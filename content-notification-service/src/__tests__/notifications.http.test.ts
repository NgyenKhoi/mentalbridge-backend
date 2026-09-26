import type { INestApplication } from '@nestjs/common';
import { exportSPKI, generateKeyPair, SignJWT, type KeyLike } from 'jose';
import request from 'supertest';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import { createApplication } from '../application.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import type {
  NotificationMutationResult,
  NotificationRepository,
} from '../notifications/notification.repository.js';
import type { NotificationItem } from '../notifications/notification.types.js';

const ISSUER = 'https://identity.local.mentalbridge';
const AUDIENCE = 'mentalbridge-api';
const OWNER_A = 'a13e4567-e89b-42d3-a456-426614174000';
const OWNER_B = 'b13e4567-e89b-42d3-a456-426614174000';
const NOTIFICATION_ID = 'c13e4567-e89b-42d3-a456-426614174000';
let privateKey: KeyLike;
let publicKey: string;
let app: INestApplication;
let item: NotificationItem;
let repository: NotificationRepository;

beforeAll(async () => {
  const keys = await generateKeyPair('RS256');
  privateKey = keys.privateKey;
  publicKey = await exportSPKI(keys.publicKey);
});

beforeEach(async () => {
  item = {
    id: NOTIFICATION_ID,
    kind: 'APPOINTMENT',
    title: 'Lịch tư vấn sắp diễn ra',
    body: 'Bạn có một lịch tư vấn vào ngày mai.',
    priority: 'NORMAL',
    occurredAt: '2026-09-26T01:00:00.000Z',
    createdAt: '2026-09-26T01:00:01.000Z',
    read: false,
    readAt: null,
    action: { type: 'OPEN_APPOINTMENTS', targetId: null, href: '/appointments' },
    lifecycleState: 'ACTIVE',
    expiresAt: '2026-12-25T01:00:01.000Z',
  };
  repository = {
    list: vi.fn(async (ownerId: string) => ({
      items: ownerId === OWNER_A ? [item] : [],
      nextCursor: null,
      hasMore: false,
      unreadCount: ownerId === OWNER_A && !item.read ? 1 : 0,
    })),
    markRead: vi.fn(async (ownerId: string): Promise<NotificationMutationResult> => {
      if (ownerId !== OWNER_A) return { outcome: 'NOT_FOUND' };
      if (!item.read) {
        item = { ...item, read: true, readAt: '2026-09-26T02:00:00.000Z' };
      }
      return { outcome: 'UPDATED', item };
    }),
    markAllRead: vi.fn(async (ownerId: string) => {
      if (ownerId !== OWNER_A || item.read) return 0;
      item = { ...item, read: true, readAt: '2026-09-26T02:00:00.000Z' };
      return 1;
    }),
    delete: vi.fn(async (ownerId: string) => (ownerId === OWNER_A ? 'DELETED' : 'NOT_FOUND')),
  } as unknown as NotificationRepository;

  const configuration: ServiceConfiguration = {
    NODE_ENV: 'test',
    PORT: 3003,
    DATABASE_URL: 'postgres://test:test@localhost:5432/test',
    DB_POOL_MAX: 2,
    DB_IDLE_TIMEOUT_MS: 100,
    DB_CONNECT_TIMEOUT_MS: 100,
    LOG_LEVEL: 'silent',
    CORS_ORIGINS: '',
    SERVICE_NAME: 'content-notification-service',
    ALLOWED_ORIGINS: [],
    IDENTITY_JWT_ISSUER: ISSUER,
    IDENTITY_JWT_AUDIENCE: AUDIENCE,
    IDENTITY_JWT_PUBLIC_KEY: publicKey,
    IDENTITY_JWT_CLOCK_TOLERANCE_SECONDS: 0,
  };
  app = await createApplication(configuration, {
    readinessProbe: { check: async () => undefined },
    notificationRepository: repository,
  });
  await app.init();
});

afterEach(async () => app.close());

async function token(subject = OWNER_A): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  return new SignJWT({ roles: ['USER'] })
    .setProtectedHeader({ alg: 'RS256' })
    .setSubject(subject)
    .setIssuer(ISSUER)
    .setAudience(AUDIENCE)
    .setIssuedAt(now)
    .setExpirationTime(now + 300)
    .sign(privateKey);
}

describe('notification inbox HTTP boundary', () => {
  it('requires authentication and lists only the authenticated owner without caching', async () => {
    await request(app.getHttpServer()).get('/api/v1/notifications').expect(401);

    const response = await request(app.getHttpServer())
      .get('/api/v1/notifications?limit=10')
      .set('Authorization', `Bearer ${await token()}`)
      .expect(200)
      .expect('Cache-Control', 'private, no-store');

    expect(response.body.items).toEqual([item]);
    expect(response.body.unreadCount).toBe(1);
    expect(repository.list).toHaveBeenCalledWith(OWNER_A, 10, null);

    const other = await request(app.getHttpServer())
      .get('/api/v1/notifications')
      .set('Authorization', `Bearer ${await token(OWNER_B)}`)
      .expect(200);
    expect(other.body.items).toEqual([]);
  });

  it('persists one read time across repeated and concurrent-style reads', async () => {
    const authorization = `Bearer ${await token()}`;
    const first = await request(app.getHttpServer())
      .patch(`/api/v1/notifications/${NOTIFICATION_ID}/read`)
      .set('Authorization', authorization)
      .expect(200);
    const retry = await request(app.getHttpServer())
      .patch(`/api/v1/notifications/${NOTIFICATION_ID}/read`)
      .set('Authorization', authorization)
      .expect(200);

    expect(first.body.readAt).toBe('2026-09-26T02:00:00.000Z');
    expect(retry.body.readAt).toBe(first.body.readAt);
  });

  it('marks all items read in persistence and tombstones an owned item', async () => {
    const authorization = `Bearer ${await token()}`;
    await request(app.getHttpServer())
      .post('/api/v1/notifications/mark-all-read')
      .set('Authorization', authorization)
      .expect(200, { updatedCount: 1 });
    await request(app.getHttpServer())
      .delete(`/api/v1/notifications/${NOTIFICATION_ID}`)
      .set('Authorization', authorization)
      .expect(204);

    expect(repository.markAllRead).toHaveBeenCalledWith(OWNER_A);
    expect(repository.delete).toHaveBeenCalledWith(OWNER_A, NOTIFICATION_ID);
  });

  it('does not disclose another owner notification and rejects malformed page state', async () => {
    const wrongOwner = await request(app.getHttpServer())
      .patch(`/api/v1/notifications/${NOTIFICATION_ID}/read`)
      .set('Authorization', `Bearer ${await token(OWNER_B)}`)
      .expect(404);
    expect(wrongOwner.body.code).toBe('NOTIFICATION_NOT_FOUND');

    await request(app.getHttpServer())
      .get('/api/v1/notifications?limit=51')
      .set('Authorization', `Bearer ${await token()}`)
      .expect(422);
    await request(app.getHttpServer())
      .get('/api/v1/notifications?cursor=https://example.com')
      .set('Authorization', `Bearer ${await token()}`)
      .expect(422);
  });

  it('maps repository failure to a bounded dependency response', async () => {
    vi.mocked(repository.list).mockRejectedValueOnce(new Error('private database detail'));
    const response = await request(app.getHttpServer())
      .get('/api/v1/notifications')
      .set('Authorization', `Bearer ${await token()}`)
      .expect(503);

    expect(response.body.code).toBe('DEPENDENCY_UNAVAILABLE');
    expect(JSON.stringify(response.body)).not.toContain('private database detail');
  });
});
