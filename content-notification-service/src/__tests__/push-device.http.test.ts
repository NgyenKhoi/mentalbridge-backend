import type { INestApplication } from '@nestjs/common';
import { exportSPKI, generateKeyPair, SignJWT, type KeyLike } from 'jose';
import request from 'supertest';
import { randomBytes } from 'node:crypto';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import { createApplication } from '../application.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import type { PushDeviceRepository } from '../push-delivery/push-device.repository.js';

const ISSUER = 'https://identity.local.mentalbridge';
const AUDIENCE = 'mentalbridge-api';
const OWNER = 'a13e4567-e89b-42d3-a456-426614174000';
const INSTALLATION = 'b13e4567-e89b-42d3-a456-426614174000';
let privateKey: KeyLike;
let publicKey: string;
let app: INestApplication;
let repository: PushDeviceRepository;

beforeAll(async () => {
  const keys = await generateKeyPair('RS256');
  privateKey = keys.privateKey;
  publicKey = await exportSPKI(keys.publicKey);
});

beforeEach(async () => {
  repository = {
    register: vi.fn(async (ownerId, input) => ({
      installationId: input.installationId,
      platform: input.platform,
      provider: input.provider,
      permissionStatus: input.permissionStatus,
      status: input.permissionStatus === 'GRANTED' ? 'ACTIVE' : 'DISABLED',
      appVersion: input.appVersion ?? null,
      lastRegisteredAt: '2026-10-22T00:00:00.000Z',
      version: ownerId === OWNER ? 1 : 0,
    })),
    revoke: vi.fn(async (ownerId: string) => ownerId === OWNER),
    materializeCandidates: vi.fn(async () => 0),
    settleIneligible: vi.fn(async () => undefined),
    claim: vi.fn(async () => []),
  } as unknown as PushDeviceRepository;
  const configuration = {
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
    CONTENT_PUSH_TOKEN_ENCRYPTION_KEY: randomBytes(32).toString('base64'),
    CONTENT_PUSH_DELIVERY_ENABLED: false,
  } as ServiceConfiguration;
  app = await createApplication(configuration, {
    readinessProbe: { check: async () => undefined },
    pushDeviceRepository: repository,
    pushProvider: { send: vi.fn() },
  });
  await app.init();
});

afterEach(async () => app.close());

async function token(subject = OWNER): Promise<string> {
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

describe('push device HTTP boundary', () => {
  it('requires authentication and binds registration to the JWT owner', async () => {
    await request(app.getHttpServer())
      .put(`/api/v1/push-devices/${INSTALLATION}`)
      .send({})
      .expect(401);

    const response = await request(app.getHttpServer())
      .put(`/api/v1/push-devices/${INSTALLATION}`)
      .set('Authorization', `Bearer ${await token()}`)
      .send({
        platform: 'ANDROID',
        provider: 'EXPO',
        providerToken: 'ExponentPushToken[test-device-token]',
        permissionStatus: 'GRANTED',
      })
      .expect(200);

    expect(response.body).not.toHaveProperty('providerToken');
    expect(repository.register).toHaveBeenCalledWith(
      OWNER,
      expect.objectContaining({ installationId: INSTALLATION }),
      expect.objectContaining({ ciphertext: expect.any(String) }),
    );
  });

  it('rejects malformed tokens and revokes only through the authenticated owner', async () => {
    const authorization = `Bearer ${await token()}`;
    await request(app.getHttpServer())
      .put(`/api/v1/push-devices/${INSTALLATION}`)
      .set('Authorization', authorization)
      .send({
        platform: 'ANDROID',
        provider: 'EXPO',
        providerToken: 'not-a-provider-token',
        permissionStatus: 'GRANTED',
      })
      .expect(422);
    await request(app.getHttpServer())
      .delete(`/api/v1/push-devices/${INSTALLATION}`)
      .set('Authorization', authorization)
      .expect(204);
    expect(repository.revoke).toHaveBeenCalledWith(OWNER, INSTALLATION);
  });
});
