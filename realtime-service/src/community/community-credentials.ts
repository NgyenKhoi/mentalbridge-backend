import { createHash, randomBytes } from 'node:crypto';
import type { IncomingMessage, ServerResponse } from 'node:http';
import {
  Controller,
  HttpCode,
  Inject,
  Injectable,
  Post,
  Req,
  Res,
  type OnModuleInit,
} from '@nestjs/common';
import { importSPKI, jwtVerify, type CryptoKey } from 'jose';
import { z } from 'zod';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import { RedisService } from '../database/redis.service.js';
import { ApplicationException } from '../http/application.exception.js';
import { CONFIGURATION_TOKEN } from '../shared/tokens.js';
import { CommunityOwnerClient } from './community-owner.client.js';
import { CommunityError } from './community-schema.js';

const sessionSchema = z
  .object({ subject: z.uuid(), identityExpiresAt: z.number().int().positive() })
  .strict();
const delegationSchema = z
  .object({
    iss: z.literal('community-bff'),
    aud: z.literal('realtime-service'),
    sub: z.uuid(),
    act: z.object({ sub: z.literal('community-bff') }).strict(),
    scope: z.literal('community:socket:issue'),
    jti: z.uuid(),
    iat: z.number().int().nonnegative(),
    exp: z.number().int().positive(),
    identityExpiresAt: z.number().int().positive(),
  })
  .strict();
export type CommunitySession = z.infer<typeof sessionSchema>;
@Injectable()
export class CommunityCredentials implements OnModuleInit {
  private key!: CryptoKey;
  constructor(
    private readonly redis: RedisService,
    private readonly owner: CommunityOwnerClient,
    @Inject(CONFIGURATION_TOKEN) private readonly configuration: ServiceConfiguration,
  ) {}
  async onModuleInit(): Promise<void> {
    this.key = await importSPKI(this.configuration.COMMUNITY.bffPublicKey, 'RS256');
  }
  async issue(
    token: string,
  ): Promise<{ schemaVersion: 1; namespace: '/community'; credential: string; expiresAt: string }> {
    let session: CommunitySession;
    try {
      const verified = await jwtVerify(token, this.key, {
        algorithms: ['RS256'],
        issuer: 'community-bff',
        audience: 'realtime-service',
        clockTolerance: 0,
      });
      const p = delegationSchema.parse(verified.payload);
      const now = Math.floor(Date.now() / 1000);
      if (
        verified.protectedHeader.kid !== this.configuration.COMMUNITY.bffKeyId ||
        p.iat > now ||
        p.exp <= p.iat ||
        p.exp - p.iat > 30 ||
        p.identityExpiresAt <= now
      )
        throw new Error('Invalid credential delegation');
      session = { subject: p.sub, identityExpiresAt: p.identityExpiresAt };
    } catch {
      throw new ApplicationException(401, 'UNAUTHENTICATED', 'Authentication is required');
    }
    await this.owner.session(session.subject);
    const credential = randomBytes(32).toString('base64url');
    const ttl = Math.min(30, session.identityExpiresAt - Math.floor(Date.now() / 1000));
    if (ttl <= 0)
      throw new ApplicationException(401, 'UNAUTHENTICATED', 'Authentication has expired');
    const stored = await this.redis.execute((client) =>
      client.set(this.keyFor(credential), JSON.stringify(session), { EX: ttl, NX: true }),
    );
    if (stored !== 'OK') throw new CommunityError('DEPENDENCY_UNAVAILABLE');
    return {
      schemaVersion: 1,
      namespace: '/community',
      credential,
      expiresAt: new Date((Math.floor(Date.now() / 1000) + ttl) * 1000).toISOString(),
    };
  }
  async consume(credential: string): Promise<CommunitySession> {
    const value = await this.redis.execute((client) => client.getDel(this.keyFor(credential)));
    if (!value) throw new Error('Credential unavailable');
    const parsed: unknown = JSON.parse(value);
    const session = sessionSchema.parse(parsed);
    if (Date.now() >= session.identityExpiresAt * 1000) throw new Error('Identity expired');
    await this.owner.session(session.subject);
    return session;
  }
  async register(session: CommunitySession, id: string): Promise<boolean> {
    const now = Date.now();
    return (
      (await this.redis.execute(
        async (client) =>
          await client.eval(
            "redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1]); if redis.call('ZCARD', KEYS[1]) >= 5 then return 0 end; redis.call('ZADD', KEYS[1], ARGV[2], ARGV[3]); redis.call('PEXPIRE', KEYS[1], 60000); return 1",
            {
              keys: [this.connections(session.subject)],
              arguments: [String(now), String(now + 60_000), id],
            },
          ),
      )) === 1
    );
  }
  async renew(session: CommunitySession, id: string): Promise<boolean> {
    return (
      (await this.redis.execute(
        async (client) =>
          await client.eval(
            "if not redis.call('ZSCORE', KEYS[1], ARGV[1]) then return 0 end; redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1]); redis.call('PEXPIRE', KEYS[1], 60000); return 1",
            {
              keys: [this.connections(session.subject)],
              arguments: [id, String(Date.now() + 60_000)],
            },
          ),
      )) === 1
    );
  }
  async remove(session: CommunitySession, id: string): Promise<void> {
    await this.redis.execute((client) => client.zRem(this.connections(session.subject), id));
  }
  private keyFor(value: string): string {
    return 'community:v1:ticket:' + createHash('sha256').update(value).digest('hex');
  }
  private connections(subject: string): string {
    return 'community:v1:connections:' + subject;
  }
}

@Controller('internal/v1/community/socket-credentials')
export class CommunityCredentialController {
  constructor(private readonly credentials: CommunityCredentials) {}
  @Post()
  @HttpCode(200)
  async issue(
    @Req() request: IncomingMessage,
    @Res({ passthrough: true }) response: ServerResponse,
  ) {
    response.setHeader('Cache-Control', 'no-store');
    const token = /^Bearer ([^\s]+)$/.exec(request.headers.authorization ?? '')?.[1];
    if (!token)
      throw new ApplicationException(401, 'UNAUTHENTICATED', 'Authentication is required');
    try {
      return await this.credentials.issue(token);
    } catch (error) {
      if (error instanceof ApplicationException) throw error;
      if (error instanceof CommunityError && error.code === 'TARGET_UNAVAILABLE')
        throw new ApplicationException(404, 'TARGET_UNAVAILABLE', 'Target is unavailable');
      throw new ApplicationException(
        503,
        'DEPENDENCY_UNAVAILABLE',
        'Community credential is unavailable',
        true,
      );
    }
  }
}
