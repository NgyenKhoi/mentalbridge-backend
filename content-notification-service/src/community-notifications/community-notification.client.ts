import { Inject, Injectable, type OnModuleInit } from '@nestjs/common';
import { randomUUID } from 'node:crypto';
import type { ReadableStreamReadResult } from 'node:stream/web';
import { importPKCS8, SignJWT } from 'jose';
import { z } from 'zod';
import { CONFIGURATION_TOKEN } from '../application.tokens.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import type { CommunityNotificationProjection } from './community-interaction.types.js';

const decision = z
  .object({ allowed: z.boolean(), decisionVersion: z.string().regex(/^(0|[1-9][0-9]{0,18})$/) })
  .strict();

@Injectable()
export class CommunityNotificationClient implements OnModuleInit {
  private key?: Awaited<ReturnType<typeof importPKCS8>>;
  constructor(@Inject(CONFIGURATION_TOKEN) private readonly configuration: ServiceConfiguration) {}
  async onModuleInit(): Promise<void> {
    if (!this.configuration.CONTENT_COMMUNITY_NOTICE_CONSUMER_ENABLED) return;
    try {
      this.key = await importPKCS8(
        (this.configuration.CONTENT_COMMUNITY_NOTIFICATION_PRIVATE_KEY ?? '').replaceAll(
          '\\n',
          '\n',
        ),
        'RS256',
      );
    } catch {
      throw new Error('Community notification signing key is invalid');
    }
  }
  async authorize(
    target: NonNullable<CommunityNotificationProjection['authorization']>,
  ): Promise<boolean> {
    if (!this.key) throw new Error('Community notification authorization is unavailable');
    const now = Math.floor(Date.now() / 1000);
    const token = await new SignJWT({ scope: 'community:notification:authorize' })
      .setProtectedHeader({
        alg: 'RS256',
        kid: this.configuration.CONTENT_COMMUNITY_NOTIFICATION_KEY_ID,
      })
      .setIssuer('content-notification-service')
      .setSubject('content-notification-service')
      .setAudience('community-service')
      .setJti(randomUUID())
      .setIssuedAt(now)
      .setExpirationTime(now + 30)
      .sign(this.key);
    const response = await fetch(
      new URL(
        '/internal/v1/community/chat/notification-authorizations',
        this.configuration.COMMUNITY_SERVICE_URL,
      ),
      {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${token}`,
          'Content-Type': 'application/json',
          'X-Correlation-Id': randomUUID(),
        },
        body: JSON.stringify(target),
        redirect: 'error',
        signal: AbortSignal.timeout(2000),
      },
    );
    if (response.status === 404) {
      await response.body?.cancel();
      return false;
    }
    if (!response.ok) {
      await response.body?.cancel();
      throw new Error('Community notification authorization is unavailable');
    }
    const reader = response.body?.getReader();
    if (!reader) throw new Error('Community notification decision is invalid');
    let wire = '';
    try {
      const decoder = new TextDecoder();
      let bytes = 0;
      for (;;) {
        const chunk: ReadableStreamReadResult<unknown> = await reader.read();
        if (chunk.done) break;
        if (!(chunk.value instanceof Uint8Array))
          throw new Error('Community notification decision is invalid');
        bytes += chunk.value.byteLength;
        if (bytes > 4096) throw new Error('Community notification decision is invalid');
        wire += decoder.decode(chunk.value, { stream: true });
      }
      wire += decoder.decode();
      return decision.parse(JSON.parse(wire)).allowed;
    } finally {
      await reader.cancel().catch(() => undefined);
    }
  }
}
