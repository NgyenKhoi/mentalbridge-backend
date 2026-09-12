import type { INestApplication } from '@nestjs/common';
import { exportSPKI, generateKeyPair, SignJWT, type KeyLike } from 'jose';
import type { Server } from 'node:http';
import request from 'supertest';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import { createApplication } from '../application.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import type { ResourceRepository } from '../resources/resource.repository.js';
import type { ResourceRow } from '../resources/resource.types.js';

const ADMIN_ID = 'a13e4567-e89b-42d3-a456-426614174000';
const USER_ID = 'b13e4567-e89b-42d3-a456-426614174000';
const RESOURCE_ID = 'c13e4567-e89b-42d3-a456-426614174000';
const CORRELATION_ID = 'd13e4567-e89b-42d3-a456-426614174000';
const ISSUER = 'https://identity.local.mentalbridge';
const AUDIENCE = 'mentalbridge-api';

let privateKey: KeyLike;
let publicKey: string;
let app: INestApplication | undefined;

const resource: ResourceRow = {
  id: RESOURCE_ID,
  category: 'ARTICLE',
  locale: 'vi-VN',
  title: 'Draft resource',
  summary: 'Draft summary',
  content_body: 'Draft body',
  external_url: null,
  status: 'DRAFT',
  reviewed_by: null,
  reviewed_at: null,
  effective_at: null,
  expires_at: null,
  created_at: new Date('2026-09-01T00:00:00Z'),
  updated_at: new Date('2026-09-01T00:00:00Z'),
  version: 0,
};

const repository = {
  listPublished: vi.fn(async () => []),
  listAdmin: vi.fn(async () => [resource]),
  findById: vi.fn(async () => resource),
  findPublishedEligibleById: vi.fn(async () => null),
  create: vi.fn(async () => resource),
  update: vi.fn(async () => resource),
  delete: vi.fn(async () => true),
  archive: vi.fn(async () => null),
  auditPublishBlocked: vi.fn(async () => undefined),
} as unknown as ResourceRepository;

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
    resourceRepository: repository,
  });
  await app.init();
});

afterEach(async () => {
  await app?.close();
  app = undefined;
});

async function token(subject: string, roles: string[]) {
  const now = Math.floor(Date.now() / 1000);
  return new SignJWT({ roles })
    .setProtectedHeader({ alg: 'RS256' })
    .setSubject(subject)
    .setIssuer(ISSUER)
    .setAudience(AUDIENCE)
    .setIssuedAt(now)
    .setNotBefore(now)
    .setExpirationTime(now + 300)
    .setJti(crypto.randomUUID())
    .sign(privateKey);
}

function server(): Server {
  return app!.getHttpServer() as Server;
}

