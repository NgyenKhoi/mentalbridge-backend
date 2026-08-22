import type { INestApplication } from '@nestjs/common';
import type { Server } from 'node:http';
import request from 'supertest';
import { afterEach, describe, expect, it } from 'vitest';

import { createApplication } from '../application.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';

const configuration: ServiceConfiguration = {
  NODE_ENV: 'test',
  PORT: 3003,
  DB_HOST: 'localhost',
  DB_PORT: 5432,
  DB_NAME: 'test_db',
  DB_USER: 'test_user',
  DB_PASSWORD: 'test_password',
  DB_POOL_MAX: 2,
  DB_IDLE_TIMEOUT_MS: 100,
  DB_CONNECT_TIMEOUT_MS: 100,
  LOG_LEVEL: 'silent',
  CORS_ORIGINS: '',
  SERVICE_NAME: 'content-notification-service',
  ALLOWED_ORIGINS: [],
};

let app: INestApplication | undefined;

afterEach(async () => {
  await app?.close();
  app = undefined;
});

describe('health endpoints', () => {
  it('returns liveness without checking PostgreSQL', async () => {
    let checks = 0;
    app = await createApplication(configuration, {
      readinessProbe: { check: async () => void checks++ },
    });
    await app.init();

    await request(app.getHttpServer() as Server)
      .get('/health/live')
      .expect(200)
      .expect({ status: 'ok' });
    expect(checks).toBe(0);
  });

  it('returns readiness when PostgreSQL responds', async () => {
    app = await createApplication(configuration, {
      readinessProbe: { check: async () => undefined },
    });
    await app.init();

    await request(app.getHttpServer() as Server)
      .get('/health/ready')
      .set('x-correlation-id', 'test-correlation')
      .expect('x-correlation-id', 'test-correlation')
      .expect(200)
      .expect({ status: 'ok', db: 'connected' });
  });

  it('returns safe problem details when PostgreSQL is unavailable', async () => {
    app = await createApplication(configuration, {
      readinessProbe: { check: async () => Promise.reject(new Error('offline')) },
    });
    await app.init();

    await request(app.getHttpServer() as Server)
      .get('/health/ready')
      .expect('Content-Type', /application\/problem\+json/)
      .expect(503)
      .expect((response) => {
        expect(response.body.code).toBe('DEPENDENCY_UNAVAILABLE');
        expect(response.body.correlationId).toEqual(expect.any(String));
      });
  });

  it('returns RFC 9457-style not found responses', async () => {
    app = await createApplication(configuration, {
      readinessProbe: { check: async () => undefined },
    });
    await app.init();

    await request(app.getHttpServer() as Server)
      .get('/not-found')
      .expect('Content-Type', /application\/problem\+json/)
      .expect(404)
      .expect((response) => {
        expect(response.body.code).toBe('RESOURCE_NOT_FOUND');
      });
  });
});
