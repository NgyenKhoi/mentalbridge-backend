import type { INestApplication } from '@nestjs/common';
import type { Server } from 'node:http';
import request from 'supertest';
import { afterEach, describe, expect, it } from 'vitest';

import { createApplication } from '../application.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import type { ResourceRepository } from '../resources/resource.repository.js';
import type { ResourceRow } from '../resources/resource.types.js';

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
  IDENTITY_JWT_ISSUER: 'https://identity.local.mentalbridge',
  IDENTITY_JWT_AUDIENCE: 'mentalbridge-api',
  IDENTITY_JWT_PUBLIC_KEY:
    '-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0\n-----END PUBLIC KEY-----',
  IDENTITY_JWT_CLOCK_TOLERANCE_SECONDS: 60,
};

const publishedResource: ResourceRow = {
  id: '123e4567-e89b-12d3-a456-426614174000',
  category: 'BREATHING',
  locale: 'vi-VN',
  title: 'Kỹ thuật thở 4-7-8',
  summary: 'Kỹ thuật thở giúp giảm căng thẳng',
  external_url: null,
  status: 'PUBLISHED',
  reviewed_at: new Date('2024-01-15T10:00:00Z'),
  created_at: new Date('2024-01-15T09:00:00Z'),
  updated_at: new Date('2024-01-15T10:00:00Z'),
};

function makeRepository(impl: Partial<ResourceRepository>): ResourceRepository {
  return { listPublished: async () => [], ...impl } as ResourceRepository;
}

let app: INestApplication | undefined;

afterEach(async () => {
  await app?.close();
  app = undefined;
});

