import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { Pool } from 'pg';
import { GenericContainer, Wait, type StartedTestContainer } from 'testcontainers';

import { DatabaseService } from '../../database/database.service.js';
import {
  ResourceIdempotencyConflictError,
  ResourceRepository,
} from '../../resources/resource.repository.js';
import type { ServiceConfiguration } from '../../configuration/configuration.js';

const ADMIN_A = 'a0000000-0000-4000-8000-000000000001';
const ADMIN_B = 'a0000000-0000-4000-8000-000000000002';
const CORRELATION_A = 'c0000000-0000-4000-8000-000000000001';
const CORRELATION_B = 'c0000000-0000-4000-8000-000000000002';
const migrationDirectory = fileURLToPath(new URL('../../../migrations', import.meta.url));

const baseCreate = {
  category: 'ARTICLE' as const,
  locale: 'vi-VN',
  title: 'Reviewed retry behavior',
  summary: 'Concurrency test resource',
  contentBody: 'Body',
  externalUrl: null,
};

describe('ResourceRepository command consistency', () => {
  let container: StartedTestContainer;
  let migrationPool: Pool;
  let database: DatabaseService;
  let repository: ResourceRepository;

  beforeAll(async () => {
    container = await new GenericContainer('postgres:16-alpine')
      .withEnvironment({
        POSTGRES_USER: 'test_user',
        POSTGRES_PASSWORD: 'test_password',
        POSTGRES_DB: 'test_db',
      })
      .withExposedPorts(5432)
      .withWaitStrategy(Wait.forLogMessage('database system is ready to accept connections', 2))
      .start();

    const connectionString = `postgres://test_user:test_password@${container.getHost()}:${String(container.getMappedPort(5432))}/test_db`;
    migrationPool = new Pool({ connectionString });
    for (const name of [
      '1_initial_schema.sql',
      '2_remove_hotline_catalogue.sql',
      '3_add_review_provenance_fields.sql',
      '4_add_idempotency_key.sql',
      '5_add_resource_command_records.sql',
    ]) {
      await migrationPool.query(await readFile(join(migrationDirectory, name), 'utf8'));
    }

    const configuration: ServiceConfiguration = {
      NODE_ENV: 'test',
      PORT: 3003,
      DATABASE_URL: connectionString,
      DB_POOL_MAX: 10,
      DB_IDLE_TIMEOUT_MS: 1_000,
      DB_CONNECT_TIMEOUT_MS: 10_000,
      LOG_LEVEL: 'silent',
      CORS_ORIGINS: '',
      SERVICE_NAME: 'content-notification-service',
      ALLOWED_ORIGINS: [],
      IDENTITY_JWT_ISSUER: 'test',
      IDENTITY_JWT_AUDIENCE: 'test',
      IDENTITY_JWT_PUBLIC_KEY: 'test',
      IDENTITY_JWT_CLOCK_TOLERANCE_SECONDS: 0,
    };
    database = new DatabaseService(configuration);
    repository = new ResourceRepository(database);
  }, 120_000);

  afterAll(async () => {
    await database?.onModuleDestroy();
    await migrationPool?.end();
    await container?.stop();
  });

  it('atomically replays simultaneous identical create retries', async () => {
    const requests = Array.from({ length: 8 }, () =>
      repository.create(baseCreate, 'same-logical-create', {
        actorId: ADMIN_A,
        correlationId: CORRELATION_A,
      }),
    );
    const resources = await Promise.all(requests);

    expect(new Set(resources.map(({ id }) => id)).size).toBe(1);
    const resourceId = resources[0].id;
    const counts = await migrationPool.query<{
      resources: string;
      audits: string;
      records: string;
    }>(
      `SELECT
        (SELECT count(*) FROM resource WHERE id = $1) AS resources,
        (SELECT count(*) FROM resource_audit_event WHERE resource_id = $1) AS audits,
        (SELECT count(*) FROM resource_idempotency_record WHERE resource_id = $1) AS records`,
      [resourceId],
    );
    expect(counts.rows[0]).toEqual({ resources: '1', audits: '1', records: '1' });
  });

  it('rejects changed payload reuse and scopes keys by actor', async () => {
    await repository.create(baseCreate, 'payload-key', {
      actorId: ADMIN_A,
      correlationId: CORRELATION_A,
    });
    await expect(
      repository.create({ ...baseCreate, title: 'Changed' }, 'payload-key', {
        actorId: ADMIN_A,
        correlationId: CORRELATION_A,
      }),
    ).rejects.toBeInstanceOf(ResourceIdempotencyConflictError);

    const otherActor = await repository.create({ ...baseCreate, title: 'Changed' }, 'payload-key', {
      actorId: ADMIN_B,
      correlationId: CORRELATION_B,
    });
    expect(otherActor.title).toBe('Changed');
  });

  it('prevents stale update and stale delete from winning', async () => {
    const created = await repository.create(baseCreate, 'optimistic-create', {
      actorId: ADMIN_A,
      correlationId: CORRELATION_A,
    });
    const updated = await repository.update(
      created.id,
      { title: 'Latest title', version: 0 },
      { actorId: ADMIN_A, correlationId: CORRELATION_A },
    );
    expect(updated?.version).toBe(1);

    await expect(
      repository.update(
        created.id,
        { title: 'Stale title', version: 0 },
        { actorId: ADMIN_B, correlationId: CORRELATION_B },
      ),
    ).resolves.toBeNull();
    await expect(
      repository.delete(created.id, 0, {
        actorId: ADMIN_B,
        correlationId: CORRELATION_B,
      }),
    ).resolves.toBe(false);
    expect((await repository.findById(created.id))?.title).toBe('Latest title');
  });

  it('writes minimized audit facts for mutations and blocked publish outcomes', async () => {
    const deletedDraft = await repository.create(baseCreate, 'delete-audit', {
      actorId: ADMIN_A,
      correlationId: CORRELATION_A,
    });
    await repository.delete(deletedDraft.id, 0, {
      actorId: ADMIN_A,
      correlationId: CORRELATION_A,
    });
    await expect(
      repository.create(baseCreate, 'delete-audit', {
        actorId: ADMIN_A,
        correlationId: CORRELATION_A,
      }),
    ).rejects.toBeInstanceOf(ResourceIdempotencyConflictError);

    const published = await repository.create(baseCreate, 'archive-audit', {
      actorId: ADMIN_A,
      correlationId: CORRELATION_A,
    });
    await migrationPool.query(
      `UPDATE resource
       SET status = 'PUBLISHED', reviewed_by = $2, reviewed_at = now(), version = 1
       WHERE id = $1`,
      [published.id, ADMIN_B],
    );
    await repository.archive(published.id, 1, {
      actorId: ADMIN_A,
      correlationId: CORRELATION_A,
    });
    await repository.auditPublishBlocked(published.id, 2, {
      actorId: ADMIN_A,
      correlationId: CORRELATION_A,
    });

    const result = await migrationPool.query<{ action: string; payload: string }>(
      `SELECT action, row_to_json(resource_audit_event)::text AS payload
       FROM resource_audit_event
       WHERE resource_id IN ($1, $2)
       ORDER BY occurred_at`,
      [deletedDraft.id, published.id],
    );
    expect(result.rows.map(({ action }) => action)).toEqual([
      'RESOURCE_CREATED',
      'RESOURCE_DELETED',
      'RESOURCE_CREATED',
      'RESOURCE_ARCHIVED',
      'RESOURCE_PUBLISH_BLOCKED',
    ]);
    expect(result.rows.every(({ payload }) => !/title|summary|content|url/i.test(payload))).toBe(
      true,
    );
  });

  it('enforces review, locale, effective, expiry, and archive predicates for public detail', async () => {
    const active = await repository.create(baseCreate, 'active-public', {
      actorId: ADMIN_A,
      correlationId: CORRELATION_A,
    });
    const future = await repository.create(baseCreate, 'future-public', {
      actorId: ADMIN_A,
      correlationId: CORRELATION_A,
    });
    await migrationPool.query(
      `UPDATE resource
       SET status = 'PUBLISHED', reviewed_by = $3, reviewed_at = now(),
           effective_at = CASE WHEN id = $2 THEN now() + interval '1 day' ELSE NULL END
       WHERE id IN ($1, $2)`,
      [active.id, future.id, ADMIN_B],
    );

    await expect(repository.findPublishedEligibleById(active.id, 'vi-VN')).resolves.not.toBeNull();
    await expect(repository.findPublishedEligibleById(active.id, 'en-US')).resolves.toBeNull();
    await expect(repository.findPublishedEligibleById(future.id, 'vi-VN')).resolves.toBeNull();
    await repository.archive(active.id, 0, {
      actorId: ADMIN_A,
      correlationId: CORRELATION_A,
    });
    await expect(repository.findPublishedEligibleById(active.id, 'vi-VN')).resolves.toBeNull();
  });
});