describe('resource HTTP and authorization boundary', () => {
  it('allows ADMIN to list every status and rejects USER', async () => {
    const adminToken = await token(ADMIN_ID, ['ADMIN']);
    const userToken = await token(USER_ID, ['USER']);

    const adminResponse = await request(server())
      .get('/api/v1/resources/admin/list?status=DRAFT')
      .set('authorization', `Bearer ${adminToken}`);
    expect(adminResponse.body).toMatchObject({
      data: [expect.objectContaining({ status: 'DRAFT' })],
    });
    expect(adminResponse.status).toBe(200);

    const forbidden = await request(server())
      .get('/api/v1/resources/admin/list')
      .set('authorization', `Bearer ${userToken}`)
      .expect(403);
    expect(forbidden.headers['content-type']).toMatch(/^application\/problem\+json/);
    expect(forbidden.body.code).toBe('FORBIDDEN');
  });

  it('returns stable Problem Details when the admin catalogue is unavailable', async () => {
    vi.mocked(repository.listAdmin).mockRejectedValueOnce(new Error('database unavailable'));
    const adminToken = await token(ADMIN_ID, ['ADMIN']);

    const response = await request(server())
      .get('/api/v1/resources/admin/list')
      .set('authorization', `Bearer ${adminToken}`)
      .set('x-correlation-id', CORRELATION_ID)
      .expect(503);

    expect(response.headers['content-type']).toMatch(/^application\/problem\+json/);
    expect(response.body).toMatchObject({
      code: 'DEPENDENCY_UNAVAILABLE',
      correlationId: CORRELATION_ID,
    });
  });

  it('returns draft detail only through the protected admin route', async () => {
    const adminToken = await token(ADMIN_ID, ['ADMIN']);
    const detail = await request(server())
      .get(`/api/v1/resources/admin/${RESOURCE_ID}`)
      .set('authorization', `Bearer ${adminToken}`)
      .expect(200);

    expect(detail.body).toMatchObject({ id: RESOURCE_ID, status: 'DRAFT', version: 0 });
    await request(server()).get(`/api/v1/resources/${RESOURCE_ID}?locale=vi-VN`).expect(404);
    expect(repository.findPublishedEligibleById).toHaveBeenCalledWith(RESOURCE_ID, 'vi-VN');
  });

  it('omits reviewer and version from eligible public detail', async () => {
    vi.mocked(repository.findPublishedEligibleById).mockResolvedValueOnce({
      ...resource,
      status: 'PUBLISHED',
      reviewed_by: ADMIN_ID,
      reviewed_at: new Date('2026-09-01T01:00:00Z'),
    });

    const response = await request(server())
      .get(`/api/v1/resources/${RESOURCE_ID}?locale=vi-VN`)
      .expect(200);
    expect(response.body.contentBody).toBe('Draft body');
    expect(response.body).not.toHaveProperty('reviewedBy');
    expect(response.body).not.toHaveProperty('version');
  });

  it('preserves validation Problem Details and field violations', async () => {
    const adminToken = await token(ADMIN_ID, ['ADMIN']);
    const response = await request(server())
      .post('/api/v1/resources')
      .set('authorization', `Bearer ${adminToken}`)
      .set('idempotency-key', 'create-1')
      .set('x-correlation-id', CORRELATION_ID)
      .send({ category: 'ARTICLE', title: '', summary: '' })
      .expect(422);

    expect(response.headers['content-type']).toMatch(/^application\/problem\+json/);
    expect(response.headers['x-correlation-id']).toBe(CORRELATION_ID);
    expect(response.body).toMatchObject({
      code: 'VALIDATION_ERROR',
      correlationId: CORRELATION_ID,
    });
    expect(response.body.fieldViolations).toEqual(
      expect.arrayContaining([expect.objectContaining({ field: 'title' })]),
    );
  });

  it('requires an idempotency key and forwards actor-scoped command context', async () => {
    const adminToken = await token(ADMIN_ID, ['ADMIN']);
    await request(server())
      .post('/api/v1/resources')
      .set('authorization', `Bearer ${adminToken}`)
      .send({ category: 'ARTICLE', title: 'Title', summary: 'Summary', contentBody: 'Body' })
      .expect(400);

    await request(server())
      .post('/api/v1/resources')
      .set('authorization', `Bearer ${adminToken}`)
      .set('idempotency-key', 'create-2')
      .set('x-correlation-id', CORRELATION_ID)
      .send({ category: 'ARTICLE', title: 'Title', summary: 'Summary', contentBody: 'Body' })
      .expect(201);

    expect(repository.create).toHaveBeenCalledWith(
      expect.objectContaining({ title: 'Title' }),
      'create-2',
      { actorId: ADMIN_ID, correlationId: CORRELATION_ID },
    );
  });

  it('requires optimistic versioning for delete', async () => {
    const adminToken = await token(ADMIN_ID, ['ADMIN']);
    await request(server())
      .delete(`/api/v1/resources/${RESOURCE_ID}`)
      .set('authorization', `Bearer ${adminToken}`)
      .expect(400);

    vi.mocked(repository.delete).mockResolvedValueOnce(false);
    const stale = await request(server())
      .delete(`/api/v1/resources/${RESOURCE_ID}?version=9`)
      .set('authorization', `Bearer ${adminToken}`)
      .expect(409);
    expect(stale.body.code).toBe('INVALID_STATE_TRANSITION');
  });

  it('keeps publish blocked without fabricating review provenance', async () => {
    const adminToken = await token(ADMIN_ID, ['ADMIN']);
    const response = await request(server())
      .post(`/api/v1/resources/${RESOURCE_ID}/publish?version=0`)
      .set('authorization', `Bearer ${adminToken}`)
      .send({})
      .expect(409);

    expect(response.body.code).toBe('REVIEW_APPROVAL_REQUIRED');
    expect(repository.auditPublishBlocked).toHaveBeenCalledWith(RESOURCE_ID, 0, {
      actorId: ADMIN_ID,
      correlationId: expect.any(String),
    });
    expect(repository.update).not.toHaveBeenCalled();
    expect(repository.archive).not.toHaveBeenCalled();
  });
});
