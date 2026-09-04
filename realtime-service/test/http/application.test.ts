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
