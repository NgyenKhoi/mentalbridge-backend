import { createCipheriv, createDecipheriv, createHash, randomBytes } from 'node:crypto';

import { HttpStatus, Inject, Injectable } from '@nestjs/common';
import { z } from 'zod';

import type { ServiceConfiguration } from '../configuration/configuration.js';
import { RedisService } from '../database/redis.service.js';
import { ApplicationException } from '../http/application.exception.js';
import { CONFIGURATION_TOKEN } from '../shared/tokens.js';
import type { AuthenticatedPrincipal } from './principal.js';

const TICKET_TTL_SECONDS = 30;
const ticketRecordSchema = z
  .object({
    accountId: z.uuid(),
    role: z.enum(['USER', 'SPECIALIST']),
    tokenId: z.string().min(1).max(256),
    expiresAtEpochSeconds: z.number().int().positive(),
    bodyCiphertext: z.string().min(1),
    bodyIv: z.string().min(1),
    bodyTag: z.string().min(1),
  })
  .strict();

type TicketRecord = z.infer<typeof ticketRecordSchema>;

@Injectable()
export class SocketCredentialService {
  constructor(
    private readonly redis: RedisService,
    @Inject(CONFIGURATION_TOKEN) private readonly configuration: ServiceConfiguration,
  ) {}

  async issue(
    principal: AuthenticatedPrincipal,
    bearerToken: string,
  ): Promise<{ accessToken: string; expiresAt: string }> {
    if (principal.role !== 'USER' && principal.role !== 'SPECIALIST') {
      throw new ApplicationException(
        HttpStatus.FORBIDDEN,
        'ACCESS_DENIED',
        'Socket credential access is denied',
      );
    }
    const accessToken = randomBytes(32).toString('base64url');
    const now = Math.floor(Date.now() / 1000);
    const expiresAtEpochSeconds = Math.min(
      principal.expiresAtEpochSeconds,
      now + TICKET_TTL_SECONDS,
    );
    if (expiresAtEpochSeconds <= now) throw new Error('Identity bearer has expired');
    const record: TicketRecord = {
      accountId: principal.accountId,
      role: principal.role,
      tokenId: principal.tokenId,
      expiresAtEpochSeconds: principal.expiresAtEpochSeconds,
      ...this.encrypt(bearerToken),
    };
    let stored: string | null;
    try {
      stored = await this.redis.execute((client) =>
        client.set(this.key(accessToken), JSON.stringify(record), {
          EX: expiresAtEpochSeconds - now,
          NX: true,
        }),
      );
    } catch {
      throw dependencyUnavailable();
    }
    if (stored !== 'OK') throw new Error('Socket credential could not be issued');
    return { accessToken, expiresAt: new Date(expiresAtEpochSeconds * 1000).toISOString() };
  }

  async consume(accessToken: string): Promise<{
    principal: AuthenticatedPrincipal;
    bearerToken: string;
  }> {
    let serialized: string | null;
    try {
      serialized = await this.redis.execute((client) => client.getDel(this.key(accessToken)));
    } catch {
      throw dependencyUnavailable();
    }
    if (!serialized) throw new Error('Socket credential is unavailable');
    const record = ticketRecordSchema.parse(JSON.parse(serialized) as unknown);
    if (Date.now() >= record.expiresAtEpochSeconds * 1000) throw new Error('Identity has expired');
    return {
      principal: {
        accountId: record.accountId,
        role: record.role,
        tokenId: record.tokenId,
        expiresAtEpochSeconds: record.expiresAtEpochSeconds,
      },
      bearerToken: this.decrypt(record),
    };
  }

  private key(accessToken: string): string {
    return `realtime:v1:socket-ticket:${createHash('sha256').update(accessToken).digest('hex')}`;
  }

  private encrypt(value: string) {
    const iv = randomBytes(12);
    const cipher = createCipheriv('aes-256-gcm', this.configuration.MESSAGE_ENCRYPTION_KEY, iv);
    const ciphertext = Buffer.concat([cipher.update(value, 'utf8'), cipher.final()]);
    return {
      bodyCiphertext: ciphertext.toString('base64'),
      bodyIv: iv.toString('base64'),
      bodyTag: cipher.getAuthTag().toString('base64'),
    };
  }

  private decrypt(record: TicketRecord): string {
    const decipher = createDecipheriv(
      'aes-256-gcm',
      this.configuration.MESSAGE_ENCRYPTION_KEY,
      Buffer.from(record.bodyIv, 'base64'),
    );
    decipher.setAuthTag(Buffer.from(record.bodyTag, 'base64'));
    return Buffer.concat([
      decipher.update(Buffer.from(record.bodyCiphertext, 'base64')),
      decipher.final(),
    ]).toString('utf8');
  }
}

function dependencyUnavailable(): ApplicationException {
  return new ApplicationException(
    HttpStatus.SERVICE_UNAVAILABLE,
    'DEPENDENCY_UNAVAILABLE',
    'Socket credential storage is unavailable',
    true,
  );
}
