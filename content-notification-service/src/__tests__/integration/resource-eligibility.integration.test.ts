import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { Pool } from 'pg';
import { GenericContainer, Wait, type StartedTestContainer } from 'testcontainers';

import type { ServiceConfiguration } from '../../configuration/configuration.js';
import { DatabaseService } from '../../database/database.service.js';
import {
  EligibilityCommandConflictError,
  ResourceEligibilityRepository,
} from '../../resources/resource-eligibility.repository.js';

const ADMIN = 'a0000000-0000-4000-8000-000000000001';
const CORRELATION = 'c0000000-0000-4000-8000-000000000001';
const RESOURCE = '10000000-0000-4000-8000-000000000001';
const ACTIVE_RESOURCE = '10000000-0000-4000-8000-000000000002';
const CONCURRENT_RESOURCE = '10000000-0000-4000-8000-000000000003';
const DRAFT_RESOURCE = '10000000-0000-4000-8000-000000000004';
const NO_PUBLICATION_RESOURCE = '10000000-0000-4000-8000-000000000005';
const ARCHIVED_RESOURCE = '10000000-0000-4000-8000-000000000006';
const UNPUBLISHED_RESOURCE = '10000000-0000-4000-8000-000000000007';
const FUTURE_RESOURCE = '10000000-0000-4000-8000-000000000008';
const ENDED_RESOURCE = '10000000-0000-4000-8000-000000000009';
const BOUNDED_RESOURCE = '10000000-0000-4000-8000-000000000010';
const migrationDirectory = fileURLToPath(new URL('../../../migrations', import.meta.url));