describe('GET /api/v1/resources', () => {
  it('returns published resources', async () => {
    app = await createApplication(configuration, {
      readinessProbe: { check: async () => undefined },
      resourceRepository: makeRepository({ listPublished: async () => [publishedResource] }),
    });
    await app.init();

    const response = await request(app.getHttpServer() as Server)
      .get('/api/v1/resources')
      .expect(200);

    expect(response.body.data).toHaveLength(1);
    expect(response.body.data[0].id).toBe(publishedResource.id);
    expect(response.body.data[0].category).toBe('BREATHING');
    expect(response.body.data[0].status).toBe('PUBLISHED');
    expect(response.body.count).toBe(1);
    expect(response.body.data[0]).not.toHaveProperty('hotline');
    expect(response.body.data[0]).not.toHaveProperty('emergencyNumber');
  });

  it('returns empty array when no published resources match', async () => {
    app = await createApplication(configuration, {
      readinessProbe: { check: async () => undefined },
      resourceRepository: makeRepository({ listPublished: async () => [] }),
    });
    await app.init();

    const response = await request(app.getHttpServer() as Server)
      .get('/api/v1/resources')
      .expect(200);

    expect(response.body.data).toEqual([]);
    expect(response.body.count).toBe(0);
    expect(response.body).not.toHaveProperty('guidance');
    expect(response.body).not.toHaveProperty('hotline');
  });

  it('returns neutral fallback when repository is unavailable', async () => {
    app = await createApplication(configuration, {
      readinessProbe: { check: async () => undefined },
      resourceRepository: makeRepository({
        listPublished: async () => {
          throw new Error('DB connection refused');
        },
      }),
    });
    await app.init();

    const response = await request(app.getHttpServer() as Server)
      .get('/api/v1/resources')
      .expect(200);

    expect(response.body.fallback).toBe('unavailable');
    expect(response.body.data).toEqual([]);
    expect(response.body.count).toBe(0);
    expect(typeof response.body.message).toBe('string');
    expect(response.body.message).not.toMatch(/hotline/i);
    expect(response.body.message).not.toMatch(/emergency/i);
    expect(response.body.message).not.toMatch(/guaranteed/i);
  });

  it('returns neutral fallback on timeout', async () => {
    app = await createApplication(configuration, {
      readinessProbe: { check: async () => undefined },
      resourceRepository: makeRepository({
        listPublished: async () => {
          throw Object.assign(new Error('timeout'), { code: 'ETIMEDOUT' });
        },
      }),
    });
    await app.init();

    const response = await request(app.getHttpServer() as Server)
      .get('/api/v1/resources')
      .expect(200);

    expect(response.body.fallback).toBe('unavailable');
  });

  it('drops malformed rows and returns only valid resources', async () => {
    const malformed = {
      id: null,
      category: 'HOTLINE',
      locale: 'vi-VN',
      title: null,
      summary: null,
      external_url: null,
      status: 'PUBLISHED',
      reviewed_at: null,
      created_at: new Date(),
      updated_at: new Date(),
    } as unknown as ResourceRow;

    app = await createApplication(configuration, {
      readinessProbe: { check: async () => undefined },
      resourceRepository: makeRepository({
        listPublished: async () => [malformed, publishedResource],
      }),
    });
    await app.init();

    const response = await request(app.getHttpServer() as Server)
      .get('/api/v1/resources')
      .expect(200);

    expect(response.body.data).toHaveLength(1);
    expect(response.body.data[0].id).toBe(publishedResource.id);
  });

  it('paginates and returns nextCursor when more results exist', async () => {
    const rows: ResourceRow[] = Array.from({ length: 3 }, (_, i) => ({
      ...publishedResource,
      id: `id-${String(i)}`,
      created_at: new Date(Date.now() - i * 1000),
    }));

    app = await createApplication(configuration, {
      readinessProbe: { check: async () => undefined },
      resourceRepository: makeRepository({ listPublished: async () => rows }),
    });
    await app.init();

    const response = await request(app.getHttpServer() as Server)
      .get('/api/v1/resources?limit=2')
      .expect(200);

    expect(response.body.data).toHaveLength(2);
    expect(response.body.nextCursor).toBeDefined();
  });

  it('does not return nextCursor when results fit within limit', async () => {
    app = await createApplication(configuration, {
      readinessProbe: { check: async () => undefined },
      resourceRepository: makeRepository({ listPublished: async () => [publishedResource] }),
    });
    await app.init();

    const response = await request(app.getHttpServer() as Server)
      .get('/api/v1/resources?limit=10')
      .expect(200);

    expect(response.body).not.toHaveProperty('nextCursor');
  });

  it('rejects invalid category', async () => {
    app = await createApplication(configuration, {
      readinessProbe: { check: async () => undefined },
      resourceRepository: makeRepository({ listPublished: async () => [] }),
    });
    await app.init();

    await request(app.getHttpServer() as Server)
      .get('/api/v1/resources?category=HOTLINE')
      .expect(400);
  });

  it('rejects invalid limit', async () => {
    app = await createApplication(configuration, {
      readinessProbe: { check: async () => undefined },
      resourceRepository: makeRepository({ listPublished: async () => [] }),
    });
    await app.init();

    await request(app.getHttpServer() as Server)
      .get('/api/v1/resources?limit=abc')
      .expect(400);
  });

  it('rejects limit with trailing non-digit characters', async () => {
    app = await createApplication(configuration, {
      readinessProbe: { check: async () => undefined },
      resourceRepository: makeRepository({ listPublished: async () => [] }),
    });
    await app.init();

    await request(app.getHttpServer() as Server)
      .get('/api/v1/resources?limit=10abc')
      .expect(400);
  });

  it('rejects fractional limit', async () => {
    app = await createApplication(configuration, {
      readinessProbe: { check: async () => undefined },
      resourceRepository: makeRepository({ listPublished: async () => [] }),
    });
    await app.init();

    await request(app.getHttpServer() as Server)
      .get('/api/v1/resources?limit=1.5')
      .expect(400);
  });

  it('rejects invalid cursor', async () => {
    app = await createApplication(configuration, {
      readinessProbe: { check: async () => undefined },
      resourceRepository: makeRepository({ listPublished: async () => [] }),
    });
    await app.init();

    await request(app.getHttpServer() as Server)
      .get('/api/v1/resources?cursor=not-a-uuid')
      .expect(400);
  });

  it('forwards locale and category to the repository', async () => {
    let capturedQuery: unknown;
    app = await createApplication(configuration, {
      readinessProbe: { check: async () => undefined },
      resourceRepository: makeRepository({
        listPublished: async (q) => {
          capturedQuery = q;
          return [];
        },
      }),
    });
    await app.init();

    await request(app.getHttpServer() as Server)
      .get('/api/v1/resources?locale=vi-VN&category=MEDITATION&limit=10')
      .expect(200);

    expect(capturedQuery).toMatchObject({ locale: 'vi-VN', category: 'MEDITATION', limit: 10 });
  });

  it('echoes the x-correlation-id header', async () => {
    app = await createApplication(configuration, {
      readinessProbe: { check: async () => undefined },
      resourceRepository: makeRepository({ listPublished: async () => [] }),
    });
    await app.init();

    await request(app.getHttpServer() as Server)
      .get('/api/v1/resources')
      .set('x-correlation-id', 'test-corr-id')
      .expect('x-correlation-id', 'test-corr-id')
      .expect(200);
  });

  it('does not return DRAFT resources in public endpoint', async () => {
    const draftResource: ResourceRow = {
      ...publishedResource,
      id: '223e4567-e89b-12d3-a456-426614174000',
      status: 'DRAFT',
      reviewed_at: null,
    };

    app = await createApplication(configuration, {
      readinessProbe: { check: async () => undefined },
      resourceRepository: makeRepository({
        listPublished: async () => [publishedResource],
      }),
    });
    await app.init();

    const response = await request(app.getHttpServer() as Server)
      .get('/api/v1/resources')
      .expect(200);

    expect(response.body.data).toHaveLength(1);
    expect(response.body.data[0].status).toBe('PUBLISHED');
    expect(response.body.data.every((r: { status: string }) => r.status !== 'DRAFT')).toBe(true);
  });

  it('does not return ARCHIVED resources in public endpoint', async () => {
    const archivedResource: ResourceRow = {
      ...publishedResource,
      id: '323e4567-e89b-12d3-a456-426614174000',
      status: 'ARCHIVED',
    };

    app = await createApplication(configuration, {
      readinessProbe: { check: async () => undefined },
      resourceRepository: makeRepository({
        listPublished: async () => [publishedResource],
      }),
    });
    await app.init();

    const response = await request(app.getHttpServer() as Server)
      .get('/api/v1/resources')
      .expect(200);

    expect(response.body.data).toHaveLength(1);
    expect(response.body.data[0].status).toBe('PUBLISHED');
    expect(response.body.data.every((r: { status: string }) => r.status !== 'ARCHIVED')).toBe(
      true,
    );
  });
});
