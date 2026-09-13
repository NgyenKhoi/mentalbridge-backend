import type { INestApplication } from '@nestjs/common';
import { exportSPKI, generateKeyPair, SignJWT, type KeyLike } from 'jose';
import type { Server } from 'node:http';
import request from 'supertest';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import { createApplication } from '../application.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import type { ResourceEligibilityRepository } from '../resources/resource-eligibility.repository.js';

const ADMIN_ID = 'a13e4567-e89b-42d3-a456-426614174000';
const USER_ID = 'b13e4567-e89b-42d3-a456-426614174000';
const RESOURCE_ID = 'c13e4567-e89b-42d3-a456-426614174000';
const REQUEST_ID = 'd13e4567-e89b-42d3-a456-426614174000';
const ISSUER = 'https://identity.local.mentalbridge';
const AUDIENCE = 'mentalbridge-api';

const publication = {
  publicationId: 'e13e4567-e89b-42d3-a456-426614174000',
  resourceId: RESOURCE_ID,
  contentVersion: '3',
  policyVersion: 'content-eligibility-v1' as const,
  locale: 'vi-VN',
  state: 'PUBLISHED' as const,
  effectiveAt: '2026-09-13T00:00:00Z',
  expiresAt: null,
  declarations: [
    {
      targetDomain: 'ANXIETY_SYMPTOMS' as const,
      role: 'PRIMARY' as const,
      instrument: 'GAD_7' as const,
      screeningLevels: ['MILD'] as const,
      supportTiers: ['SELF_GUIDED_SUPPORT'] as const,
    },
  ],
  publishedAt: '2026-09-13T00:00:01Z',
  withdrawnAt: null,
  withdrawalReasonCode: null,
};

const repository = {
  publish: vi.fn(async () => publication),
  withdraw: vi.fn(async () => ({ ...publication, state: 'WITHDRAWN' as const })),
  resolve: vi.fn(async () => ({
    policyVersion: 'content-eligibility-v1' as const,
    resolvedAt: '2026-09-13T00:00:02Z',
    results: [
      {
        requestId: REQUEST_ID,
        resourceId: RESOURCE_ID,
        contentVersion: '3',
        outcome: 'ELIGIBLE' as const,
        reasonCode: 'ELIGIBLE_MATCH' as const,
        role: 'PRIMARY' as const,
        publicationId: publication.publicationId,
      },
    ],
  })),
} as unknown as ResourceEligibilityRepository;

let privateKey: KeyLike;
let publicKey: string;
let app: INestApplication | undefined;

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
    resourceEligibilityRepository: repository,
  });
  await app.init();
});

afterEach(async () => {
  await app?.close();
  app = undefined;
});

describe('Resource Eligibility HTTP boundary', () => {
  it('publishes explicit exact-version eligibility for an administrator', async () => {
    const response = await request(server())
      .post(`/api/v1/resources/${RESOURCE_ID}/versions/3/eligibility-publications`)
      .set('authorization', `Bearer ${await token(ADMIN_ID, ['ADMIN'])}`)
      .set('idempotency-key', 'eligibility-publication-1')
      .send({
        policyVersion: 'content-eligibility-v1',
        locale: 'vi-VN',
        effectiveAt: '2026-09-13T00:00:00Z',
        expiresAt: null,
        declarations: publication.declarations,
      })
      .expect(201);

    expect(response.body).toMatchObject({
      resourceId: RESOURCE_ID,
      contentVersion: '3',
      state: 'PUBLISHED',
    });
    expect(repository.publish).toHaveBeenCalledOnce();
  });

  it('rejects user publication and unapproved pseudo-domains', async () => {
    await request(server())
      .post(`/api/v1/resources/${RESOURCE_ID}/versions/3/eligibility-publications`)
      .set('authorization', `Bearer ${await token(USER_ID, ['USER'])}`)
      .set('idempotency-key', 'eligibility-publication-2')
      .send({})
      .expect(403);

    const response = await request(server())
      .post(`/api/v1/resources/${RESOURCE_ID}/versions/3/eligibility-publications`)
      .set('authorization', `Bearer ${await token(ADMIN_ID, ['ADMIN'])}`)
      .set('idempotency-key', 'eligibility-publication-3')
      .send({
        policyVersion: 'content-eligibility-v1',
        locale: 'vi-VN',
        effectiveAt: '2026-09-13T00:00:00Z',
        declarations: [
          {
            targetDomain: 'GENERAL_WELLBEING',
            role: 'PRIMARY',
            instrument: 'GAD_7',
            screeningLevels: ['MILD'],
            supportTiers: ['SELF_GUIDED_SUPPORT'],
          },
        ],
      })
      .expect(422);

    expect(response.body.code).toBe('VALIDATION_ERROR');
  });

  it('requires a USER credential and preserves deterministic batch order', async () => {
    await request(server())
      .post('/internal/v1/resource-eligibility:resolve')
      .send({ requests: [] })
      .expect(401);

    const response = await request(server())
      .post('/internal/v1/resource-eligibility:resolve')
      .set('authorization', `Bearer ${await token(USER_ID, ['USER'])}`)
      .send({ requests: [eligibilityQuery()] })
      .expect(200);

    expect(response.body.results).toEqual([
      expect.objectContaining({ requestId: REQUEST_ID, outcome: 'ELIGIBLE', role: 'PRIMARY' }),
    ]);
  });

  it('fails closed when the provider dependency is unavailable', async () => {
    await app?.close();
    app = undefined;
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
      resourceEligibilityRepository: repository,
      outageState: { enabled: true },
    });
    await app.init();

    const response = await request(server())
      .post('/internal/v1/resource-eligibility:resolve')
      .set('authorization', `Bearer ${await token(USER_ID, ['USER'])}`)
      .send({ requests: [eligibilityQuery()] })
      .expect(503);
    expect(response.body.code).toBe('DEPENDENCY_UNAVAILABLE');
  });
});

function eligibilityQuery() {
  return {
    requestId: REQUEST_ID,
    resourceId: RESOURCE_ID,
    contentVersion: '3',
    targetDomain: 'ANXIETY_SYMPTOMS',
    requiredRole: 'PRIMARY',
    instrument: 'GAD_7',
    screeningLevel: 'MILD',
    supportTier: 'SELF_GUIDED_SUPPORT',
    locale: 'vi-VN',
  };
}

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
