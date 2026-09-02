import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { Pool } from 'pg';
import { GenericContainer, Wait, type StartedTestContainer } from 'testcontainers';
import { readFileSync } from 'fs';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';

import { ResourceRepository } from '../../resources/resource.repository.js';
import type { DatabaseService } from '../../database/database.service.js';

const __dirname = dirname(fileURLToPath(import.meta.url));

describe('ResourceRepository integration', () => {
  let container: StartedTestContainer;
  let pool: Pool;
  let repository: ResourceRepository;

  beforeAll(async () => {
    container = await new GenericContainer('postgres:16-alpine')
      .withEnvironment({
        POSTGRES_USER: 'test_user',
        POSTGRES_PASSWORD: 'test_password',
        POSTGRES_DB: 'test_db',
      })
      .withExposedPorts(5432)
      .withWaitStrategy(Wait.forLogMessage('database system is ready to accept connections'))
      .start();

    pool = new Pool({
      host: container.getHost(),
      port: container.getMappedPort(5432),
      user: 'test_user',
      password: 'test_password',
      database: 'test_db',
    });

    for (const migration of ['1_initial_schema.sql', '2_remove_hotline_catalogue.sql']) {
      const sql = readFileSync(join(__dirname, '../../../migrations', migration), 'utf8');
      await pool.query(sql);
    }

    const dbService = { query: pool.query.bind(pool) } as unknown as DatabaseService;
    repository = new ResourceRepository(dbService);
  }, 120_000);

  afterAll(async () => {
    if (pool) await pool.end();
    if (container) await container.stop();
  });

  async function insertResource(overrides: Record<string, unknown> = {}): Promise<string> {
    const defaults: Record<string, unknown> = {
      category: 'BREATHING',
      locale: 'vi-VN',
      title: 'Test resource',
      summary: 'Test summary',
      content_body: 'Test body',
      status: 'PUBLISHED',
      reviewed_by: 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
      reviewed_at: new Date().toISOString(),
      ...overrides,
    };
    const entries = Object.entries(defaults).filter(([, v]) => v !== null && v !== undefined);
    const keys = entries.map(([k]) => k);
    const values = entries.map(([, v]) => v);
    const placeholders = keys.map((_, i) => '$' + String(i + 1)).join(', ');
    const { rows } = await pool.query<{ id: string }>(
      `INSERT INTO resource (${keys.join(', ')}) VALUES (${placeholders}) RETURNING id`,
      values,
    );
    return rows[0].id;
  }

  it('returns published reviewed active resources from the real database', async () => {
    await insertResource();

    const rows = await repository.listPublished({ limit: 10 });

    expect(rows.length).toBeGreaterThanOrEqual(1);
    expect(rows[0].status).toBe('PUBLISHED');
    expect(rows[0].reviewed_at).not.toBeNull();
  });

  it('does not return unreviewed resources', async () => {
    await insertResource({ reviewed_by: undefined, reviewed_at: undefined });

    const rows = await repository.listPublished({ limit: 100 });
    expect(rows.every((r) => r.reviewed_at !== null)).toBe(true);
  });

  it('does not return future-effective resources', async () => {
    const futureDate = new Date(Date.now() + 60 * 60 * 1000).toISOString();
    await insertResource({ effective_at: futureDate });

    const rows = await repository.listPublished({ limit: 100 });
    expect(rows).toEqual([]);
  });

  it('does not return expired resources', async () => {
    await insertResource({
      effective_at: new Date(Date.now() - 2 * 60 * 60 * 1000).toISOString(),
      expires_at: new Date(Date.now() - 60 * 60 * 1000).toISOString(),
    });

    const rows = await repository.listPublished({ limit: 100 });
    expect(rows).toEqual([]);
  });

  it('filters by locale', async () => {
    await insertResource({ locale: 'en-US', title: 'English resource' });

    const rows = await repository.listPublished({ limit: 10, locale: 'en-US' });

    expect(rows.length).toBeGreaterThanOrEqual(1);
    expect(rows.every((r) => r.locale === 'en-US')).toBe(true);
  });

  it('filters by category', async () => {
    await insertResource({ category: 'MEDITATION', title: 'Meditation resource' });

    const rows = await repository.listPublished({ limit: 10, category: 'MEDITATION' });

    expect(rows.length).toBeGreaterThanOrEqual(1);
    expect(rows.every((r) => r.category === 'MEDITATION')).toBe(true);
  });

  it('returns empty array when no published resources match', async () => {
    const rows = await repository.listPublished({
      limit: 10,
      category: 'COMMUNITY',
      locale: 'ja-JP',
    });
    expect(rows).toEqual([]);
  });
});
