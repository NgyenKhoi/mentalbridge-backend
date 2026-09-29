import type { Server } from 'node:http';

import { exportSPKI, generateKeyPair } from 'jose';
import request from 'supertest';
import { beforeAll, describe, expect, it } from 'vitest';

import { createApplication } from '../../src/application.js';
import type { MongoDatabaseService } from '../../src/database/mongo-database.service.js';
import type { RedisService } from '../../src/database/redis.service.js';
import { testConfiguration } from '../fixtures/test-configuration.js';
import { issueToken } from '../fixtures/token.js';

const keys = await generateKeyPair('RS256', { extractable: true });
const publicKey = await exportSPKI(keys.publicKey);
const configuration = testConfiguration(publicKey);

const mongo = {
  ping: () => Promise.resolve(),
  collection: () => {
    throw new Error('Not used by this test');
  },
  onApplicationShutdown: () => Promise.resolve(),
} as unknown as MongoDatabaseService;

const redis = {
  ping: () => Promise.resolve(true),
  onApplicationShutdown: () => Promise.resolve(),
} as unknown as RedisService;

describe('Realtime HTTP boundary', () => {
  let token: string;

  beforeAll(async () => {
    token = await issueToken(keys.privateKey, configuration);
  });

  it('exposes liveness without dependency access', async () => {
    const app = await createApplication(configuration, { mongo, redis });
    await app.init();
    try {
      await request(app.getHttpServer() as Server)
        .get('/health/live')
        .expect(200)
        .expect({ status: 'ok', service: 'realtime-service' });
    } finally {
      await app.close();
    }
  });

  it('applies the HTTP security header baseline', async () => {
    const app = await createApplication(configuration, { mongo, redis });
    await app.init();
    try {
      await request(app.getHttpServer() as Server)
        .get('/health/live')
        .expect('x-content-type-options', 'nosniff')
        .expect('x-frame-options', 'SAMEORIGIN')
        .expect('content-security-policy', /default-src/)
        .expect(200);
    } finally {
      await app.close();
    }
  });

  it('reports connected dependencies and correlation ID', async () => {
    const app = await createApplication(configuration, { mongo, redis });
    await app.init();
    try {
      await request(app.getHttpServer() as Server)
        .get('/health/ready')
        .set('x-correlation-id', 'health-test')
        .expect('x-correlation-id', 'health-test')
        .expect(200)
        .expect({
          status: 'ok',
          service: 'realtime-service',
          mongodb: 'connected',
          redis: 'connected',
        });
    } finally {
      await app.close();
    }
  });

  it('keeps durable readiness while Redis is degraded', async () => {
    const degradedRedis = {
      ping: () => Promise.resolve(false),
      onApplicationShutdown: () => Promise.resolve(),
    } as unknown as RedisService;
    const app = await createApplication(configuration, { mongo, redis: degradedRedis });
    await app.init();
    try {
      await request(app.getHttpServer() as Server)
        .get('/health/ready')
        .expect(200)
        .expect((response) => {
          const body = response.body as { status?: unknown };
          expect(body.status).toBe('degraded');
        });
    } finally {
      await app.close();
    }
  });

  it('fails readiness safely when MongoDB is unavailable', async () => {
    const failedMongo = {
      ping: () => Promise.reject(new Error('offline')),
      collection: () => {
        throw new Error('Not used by this test');
      },
      onApplicationShutdown: () => Promise.resolve(),
    } as unknown as MongoDatabaseService;
    const app = await createApplication(configuration, { mongo: failedMongo, redis });
    await app.init();
    try {
      await request(app.getHttpServer() as Server)
        .get('/health/ready')
        .expect('Content-Type', /application\/problem\+json/)
        .expect(503)
        .expect((response) => {
          const body = response.body as { code?: unknown };
          expect(body.code).toBe('DEPENDENCY_UNAVAILABLE');
        });
    } finally {
      await app.close();
    }
  });

  it('fails history closed without Consultation eligibility', async () => {
    const app = await createApplication(configuration, { mongo, redis });
    await app.init();
    try {
      await request(app.getHttpServer() as Server)
        .get('/api/v1/conversations/22222222-2222-4222-8222-222222222222/messages')
        .set('authorization', `Bearer ${token}`)
        .expect(503)
        .expect((response) => {
          const body = response.body as { code?: unknown };
          expect(body.code).toBe('CHAT_ELIGIBILITY_UNAVAILABLE');
        });
    } finally {
      await app.close();
    }
  });

  it('rejects history without a bearer token', async () => {
    const app = await createApplication(configuration, { mongo, redis });
    await app.init();
    try {
      await request(app.getHttpServer() as Server)
        .get('/api/v1/conversations/22222222-2222-4222-8222-222222222222/messages')
        .expect(401)
        .expect((response) => {
          const body = response.body as { code?: unknown };
          expect(body.code).toBe('AUTHENTICATION_REQUIRED');
        });
    } finally {
      await app.close();
    }
  });

  it('issues a bounded one-use socket credential for a chat participant', async () => {
    const credentialRedis = {
      ping: () => Promise.resolve(true),
      execute: (callback: (client: { set: () => Promise<string> }) => Promise<unknown>) =>
        callback({ set: () => Promise.resolve('OK') }),
      onApplicationShutdown: () => Promise.resolve(),
    } as unknown as RedisService;
    const app = await createApplication(configuration, { mongo, redis: credentialRedis });
    await app.init();
    try {
      await request(app.getHttpServer() as Server)
        .post('/internal/v1/socket-credentials')
        .set('authorization', `Bearer ${token}`)
        .expect(201)
        .expect((response) => {
          const body = response.body as { accessToken?: unknown; expiresAt?: unknown };
          expect(body.accessToken).toEqual(expect.stringMatching(/^[A-Za-z0-9_-]{43}$/));
          expect(body.expiresAt).toEqual(expect.any(String));
        });
    } finally {
      await app.close();
    }
  });

  it('rejects ADMIN socket credential exchange', async () => {
    const adminToken = await issueToken(keys.privateKey, configuration, undefined, {
      role: 'ADMIN',
      tokenId: 'admin-token',
    });
    const app = await createApplication(configuration, { mongo, redis });
    await app.init();
    try {
      await request(app.getHttpServer() as Server)
        .post('/internal/v1/socket-credentials')
        .set('authorization', `Bearer ${adminToken}`)
        .expect(403)
        .expect((response) => {
          const body = response.body as { code?: unknown };
          expect(body.code).toBe('ACCESS_DENIED');
        });
    } finally {
      await app.close();
    }
  });

  it('returns dependency unavailable when a socket credential cannot be stored', async () => {
    const failedRedis = {
      ping: () => Promise.resolve(false),
      execute: () => Promise.reject(new Error('offline')),
      onApplicationShutdown: () => Promise.resolve(),
    } as unknown as RedisService;
    const app = await createApplication(configuration, { mongo, redis: failedRedis });
    await app.init();
    try {
      await request(app.getHttpServer() as Server)
        .post('/internal/v1/socket-credentials')
        .set('authorization', `Bearer ${token}`)
        .expect(503)
        .expect((response) => {
          const body = response.body as { code?: unknown };
          expect(body.code).toBe('DEPENDENCY_UNAVAILABLE');
        });
    } finally {
      await app.close();
    }
  });

  it('exposes Prometheus metrics', async () => {
    const app = await createApplication(configuration, { mongo, redis });
    await app.init();
    try {
      await request(app.getHttpServer() as Server)
        .get('/metrics')
        .expect(200)
        .expect((response) => {
          expect(response.text).toContain('realtime_active_sockets');
        });
    } finally {
      await app.close();
    }
  });
});
