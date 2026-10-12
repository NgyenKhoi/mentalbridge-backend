import type { INestApplication } from '@nestjs/common';
import { exportSPKI, generateKeyPair, SignJWT, type KeyLike } from 'jose';
import request from 'supertest';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import { createApplication } from '../application.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import type { ResourceRewardRepository } from '../resources/resource-reward.repository.js';
import type { ResourceRewardVisibility } from '../resources/resource-reward.types.js';

const ISSUER = 'https://identity.local.mentalbridge';
const AUDIENCE = 'mentalbridge-api';
const OWNER_A = 'a13e4567-e89b-42d3-a456-426614174000';
const OWNER_B = 'b13e4567-e89b-42d3-a456-426614174000';
const RESOURCE_ID = '123e4567-e89b-42d3-a456-426614174000';
let privateKey: KeyLike;
let publicKey: string;
let app: INestApplication;
let repository: ResourceRewardRepository;
let visibility: Map<string, ResourceRewardVisibility>;

beforeAll(async () => {
  const keys = await generateKeyPair('RS256');
  privateKey = keys.privateKey;
  publicKey = await exportSPKI(keys.publicKey);
});

beforeEach(async () => {
  visibility = new Map();
  repository = {
    read: vi.fn(async (ownerId: string) => ({
      facts:
        ownerId === OWNER_A
          ? [
              {
                kind: 'RESOURCE_COMPLETION' as const,
                resourceId: RESOURCE_ID,
                contentVersion: '4',
                localDate: '2026-10-12',
                occurredAt: '2026-10-12T05:00:00.000Z',
              },
            ]
          : [],
      preference: { visibilityState: visibility.get(ownerId) ?? 'VISIBLE', version: '0' },
    })),
    setVisibility: vi.fn(async (ownerId: string, value: ResourceRewardVisibility) => {
      visibility.set(ownerId, value);
    }),
  } as unknown as ResourceRewardRepository;
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
    resourceRewardRepository: repository,
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

describe('resource reward HTTP boundary', () => {
  it('requires authentication and binds facts to the authenticated owner', async () => {
    await request(app.getHttpServer()).get('/api/v1/resource-rewards').expect(401);
    const owner = await request(app.getHttpServer())
      .get('/api/v1/resource-rewards')
      .set('Authorization', `Bearer ${await token(OWNER_A)}`)
      .expect(200)
      .expect('Cache-Control', 'private, no-store');
    expect(owner.body).toMatchObject({ totalXp: 10, level: 1, sourceAvailability: 'AVAILABLE' });
    const other = await request(app.getHttpServer())
      .get('/api/v1/resource-rewards')
      .set('Authorization', `Bearer ${await token(OWNER_B)}`)
      .expect(200);
    expect(other.body).toMatchObject({ totalXp: 0, level: 0, recentGrants: [] });
  });

  it('persists an optional hidden state without changing XP', async () => {
    const authorization = `Bearer ${await token()}`;
    const response = await request(app.getHttpServer())
      .put('/api/v1/resource-rewards')
      .set('Authorization', authorization)
      .send({ visibilityState: 'HIDDEN' })
      .expect(200);
    expect(response.body).toMatchObject({ visibilityState: 'HIDDEN', totalXp: 10 });
    expect(repository.setVisibility).toHaveBeenCalledWith(OWNER_A, 'HIDDEN');
  });

  it('rejects unknown preference fields and maps source failures to 503', async () => {
    await request(app.getHttpServer())
      .put('/api/v1/resource-rewards')
      .set('Authorization', `Bearer ${await token()}`)
      .send({ visibilityState: 'VISIBLE', treatmentProgress: true })
      .expect(422);
    vi.mocked(repository.read).mockRejectedValueOnce(new Error('database secret'));
    const response = await request(app.getHttpServer())
      .get('/api/v1/resource-rewards')
      .set('Authorization', `Bearer ${await token()}`)
      .expect(503);
    expect(JSON.stringify(response.body)).not.toContain('database secret');
  });
});