describe('ResourceEligibilityRepository integration', () => {
  let container: StartedTestContainer;
  let migrationPool: Pool;
  let database: DatabaseService;
  let repository: ResourceEligibilityRepository;

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
      '6_add_resource_eligibility_v1.sql',
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
    repository = new ResourceEligibilityRepository(database);
    await migrationPool.query(
      `INSERT INTO resource
        (id, category, locale, title, summary, content_body, status, reviewed_by, reviewed_at,
         effective_at, expires_at, version)
       VALUES
         ($1, 'BREATHING', 'vi-VN', 'Anxiety breathing', 'Reviewed fixture', 'Body',
          'PUBLISHED', $5, now(), '2026-01-01T00:00:00Z', NULL, 3),
         ($2, 'BREATHING', 'vi-VN', 'Active anxiety breathing', 'Reviewed fixture', 'Body',
          'PUBLISHED', $5, now(), '2026-01-01T00:00:00Z', NULL, 3),
         ($3, 'BREATHING', 'vi-VN', 'Concurrent anxiety breathing', 'Reviewed fixture', 'Body',
          'PUBLISHED', $5, now(), '2026-01-01T00:00:00Z', NULL, 3),
         ($4, 'BREATHING', 'vi-VN', 'Draft anxiety breathing', 'Reviewed fixture', 'Body',
          'DRAFT', NULL, NULL, '2026-01-01T00:00:00Z', NULL, 3),
         ($6, 'BREATHING', 'vi-VN', 'No eligibility publication', 'Reviewed fixture', 'Body',
          'PUBLISHED', $5, now(), '2026-01-01T00:00:00Z', NULL, 3),
         ($7, 'BREATHING', 'vi-VN', 'Archived eligibility resource', 'Reviewed fixture', 'Body',
          'PUBLISHED', $5, now(), '2026-01-01T00:00:00Z', NULL, 3),
         ($8, 'BREATHING', 'vi-VN', 'Unpublished eligibility resource', 'Reviewed fixture', 'Body',
          'PUBLISHED', $5, now(), '2026-01-01T00:00:00Z', NULL, 3),
         ($9, 'BREATHING', 'vi-VN', 'Future eligibility resource', 'Reviewed fixture', 'Body',
          'PUBLISHED', $5, now(), '2026-01-01T00:00:00Z', NULL, 3),
         ($10, 'BREATHING', 'vi-VN', 'Ended eligibility resource', 'Reviewed fixture', 'Body',
          'PUBLISHED', $5, now(), '2026-01-01T00:00:00Z', NULL, 3),
         ($11, 'BREATHING', 'vi-VN', 'Bounded eligibility resource', 'Reviewed fixture', 'Body',
          'PUBLISHED', $5, now(), '2026-02-01T00:00:00Z', '2026-12-01T00:00:00Z', 3)`,
      [
        RESOURCE,
        ACTIVE_RESOURCE,
        CONCURRENT_RESOURCE,
        DRAFT_RESOURCE,
        ADMIN,
        NO_PUBLICATION_RESOURCE,
        ARCHIVED_RESOURCE,
        UNPUBLISHED_RESOURCE,
        FUTURE_RESOURCE,
        ENDED_RESOURCE,
        BOUNDED_RESOURCE,
      ],
    );
    await repository.publish(
      ACTIVE_RESOURCE,
      '3',
      publicationRequest(),
      'publish-active',
      context(),
    );
  }, 120_000);

  afterAll(async () => {
    await database?.onModuleDestroy();
    await migrationPool?.end();
    await container?.stop();
  });

  it('publishes immutable exact-version eligibility and replays its original outcome', async () => {
    const created = await repository.publish(
      RESOURCE,
      '3',
      publicationRequest(),
      'publish-1',
      context(),
    );
    expect(created).toMatchObject({
      resourceId: RESOURCE,
      contentVersion: '3',
      state: 'PUBLISHED',
    });

    await repository.withdraw(
      RESOURCE,
      '3',
      { policyVersion: 'content-eligibility-v1', reasonCode: 'POLICY_WITHDRAWN' },
      'withdraw-1',
      context(),
    );
    const replay = await repository.publish(
      RESOURCE,
      '3',
      publicationRequest(),
      'publish-1',
      context(),
    );
    expect(replay).toEqual(created);

    await expect(
      repository.publish(
        RESOURCE,
        '3',
        { ...publicationRequest(), locale: 'en-US' },
        'publish-1',
        context(),
      ),
    ).rejects.toBeInstanceOf(EligibilityCommandConflictError);
    await expect(
      migrationPool.query(
        `UPDATE resource_eligibility_publication SET locale = 'en-US' WHERE id = $1`,
        [created.publicationId],
      ),
    ).rejects.toThrow(/append-only/);
  });

  it('serializes concurrent publication and rejects non-published content', async () => {
    const concurrent = await Promise.allSettled([
      repository.publish(
        CONCURRENT_RESOURCE,
        '3',
        publicationRequest(),
        'publish-concurrent-a',
        context(),
      ),
      repository.publish(
        CONCURRENT_RESOURCE,
        '3',
        publicationRequest(),
        'publish-concurrent-b',
        context(),
      ),
    ]);
    expect(concurrent.filter((result) => result.status === 'fulfilled')).toHaveLength(1);
    expect(concurrent.filter((result) => result.status === 'rejected')).toHaveLength(1);
    await expect(
      repository.publish(DRAFT_RESOURCE, '3', publicationRequest(), 'publish-draft', context()),
    ).rejects.toBeInstanceOf(EligibilityCommandConflictError);
  });

  it('resolves eligible, primary-required, stale, not-found, and withdrawn outcomes in request order', async () => {
    const responses = await repository.resolve([
      query('20000000-0000-4000-8000-000000000001', ACTIVE_RESOURCE, '3', 'PRIMARY_OR_ADJUNCT'),
      query('20000000-0000-4000-8000-000000000002', ACTIVE_RESOURCE, '3', 'PRIMARY'),
      query('20000000-0000-4000-8000-000000000003', RESOURCE, '2', 'PRIMARY'),
      query(
        '20000000-0000-4000-8000-000000000004',
        '10000000-0000-4000-8000-000000000099',
        '0',
        'PRIMARY',
      ),
      query('20000000-0000-4000-8000-000000000005', RESOURCE, '3', 'PRIMARY_OR_ADJUNCT'),
      {
        ...query('20000000-0000-4000-8000-000000000006', ACTIVE_RESOURCE, '3', 'PRIMARY'),
        locale: 'en-US',
      },
      {
        ...query(
          '20000000-0000-4000-8000-000000000007',
          ACTIVE_RESOURCE,
          '3',
          'PRIMARY_OR_ADJUNCT',
        ),
        supportTier: 'PROFESSIONAL_SUPPORT_RECOMMENDED' as const,
      },
    ]);

    expect(responses.results.map((result) => result.outcome)).toEqual([
      'ELIGIBLE',
      'INELIGIBLE',
      'STALE',
      'NOT_FOUND',
      'WITHDRAWN',
      'INELIGIBLE',
      'INELIGIBLE',
    ]);
    expect(responses.results[1].reasonCode).toBe('PRIMARY_REQUIRED');
    expect(responses.results[5].reasonCode).toBe('LOCALE_MISMATCH');
    expect(responses.results[6].reasonCode).toBe('DOMAIN_OR_PATHWAY_NOT_ELIGIBLE');
    expect(responses.results.map((result) => result.requestId)).toEqual([
      '20000000-0000-4000-8000-000000000001',
      '20000000-0000-4000-8000-000000000002',
      '20000000-0000-4000-8000-000000000003',
      '20000000-0000-4000-8000-000000000004',
      '20000000-0000-4000-8000-000000000005',
      '20000000-0000-4000-8000-000000000006',
      '20000000-0000-4000-8000-000000000007',
    ]);
  });

  it('resolves every resource lifecycle and effective-window branch fail closed', async () => {
    await repository.publish(
      ARCHIVED_RESOURCE,
      '3',
      publicationRequest(),
      'publish-archived',
      context(),
    );
    await repository.publish(
      UNPUBLISHED_RESOURCE,
      '3',
      publicationRequest(),
      'publish-unpublished',
      context(),
    );
    await repository.publish(
      FUTURE_RESOURCE,
      '3',
      { ...publicationRequest(), effectiveAt: '2099-01-01T00:00:00Z' },
      'publish-future',
      context(),
    );
    await repository.publish(
      ENDED_RESOURCE,
      '3',
      {
        ...publicationRequest(),
        effectiveAt: '2026-01-02T00:00:00Z',
        expiresAt: '2026-02-01T00:00:00Z',
      },
      'publish-ended',
      context(),
    );
    await migrationPool.query(`UPDATE resource SET status = 'ARCHIVED' WHERE id = $1`, [
      ARCHIVED_RESOURCE,
    ]);
    await migrationPool.query(`UPDATE resource SET status = 'DRAFT' WHERE id = $1`, [
      UNPUBLISHED_RESOURCE,
    ]);

    const responses = await repository.resolve([
      query('21000000-0000-4000-8000-000000000001', NO_PUBLICATION_RESOURCE, '3', 'PRIMARY'),
      query('21000000-0000-4000-8000-000000000002', ARCHIVED_RESOURCE, '3', 'PRIMARY'),
      query('21000000-0000-4000-8000-000000000003', UNPUBLISHED_RESOURCE, '3', 'PRIMARY'),
      query('21000000-0000-4000-8000-000000000004', FUTURE_RESOURCE, '3', 'PRIMARY'),
      query('21000000-0000-4000-8000-000000000005', ENDED_RESOURCE, '3', 'PRIMARY'),
    ]);

    expect(responses.results.map((result) => [result.outcome, result.reasonCode])).toEqual([
      ['INELIGIBLE', 'NO_ELIGIBILITY_PUBLICATION'],
      ['WITHDRAWN', 'RESOURCE_ARCHIVED'],
      ['INELIGIBLE', 'RESOURCE_NOT_PUBLISHED'],
      ['INELIGIBLE', 'NOT_YET_EFFECTIVE'],
      ['WITHDRAWN', 'EFFECTIVE_WINDOW_ENDED'],
    ]);
  });

  it('rejects eligibility windows outside the exact resource publication window', async () => {
    await expect(
      repository.publish(
        BOUNDED_RESOURCE,
        '3',
        {
          ...publicationRequest(),
          effectiveAt: '2026-01-01T00:00:00Z',
          expiresAt: '2026-11-01T00:00:00Z',
        },
        'publish-before-resource',
        context(),
      ),
    ).rejects.toBeInstanceOf(EligibilityCommandConflictError);
    await expect(
      repository.publish(
        BOUNDED_RESOURCE,
        '3',
        {
          ...publicationRequest(),
          effectiveAt: '2026-02-01T00:00:00Z',
          expiresAt: null,
        },
        'publish-without-bounded-expiry',
        context(),
      ),
    ).rejects.toBeInstanceOf(EligibilityCommandConflictError);
    await expect(
      repository.publish(
        BOUNDED_RESOURCE,
        '3',
        {
          ...publicationRequest(),
          effectiveAt: '2026-02-01T00:00:00Z',
          expiresAt: '2027-01-01T00:00:00Z',
        },
        'publish-after-resource',
        context(),
      ),
    ).rejects.toBeInstanceOf(EligibilityCommandConflictError);
  });

  it('enforces approved domain, instrument, role, and indexed exact-version access', async () => {
    const publicationId = (
      await migrationPool.query<{ id: string }>(
        `SELECT id FROM resource_eligibility_publication WHERE resource_id = $1`,
        [RESOURCE],
      )
    ).rows[0].id;
    await expect(
      migrationPool.query(
        `INSERT INTO resource_eligibility_declaration
          (publication_id, target_domain, eligibility_role, instrument, screening_levels, support_tiers)
         VALUES ($1, 'ANXIETY_SYMPTOMS', 'PRIMARY', 'PHQ_9', ARRAY['MILD'], ARRAY['SELF_GUIDED_SUPPORT'])`,
        [publicationId],
      ),
    ).rejects.toThrow(/ck_resource_eligibility_domain_instrument/);
    await expect(
      migrationPool.query(
        `INSERT INTO resource_eligibility_declaration
          (publication_id, target_domain, eligibility_role, instrument, screening_levels, support_tiers)
         VALUES ($1, 'ANXIETY_SYMPTOMS', 'PRIMARY', 'GAD_7', ARRAY['MILD'], ARRAY['SELF_GUIDED_SUPPORT'])`,
        [publicationId],
      ),
    ).rejects.toThrow(/duplicate key/);
    await expect(
      migrationPool.query(
        `INSERT INTO resource_eligibility_declaration
          (publication_id, target_domain, eligibility_role, instrument, screening_levels, support_tiers)
         VALUES ($1, 'GENERAL_WELLBEING', 'PRIMARY', 'GAD_7', ARRAY['MILD'], ARRAY['SELF_GUIDED_SUPPORT'])`,
        [publicationId],
      ),
    ).rejects.toThrow(/violates check constraint/);
    await expect(
      migrationPool.query(
        `INSERT INTO resource_eligibility_declaration
          (publication_id, target_domain, eligibility_role, instrument, screening_levels, support_tiers)
         VALUES ($1, 'ANXIETY_SYMPTOMS', 'BOTH', 'GAD_7', ARRAY['MILD'], ARRAY['SELF_GUIDED_SUPPORT'])`,
        [publicationId],
      ),
    ).rejects.toThrow(/violates check constraint/);
    const indexes = await migrationPool.query<{ indexname: string }>(
      `SELECT indexname FROM pg_indexes
       WHERE tablename = 'resource_eligibility_publication'
         AND indexname = 'uq_resource_eligibility_exact_version'`,
    );
    expect(indexes.rows).toHaveLength(1);
  });
});

function publicationRequest() {
  return {
    policyVersion: 'content-eligibility-v1' as const,
    locale: 'vi-VN',
    effectiveAt: '2026-01-02T00:00:00Z',
    expiresAt: null,
    declarations: [
      {
        targetDomain: 'ANXIETY_SYMPTOMS' as const,
        role: 'ADJUNCT' as const,
        instrument: 'GAD_7' as const,
        screeningLevels: ['MILD'] as const,
        supportTiers: ['SELF_GUIDED_SUPPORT'] as const,
      },
    ],
  };
}

function query(
  requestId: string,
  resourceId: string,
  contentVersion: string,
  requiredRole: 'PRIMARY' | 'PRIMARY_OR_ADJUNCT',
) {
  return {
    requestId,
    resourceId,
    contentVersion,
    targetDomain: 'ANXIETY_SYMPTOMS' as const,
    requiredRole,
    instrument: 'GAD_7' as const,
    screeningLevel: 'MILD' as const,
    supportTier: 'SELF_GUIDED_SUPPORT' as const,
    locale: 'vi-VN',
  };
}

function context() {
  return { actorId: ADMIN, correlationId: CORRELATION };
}
