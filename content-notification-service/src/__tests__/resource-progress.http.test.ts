import type { INestApplication } from '@nestjs/common';
import { exportSPKI, generateKeyPair, SignJWT, type KeyLike } from 'jose';
import request from 'supertest';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import { createApplication } from '../application.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import type { ResourceProgressRepository } from '../resources/resource-progress.repository.js';
import type { ResourceProgressItem } from '../resources/resource-progress.types.js';

const ISSUER = 'https://identity.local.mentalbridge';
const AUDIENCE = 'mentalbridge-api';
const OWNER_A = 'a13e4567-e89b-42d3-a456-426614174000';
const OWNER_B = 'b13e4567-e89b-42d3-a456-426614174000';
const RESOURCE_ID = '123e4567-e89b-42d3-a456-426614174000';
let privateKey: KeyLike;
let publicKey: string;
let app: INestApplication;
let repository: ResourceProgressRepository;
let records: Map<string, ResourceProgressItem>;

beforeAll(async () => {
  const keys = await generateKeyPair('RS256');
  privateKey = keys.privateKey;
  publicKey = await exportSPKI(keys.publicKey);
});

beforeEach(async () => {
  records = new Map();
  repository = {
    list: vi.fn(async (ownerId: string, from: string, to: string) =>
      [...records.entries()]
        .filter(
          ([key, value]) =>
            key.startsWith(`${ownerId}:`) && value.localDate >= from && value.localDate <= to,
        )
        .map(([, value]) => value),
    ),
    save: vi.fn(async (ownerId: string, resourceId: string, localDate: string, update) => {
      if (resourceId !== RESOURCE_ID) return null;
      const key = `${ownerId}:${resourceId}:${localDate}`;
      const previous = records.get(key);
      const status = previous?.status === 'COMPLETED' ? 'COMPLETED' : update.status;
      const value: ResourceProgressItem = {
        resourceId,
        localDate,
        contentVersion: '4',
        status,
        completedActionIds: [...update.completedActionIds],
        completedAt:
          status === 'COMPLETED' ? (previous?.completedAt ?? '2026-09-28T12:00:00.000Z') : null,
        updatedAt: '2026-09-28T12:00:00.000Z',
        version: String(Number(previous?.version ?? '-1') + 1),
      };
      records.set(key, value);
      return value;
    }),
  } as unknown as ResourceProgressRepository;

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
    resourceProgressRepository: repository,
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

describe('resource progress HTTP boundary', () => {
  it('requires authentication and keeps progress owner scoped', async () => {
    await request(app.getHttpServer())
      .get('/api/v1/resource-progress?from=2026-09-22&to=2026-09-28')
      .expect(401);

    await request(app.getHttpServer())
      .put(`/api/v1/resource-progress/${RESOURCE_ID}/2026-09-28`)
      .set('Authorization', `Bearer ${await token(OWNER_A)}`)
      .send({ status: 'COMPLETED', completedActionIds: ['step-1', 'step-2'] })
      .expect(200);

    const owner = await request(app.getHttpServer())
      .get('/api/v1/resource-progress?from=2026-09-22&to=2026-09-28')
      .set('Authorization', `Bearer ${await token(OWNER_A)}`)
      .expect(200)
      .expect('Cache-Control', 'private, no-store');
    expect(owner.body.items).toHaveLength(1);

    const other = await request(app.getHttpServer())
      .get('/api/v1/resource-progress?from=2026-09-22&to=2026-09-28')
      .set('Authorization', `Bearer ${await token(OWNER_B)}`)
      .expect(200);
    expect(other.body.items).toEqual([]);
  });

  it('keeps confirmed completion while accepting later checklist edits', async () => {
    const authorization = `Bearer ${await token(OWNER_A)}`;
    const target = `/api/v1/resource-progress/${RESOURCE_ID}/2026-09-28`;

    const completed = await request(app.getHttpServer())
      .put(target)
      .set('Authorization', authorization)
      .send({ status: 'COMPLETED', completedActionIds: ['step-1', 'step-2'] })
      .expect(200);

    const edited = await request(app.getHttpServer())
      .put(target)
      .set('Authorization', authorization)
      .send({ status: 'IN_PROGRESS', completedActionIds: ['step-1'] })
      .expect(200);

    expect(edited.body).toMatchObject({
      status: 'COMPLETED',
      completedActionIds: ['step-1'],
      completedAt: completed.body.completedAt,
    });
  });

  it('validates dates, identifiers, body shape, and bounded ranges', async () => {
    const authorization = `Bearer ${await token()}`;
    await request(app.getHttpServer())
      .get('/api/v1/resource-progress?from=2026-09-28&to=2026-08-01')
      .set('Authorization', authorization)
      .expect(422);
    await request(app.getHttpServer())
      .put('/api/v1/resource-progress/not-a-uuid/2026-09-28')
      .set('Authorization', authorization)
      .send({ status: 'COMPLETED', completedActionIds: [] })
      .expect(422);
    await request(app.getHttpServer())
      .put(`/api/v1/resource-progress/${RESOURCE_ID}/2026-09-28`)
      .set('Authorization', authorization)
      .send({ status: 'DONE', completedActionIds: ['unsafe value'] })
      .expect(422);
    await request(app.getHttpServer())
      .put(`/api/v1/resource-progress/${RESOURCE_ID}/2026-09-28`)
      .set('Authorization', authorization)
      .send({
        status: 'IN_PROGRESS',
        completedActionIds: ['step-1', 'step-1'],
        reflectionText: 'must not cross this boundary',
      })
      .expect(422);
  });

  it('returns not found for a resource that cannot accept current progress', async () => {
    await request(app.getHttpServer())
      .put('/api/v1/resource-progress/223e4567-e89b-42d3-a456-426614174000/2026-09-28')
      .set('Authorization', `Bearer ${await token()}`)
      .send({ status: 'IN_PROGRESS', completedActionIds: [] })
      .expect(404);
  });

  it('maps repository failures without leaking details', async () => {
    vi.mocked(repository.list).mockRejectedValueOnce(new Error('database secret'));
    const response = await request(app.getHttpServer())
      .get('/api/v1/resource-progress?from=2026-09-22&to=2026-09-28')
      .set('Authorization', `Bearer ${await token()}`)
      .expect(503);
    expect(JSON.stringify(response.body)).not.toContain('database secret');
  });
});
