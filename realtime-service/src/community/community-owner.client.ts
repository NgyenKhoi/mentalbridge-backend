import { randomUUID } from 'node:crypto';
import { Inject, Injectable, type OnModuleInit, type OnApplicationShutdown } from '@nestjs/common';
import { importPKCS8, SignJWT, type CryptoKey } from 'jose';
import { z } from 'zod';
import { Agent, fetch } from 'undici';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import { CONFIGURATION_TOKEN } from '../shared/tokens.js';
import {
  CommunityError,
  messageSchema,
  replaySchema,
  presencePeersSchema,
  type PresencePeer,
  type CommunityMessage,
  type ReplayProjection,
} from './community-schema.js';

@Injectable()
export class CommunityOwnerClient implements OnModuleInit, OnApplicationShutdown {
  private readonly dispatcher = new Agent({
    connectTimeout: 500,
    connections: 32,
    pipelining: 1,
    maxResponseSize: 256_000,
  });
  private key!: CryptoKey;
  private failures = 0;
  private openUntil = 0;
  constructor(@Inject(CONFIGURATION_TOKEN) private readonly configuration: ServiceConfiguration) {}
  async onModuleInit(): Promise<void> {
    this.key = await importPKCS8(this.configuration.COMMUNITY.privateKey, 'RS256');
  }
  async onApplicationShutdown(): Promise<void> {
    await this.dispatcher.close();
  }
  async session(subject: string, correlationId: string = randomUUID()): Promise<void> {
    await this.authorize(
      'session-authorizations',
      'community:session:authorize',
      subject,
      undefined,
      correlationId,
    );
  }
  async subscribe(subject: string, conversationId: string, correlationId: string): Promise<void> {
    await this.authorize(
      'realtime-authorizations',
      'community:subscribe',
      subject,
      { conversationId, action: 'SUBSCRIBE' },
      correlationId,
    );
  }
  async message(
    subject: string,
    conversationId: string,
    messageId: string,
    correlationId: string,
  ): Promise<CommunityMessage> {
    const value = await this.request(
      'message-projections',
      'community:message:project',
      subject,
      { conversationId, messageId },
      correlationId,
    );
    const parsed = messageSchema.safeParse(value);
    if (
      !parsed.success ||
      parsed.data.messageId !== messageId ||
      parsed.data.conversationId !== conversationId
    )
      throw new CommunityError('DEPENDENCY_UNAVAILABLE');
    return parsed.data;
  }
  async presence(
    subject: string,
    conversationId: string,
    correlationId: string,
  ): Promise<PresencePeer[]> {
    const parsed = presencePeersSchema.safeParse(
      await this.request(
        'presence-projections',
        'community:presence:project',
        subject,
        { conversationId },
        correlationId,
      ),
    );
    if (
      !parsed.success ||
      parsed.data.peers.some((peer) => peer.subjectRoutingReference === subject)
    )
      throw new CommunityError('DEPENDENCY_UNAVAILABLE');
    return parsed.data.peers;
  }
  async replay(
    subject: string,
    conversationId: string,
    cursor: string | null,
    correlationId: string,
  ): Promise<ReplayProjection> {
    const parsed = replaySchema.safeParse(
      await this.request(
        'replay-projections',
        'community:replay:project',
        subject,
        { conversationId, cursor },
        correlationId,
      ),
    );
    if (!parsed.success) throw new CommunityError('DEPENDENCY_UNAVAILABLE');
    return parsed.data;
  }
  private async authorize(
    path: string,
    scope: string,
    subject: string,
    body: Record<string, unknown> | undefined,
    correlationId: string,
  ): Promise<void> {
    const parsed = z
      .object({ allowed: z.boolean(), decisionVersion: z.string().regex(/^(0|[1-9][0-9]{0,18})$/) })
      .strict()
      .safeParse(await this.request(path, scope, subject, body, correlationId));
    if (!parsed.success) throw new CommunityError('DEPENDENCY_UNAVAILABLE');
    if (!parsed.data.allowed) throw new CommunityError('TARGET_UNAVAILABLE');
  }
  private async request(
    path: string,
    scope: string,
    subject: string,
    body: Record<string, unknown> | undefined,
    correlationId: string,
  ): Promise<unknown> {
    if (Date.now() < this.openUntil) throw new CommunityError('DEPENDENCY_UNAVAILABLE');
    const now = Math.floor(Date.now() / 1000);
    const claims = {
      act: { sub: 'realtime-service' },
      scope,
      ...(typeof body?.conversationId === 'string' ? { conversationId: body.conversationId } : {}),
      ...(typeof body?.messageId === 'string' ? { messageId: body.messageId } : {}),
    };
    const token = await new SignJWT(claims)
      .setProtectedHeader({ alg: 'RS256', kid: this.configuration.COMMUNITY.keyId })
      .setIssuer('realtime-service')
      .setAudience('community-service')
      .setSubject(subject)
      .setJti(randomUUID())
      .setIssuedAt(now)
      .setExpirationTime(now + 30)
      .sign(this.key);
    try {
      const response = await fetch(
        new URL('/internal/v1/community/chat/' + path, this.configuration.COMMUNITY.baseUrl),
        {
          method: 'POST',
          headers: {
            authorization: 'Bearer ' + token,
            'content-type': 'application/json',
            'x-correlation-id': correlationId,
          },
          ...(body ? { body: JSON.stringify(body) } : {}),
          signal: AbortSignal.timeout(2000),
          redirect: 'error',
          dispatcher: this.dispatcher,
        },
      );
      if (response.status === 404 || response.status === 403 || response.status === 401) {
        await response.body?.cancel();
        this.failures = 0;
        throw new CommunityError('TARGET_UNAVAILABLE');
      }
      if (!response.ok) {
        await response.body?.cancel();
        throw new Error('Owner unavailable');
      }
      const text = await response.text();
      if (Buffer.byteLength(text) > 256_000) throw new Error('Owner response too large');
      const value: unknown = JSON.parse(text);
      this.failures = 0;
      return value;
    } catch (error) {
      if (error instanceof CommunityError) throw error;
      if (++this.failures >= 5) {
        this.openUntil = Date.now() + 5000;
        this.failures = 0;
      }
      throw new CommunityError('DEPENDENCY_UNAVAILABLE');
    }
  }
}
