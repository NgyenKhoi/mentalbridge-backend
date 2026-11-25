import { createServer, type Server } from 'node:http';
import { randomUUID } from 'node:crypto';
import { exportPKCS8, generateKeyPair, jwtVerify } from 'jose';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { CommunityNotificationClient } from '../community-notifications/community-notification.client.js';

describe('Community notification signed owner boundary', () => {
  let server: Server;
  let client: CommunityNotificationClient;
  let status = 200;
  let response: unknown = { allowed: true, decisionVersion: '0' };
  let observed: unknown;
  const target = {
    recipientRoutingReference: randomUUID(),
    kind: 'MESSAGE_RECEIVED' as const,
    targetId: randomUUID(),
    conversationId: randomUUID(),
    actorCommunityProfileId: randomUUID(),
  };
  beforeAll(async () => {
    const keys = await generateKeyPair('RS256', { extractable: true });
    server = createServer((request, result) => {
      void (async () => {
        const token = request.headers.authorization?.slice(7) ?? '';
        const jwt = await jwtVerify(token, keys.publicKey, {
          issuer: 'content-notification-service',
          audience: 'community-service',
          subject: 'content-notification-service',
          algorithms: ['RS256'],
        });
        expect(jwt.payload.scope).toBe('community:notification:authorize');
        expect((jwt.payload.exp ?? 0) - (jwt.payload.iat ?? 0)).toBe(30);
        const chunks: Buffer[] = [];
        for await (const chunk of request) chunks.push(Buffer.from(chunk));
        observed = JSON.parse(Buffer.concat(chunks).toString('utf8'));
        result.writeHead(status, { 'Content-Type': 'application/json' });
        result.end(JSON.stringify(response));
      })().catch(() => {
        result.writeHead(500);
        result.end();
      });
    });
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') throw new Error('Fixture unavailable');
    client = new CommunityNotificationClient({
      CONTENT_COMMUNITY_NOTICE_CONSUMER_ENABLED: true,
      CONTENT_COMMUNITY_NOTIFICATION_PRIVATE_KEY: await exportPKCS8(keys.privateKey),
      CONTENT_COMMUNITY_NOTIFICATION_KEY_ID: 'content-test',
      COMMUNITY_SERVICE_URL: 'http://127.0.0.1:' + String(address.port),
    } as never);
    await client.onModuleInit();
  });
  afterAll(async () => {
    server.closeAllConnections();
    await new Promise<void>((resolve) => server.close(() => resolve()));
  });
  it('sends only the exact query under the dedicated short-lived service scope', async () => {
    status = 200;
    response = { allowed: true, decisionVersion: '0' };
    expect(await client.authorize(target)).toBe(true);
    expect(observed).toEqual(target);
  });
  it('treats current denial as cancellation but uncertainty as retryable', async () => {
    status = 404;
    expect(await client.authorize(target)).toBe(false);
    for (const code of [401, 403, 503]) {
      status = code;
      await expect(client.authorize(target)).rejects.toThrow();
    }
    status = 200;
    response = { allowed: true, cachedPermission: true };
    await expect(client.authorize(target)).rejects.toThrow();
    response = { allowed: false, decisionVersion: '1' };
    expect(await client.authorize(target)).toBe(false);
  });
});
