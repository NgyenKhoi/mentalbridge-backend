import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { Pool } from 'pg';
import { GenericContainer, Wait, type StartedTestContainer } from 'testcontainers';
import { readFileSync } from 'fs';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';

import { ResourceRepository } from '../../resources/resource.repository.js';
import type { DatabaseService } from '../../database/database.service.js';

const __dirname = dirname(fileURLToPath(import.meta.url));

async function waitForPool(pool: Pool, retries = 10, delayMs = 500): Promise<void> {
  for (let i = 0; i < retries; i++) {
    try {
      await pool.query('SELECT 1');
      return;
    } catch {
      await new Promise((r) => setTimeout(r, delayMs));
    }
  }
  throw new Error('PostgreSQL not ready after retries');
}

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
      .withWaitStrategy(Wait.forLogMessage('database system is ready to accept connections', 2))
      .start();

    pool = new Pool({
      host: container.getHost(),
      port: container.getMappedPort(5432),
      user: 'test_user',
      password: 'test_password',
      database: 'test_db',
      connectionTimeoutMillis: 10_000,
    });

    await waitForPool(pool);

    for (const migration of ['1_initial_schema.sql', '2_remove_hotline_catalogue.sql']) {
      const sql = readFileSync(join(__dirname, '../../../migrations', migration), 'utf8');
      await pool.query(sql);
    }

    const dbService: Pick<DatabaseService, 'query'> = {
      query: <T extends Record<string, unknown>>(text: string, params?: unknown[]) =>
        pool.query<T>(text, params),
    };
    repository = new ResourceRepository(dbService as unknown as DatabaseService);
  }, 120_000);

  afterAll(async () => {
    if (pool) await pool.end();
    if (container) await container.stop();
  });

  async function insertResource(overrides: Record<string, unknown> = {}): Promise<string> {
    const base: Record<string, unknown> = {
      category: 'BREATHING',
      locale: 'vi-VN',
      title: 'Test resource',
      summary: 'Test summary',
      content_body: 'Test body',
      status: 'PUBLISHED',
      reviewed_by: 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
      reviewed_at: new Date().toISOString(),
    };
    const merged = { ...base, ...overrides };
    const entries = Object.entries(merged).filter(([, v]) => v !== null && v !== undefined);
    const cols = entries.map(([k]) => k).join(', ');
    const vals = entries.map(([, v]) => v);
    const placeholders = vals.map((_, i) => '$' + String(i + 1)).join(', ');
    const { rows } = await pool.query<{ id: string }>(
      `INSERT INTO resource (${cols}) VALUES (${placeholders}) RETURNING id`,
      vals,
    );
    return rows[0].id;
  }

  it('returns published reviewed active resources from the real database', async () => {
    const tag = 'seed-' + Date.now();
    await insertResource({ title: tag });

    const rows = await repository.listPublished({ limit: 50 });

    const found = rows.find((r) => (r as unknown as { title: string }).title === tag);
    expect(found).toBeDefined();
    expect(found!.status).toBe('PUBLISHED');
    expect(found!.reviewed_at).not.toBeNull();
  });

  it('does not return unreviewed resources', async () => {
    const tag = 'unreviewed-' + Date.now();
    await insertResource({ title: tag, reviewed_by: undefined, reviewed_at: undefined });

    const rows = await repository.listPublished({ limit: 100 });
    const found = rows.find((r) => (r as unknown as { title: string }).title === tag);
    expect(found).toBeUndefined();
  });

  it('does not return future-effective resources', async () => {
    const tag = 'future-' + Date.now();
    const futureDate = new Date(Date.now() + 60 * 60 * 1000).toISOString();
    await insertResource({ title: tag, effective_at: futureDate });

    const rows = await repository.listPublished({ limit: 100 });
    const found = rows.find((r) => (r as unknown as { title: string }).title === tag);
    expect(found).toBeUndefined();
  });

  it('does not return expired resources', async () => {
    const tag = 'expired-' + Date.now();
    await insertResource({
      title: tag,
      effective_at: new Date(Date.now() - 2 * 60 * 60 * 1000).toISOString(),
      expires_at: new Date(Date.now() - 60 * 60 * 1000).toISOString(),
    });

    const rows = await repository.listPublished({ limit: 100 });
    const found = rows.find((r) => (r as unknown as { title: string }).title === tag);
    expect(found).toBeUndefined();
  });

  it('filters by locale', async () => {
    const tag = 'locale-' + Date.now();
    await insertResource({ locale: 'en-US', title: tag });

    const rows = await repository.listPublished({ limit: 10, locale: 'en-US' });

    expect(rows.length).toBeGreaterThanOrEqual(1);
    expect(rows.every((r) => r.locale === 'en-US')).toBe(true);
  });

  it('filters by category', async () => {
    const tag = 'cat-' + Date.now();
    await insertResource({ category: 'MEDITATION', title: tag });

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

  it('cursor pagination with composite (created_at, id) excludes the cursor row', async () => {
    const sharedTs = new Date(Date.now() - 5000).toISOString();
    await insertResource({
      title: 'cursor-excl-1',
      created_at: sharedTs,
      locale: 'zh-CN',
      category: 'JOURNALING',
    });
    await insertResource({
      title: 'cursor-excl-2',
      created_at: sharedTs,
      locale: 'zh-CN',
      category: 'JOURNALING',
    });
    await insertResource({
      title: 'cursor-excl-3',
      created_at: sharedTs,
      locale: 'zh-CN',
      category: 'JOURNALING',
    });

    const firstPage = await repository.listPublished({
      limit: 2,
      locale: 'zh-CN',
      category: 'JOURNALING',
    });
    expect(firstPage.length).toBe(2);

    const cursorId = firstPage[firstPage.length - 1].id as string;
    const secondPage = await repository.listPublished({
      limit: 50,
      locale: 'zh-CN',
      category: 'JOURNALING',
      cursor: cursorId,
    });

    const secondIds = secondPage.map((r) => (r as unknown as { id: string }).id);
    expect(secondIds).not.toContain(cursorId);
    expect(secondIds.length).toBeGreaterThanOrEqual(1);
  });

  it('cursor pagination does not skip rows sharing created_at with the cursor', async () => {
    const sharedTs = new Date(Date.now() - 10_000).toISOString();
    for (let i = 0; i < 4; i++) {
      await insertResource({
        title: `same-ts-page-${String(i)}`,
        created_at: sharedTs,
        locale: 'ko-KR',
        category: 'COMMUNITY',
      });
    }

    const firstPage = await repository.listPublished({
      limit: 2,
      locale: 'ko-KR',
      category: 'COMMUNITY',
    });
    expect(firstPage.length).toBe(2);
    const cursorId = firstPage[firstPage.length - 1].id as string;

    const secondPage = await repository.listPublished({
      limit: 50,
      locale: 'ko-KR',
      category: 'COMMUNITY',
      cursor: cursorId,
    });

    const allIds = [
      ...firstPage.map((r) => (r as unknown as { id: string }).id),
      ...secondPage.map((r) => (r as unknown as { id: string }).id),
    ];
    const uniqueIds = new Set(allIds);
    expect(secondPage.length).toBeGreaterThanOrEqual(1);
    expect(uniqueIds.size).toBe(allIds.length);
  });
});
