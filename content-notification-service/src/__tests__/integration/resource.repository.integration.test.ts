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
    const defaults = {
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
    const keys = Object.keys(defaults);
    const values = Object.values(defaults);
    const placeholders = keys.map((_, i) => `$${i + 1}`).join(', ');
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
    await insertResource({ reviewed_by: null, reviewed_at: null, status: 'PUBLISHED' });

    const before = await repository.listPublished({ limit: 100 });
    const allReviewed = before.every((r) => r.reviewed_at !== null);
    expect(allReviewed).toBe(true);
  });

  it('does not return future-effective resources', async () => {
    const futureDate = new Date(Date.now() + 60 * 60 * 1000).toISOString();
    await insertResource({ effective_at: futureDate });

    const rows = await repository.listPublished({ limit: 100 });
    const allActive = rows.every(
      (r) =>
        (r as unknown as { effective_at: Date | null }).effective_at == null ||
        new Date((r as unknown as { effective_at: Date }).effective_at) <= new Date(),
    );
    expect(allActive).toBe(true);
  });

  it('does not return expired resources', async () => {
    const pastDate = new Date(Date.now() - 60 * 60 * 1000).toISOString();
    await insertResource({
      effective_at: new Date(Date.now() - 2 * 60 * 60 * 1000).toISOString(),
      expires_at: pastDate,
    });

    const rows = await repository.listPublished({ limit: 100 });
    const noneExpired = rows.every(
      (r) =>
        (r as unknown as { expires_at: Date | null }).expires_at == null ||
        new Date((r as unknown as { expires_at: Date }).expires_at) > new Date(),
    );
    expect(noneExpired).toBe(true);
  });

  it('filters by locale', async () => {
    await insertResource({ locale: 'en-US', title: 'English resource' });

    const rows = await repository.listPublished({ limit: 10, locale: 'en-US' });

    expect(rows.every((r) => r.locale === 'en-US')).toBe(true);
  });

  it('filters by category', async () => {
    await insertResource({ category: 'MEDITATION', title: 'Meditation resource' });

    const rows = await repository.listPublished({ limit: 10, category: 'MEDITATION' });

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
