import type { INestApplication } from '@nestjs/common';
import { exportSPKI, generateKeyPair, SignJWT, type KeyLike } from 'jose';
import request from 'supertest';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import { createApplication } from '../application.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import type { SafetyDirectoryRepository } from '../safety-directory/safety-directory.repository.js';

const ISSUER = 'https://identity.local.mentalbridge';
const AUDIENCE = 'mentalbridge-api';
let privateKey: KeyLike;
let publicKey: string;
let app: INestApplication;

const repository = {
  listAdmin: vi.fn(async () => []),
  lookup: vi.fn(async () => ({ area: { provinceCode: '79', districtCode: null }, entries: [] })),
} as unknown as SafetyDirectoryRepository;

beforeAll(async () => {
  const keys = await generateKeyPair('RS256');
  privateKey = keys.privateKey;
  publicKey = await exportSPKI(keys.publicKey);
});

beforeEach(async () => {
  vi.clearAllMocks();
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
    safetyDirectoryRepository: repository,
  });
  await app.init();
});

afterEach(async () => app.close());

async function token(roles: string[]) {
  const now = Math.floor(Date.now() / 1000);
  return new SignJWT({ roles })
    .setProtectedHeader({ alg: 'RS256' })
    .setSubject('a13e4567-e89b-42d3-a456-426614174000')
    .setIssuer(ISSUER)
    .setAudience(AUDIENCE)
    .setIssuedAt(now)
    .setExpirationTime(now + 300)
    .sign(privateKey);
}

describe('safety directory HTTP boundary', () => {
  it('keeps coarse lookup public and prevents caching', async () => {
    const response = await request(app.getHttpServer())
      .post('/api/v1/safety-directory:lookup')
      .send({ provinceCode: '79' })
      .expect(200)
      .expect('Cache-Control', 'no-store');
    expect(response.body).toMatchObject({ state: 'EMPTY', wording: 'Cơ sở trong khu vực đã chọn' });
  });

  it('rejects ambiguous location input', async () => {
    await request(app.getHttpServer())
      .post('/api/v1/safety-directory:lookup')
      .send({ provinceCode: '79', manualLocation: 'Hà Nội' })
      .expect(422);
    expect(repository.lookup).not.toHaveBeenCalled();
  });

  it('rejects partially populated coverage instead of relying on a database error', async () => {
    await request(app.getHttpServer())
      .post('/api/v1/safety-directory/admin/entries')
      .set('Authorization', `Bearer ${await token(['ADMIN'])}`)
      .set('Idempotency-Key', 'coverage-shape-test')
      .send({
        name: 'Synthetic facility',
        type: 'FACILITY',
        phone: 'DEMO-NOT-DIALABLE',
        address: 'Synthetic address',
        coverage: [
          {
            level: 'NATIONWIDE',
            provinceCode: '79',
            provinceName: null,
            districtCode: null,
            districtName: null,
          },
        ],
        sourceName: 'Synthetic source',
        sourceReference: 'urn:synthetic:test',
        sourceRetrievedAt: '2026-09-01T00:00:00Z',
      })
      .expect(422);
  });

  it('limits administrative records to ADMIN', async () => {
    await request(app.getHttpServer()).get('/api/v1/safety-directory/admin/entries').expect(401);
    await request(app.getHttpServer())
      .get('/api/v1/safety-directory/admin/entries')
      .set('Authorization', `Bearer ${await token(['USER'])}`)
      .expect(403);
    await request(app.getHttpServer())
      .get('/api/v1/safety-directory/admin/entries')
      .set('Authorization', `Bearer ${await token(['ADMIN'])}`)
      .expect(200);
  });
});
