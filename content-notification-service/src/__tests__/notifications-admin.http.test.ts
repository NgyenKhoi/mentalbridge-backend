import type { INestApplication } from '@nestjs/common';
import { exportSPKI, generateKeyPair, SignJWT, type KeyLike } from 'jose';
import request from 'supertest';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import { createApplication } from '../application.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import type { NotificationRepository } from '../notifications/notification.repository.js';
import type { NotificationOperationsSummary } from '../notifications/notification.types.js';

const ISSUER = 'https://identity.local.mentalbridge';
const AUDIENCE = 'mentalbridge-api';
const ADMIN_ID = 'ad3e4567-e89b-42d3-a456-426614174000';
const USER_ID = 'u13e4567-e89b-42d3-a456-426614174000';

let privateKey: KeyLike;
let publicKey: string;
let app: INestApplication;
let notificationRepository: NotificationRepository;

beforeAll(async () => {
  const keys = await generateKeyPair('RS256');
  privateKey = keys.privateKey;
  publicKey = await exportSPKI(keys.publicKey);
});

async function token(roles: string[] = ['ADMIN'], subject = ADMIN_ID): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  return new SignJWT({ roles })
    .setProtectedHeader({ alg: 'RS256' })
    .setSubject(subject)
    .setIssuer(ISSUER)
    .setAudience(AUDIENCE)
    .setIssuedAt(now)
    .setExpirationTime(now + 300)
    .sign(privateKey);
}

const mockSummary: NotificationOperationsSummary = {
  source: 'CONTENT_NOTIFICATION',
  asOf: '2026-10-05T12:00:00.000Z',
  inApp: {
    total: 150,
    delivered: 120,
    pending: 10,
    failed: 5,
    cancelled: 15,
    unread: 40,
    read: 80,
  },
  emailReminders: {
    total: 80,
    pending: 5,
    processing: 2,
    delivered: 70,
    failed: 1,
    suppressed: 1,
    invalidated: 1,
  },
};

beforeEach(async () => {
  notificationRepository = {
    getOperationsSummary: vi.fn(async () => mockSummary),
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
    JOURNAL_AI_SERVICE_URL: 'http://localhost:3005',
    JOURNAL_AI_SERVICE_TIMEOUT_MS: 2_000,
    JOURNAL_AI_REMINDER_SERVICE_TOKEN: undefined,
    REMINDER_SCHEDULER_ENABLED: false,
    REMINDER_SCHEDULER_INTERVAL_MS: 60_000,
    REMINDER_SCHEDULER_BATCH_SIZE: 100,
  };

  app = await createApplication(configuration, {
    readinessProbe: { check: async () => undefined },
    notificationRepository,
  });
  await app.init();
});

afterEach(async () => app.close());

describe('Admin Notification Operations Summary API', () => {
  it('returns 401 when request is unauthenticated', async () => {
    await request(app.getHttpServer()).get('/api/v1/admin/notifications/summary').expect(401);
  });

  it('returns 403 when non-admin role accesses the endpoint', async () => {
    const nonAdminToken = await token(['USER'], USER_ID);
    await request(app.getHttpServer())
      .get('/api/v1/admin/notifications/summary')
      .set('Authorization', `Bearer ${nonAdminToken}`)
      .expect(403);
  });

  it('returns 200 with authoritative aggregate data for ADMIN', async () => {
    const adminToken = await token(['ADMIN'], ADMIN_ID);
    const response = await request(app.getHttpServer())
      .get('/api/v1/admin/notifications/summary')
      .set('Authorization', `Bearer ${adminToken}`)
      .expect(200);

    expect(response.body).toEqual(mockSummary);
    expect(response.body.source).toBe('CONTENT_NOTIFICATION');
    expect(response.body.inApp.total).toBe(150);
    expect(response.body.emailReminders.delivered).toBe(70);

    // Verify aggregate-only facts - no sensitive content leaked
    expect(response.body).not.toHaveProperty('body');
    expect(response.body).not.toHaveProperty('email');
    expect(response.body).not.toHaveProperty('recipientId');
    expect(response.body).not.toHaveProperty('token');
  });

  it('returns 503 when database fails', async () => {
    (notificationRepository.getOperationsSummary as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
      new Error('database offline'),
    );

    const adminToken = await token(['ADMIN'], ADMIN_ID);
    await request(app.getHttpServer())
      .get('/api/v1/admin/notifications/summary')
      .set('Authorization', `Bearer ${adminToken}`)
      .expect(503);
  });
});
