import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { Pool } from 'pg';
import { GenericContainer, type StartedTestContainer } from 'testcontainers';
import { readFileSync } from 'fs';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));

async function waitForPool(pool: Pool, retries = 20, delayMs = 1000): Promise<void> {
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

describe('Database Integration', () => {
  let container: StartedTestContainer;
  let pool: Pool;

  beforeAll(async () => {
    container = await new GenericContainer('postgres:16-alpine')
      .withEnvironment({
        POSTGRES_USER: 'test_user',
        POSTGRES_PASSWORD: 'test_password',
        POSTGRES_DB: 'test_db',
      })
      .withExposedPorts(5432)
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
  }, 120_000);

  afterAll(async () => {
    if (pool) await pool.end();
    if (container) await container.stop();
  });

  describe('resource table', () => {
    it('seeds the controlled Review 1 resource idempotently', async () => {
      const seedPath = join(
        __dirname,
        '../../../migrations/review1',
        '1_seed_review1_controlled_resource.sql',
      );
      const seed = readFileSync(seedPath, 'utf8');

      await pool.query(seed);
      await pool.query(seed);

      const { rows } = await pool.query(
        `SELECT title, locale, status, reviewed_at
         FROM resource
         WHERE id = '00000000-0000-4000-8000-000000000101'`,
      );

      expect(rows).toHaveLength(1);
      expect(rows[0]).toMatchObject({
        title: 'Bài thực hành thở chậm (dữ liệu demo)',
        locale: 'vi-VN',
        status: 'PUBLISHED',
      });
      expect(rows[0].reviewed_at).not.toBeNull();
    });

    it('rejects null category', async () => {
      await expect(
        pool.query('INSERT INTO resource (category, title, summary) VALUES (NULL, $1, $2)', [
          'Title',
          'Summary',
        ]),
      ).rejects.toThrow();
    });

    it('rejects invalid category', async () => {
      await expect(
        pool.query(
          'INSERT INTO resource (category, locale, title, summary, content_body) VALUES ($1,$2,$3,$4,$5)',
          ['INVALID', 'vi-VN', 'T', 'S', 'C'],
        ),
      ).rejects.toThrow(/violates check constraint/);
    });

    it('rejects invalid status', async () => {
      await expect(
        pool.query(
          'INSERT INTO resource (category, locale, title, summary, content_body, status) VALUES ($1,$2,$3,$4,$5,$6)',
          ['BREATHING', 'vi-VN', 'T', 'S', 'C', 'INVALID'],
        ),
      ).rejects.toThrow(/violates check constraint/);
    });

    it('enforces content_or_url constraint', async () => {
      await expect(
        pool.query('INSERT INTO resource (category, locale, title, summary) VALUES ($1,$2,$3,$4)', [
          'BREATHING',
          'vi-VN',
          'T',
          'S',
        ]),
      ).rejects.toThrow(/ck_resource_content_or_url/);
    });

    it('enforces lifecycle_dates constraint', async () => {
      await expect(
        pool.query(
          `INSERT INTO resource (category, locale, title, summary, content_body, effective_at, expires_at)
           VALUES ($1,$2,$3,$4,$5,$6,$7)`,
          ['BREATHING', 'vi-VN', 'T', 'S', 'C', '2025-12-31', '2025-01-01'],
        ),
      ).rejects.toThrow(/ck_resource_lifecycle_dates/);
    });

    it('inserts valid resource', async () => {
      const { rows } = await pool.query(
        `INSERT INTO resource (category, locale, title, summary, content_body)
         VALUES ($1,$2,$3,$4,$5) RETURNING id`,
        ['BREATHING', 'vi-VN', 'Test', 'Summary', 'Body'],
      );
      expect(rows[0].id).toBeDefined();
    });

    it('has ix_resource_browse index', async () => {
      const { rows } = await pool.query(
        `SELECT indexname FROM pg_indexes WHERE tablename='resource' AND indexname='ix_resource_browse'`,
      );
      expect(rows).toHaveLength(1);
    });

    it('has ix_resource_review_window index', async () => {
      const { rows } = await pool.query(
        `SELECT indexname FROM pg_indexes WHERE tablename='resource' AND indexname='ix_resource_review_window'`,
      );
      expect(rows).toHaveLength(1);
    });
  });

  describe('removed hotline catalogue', () => {
    it('does not retain the hotline table after all migrations', async () => {
      const { rows } = await pool.query(`SELECT to_regclass('public.hotline') AS relation`);
      expect(rows[0].relation).toBeNull();
    });
  });

  describe('notification_preference table', () => {
    it('rejects invalid channel', async () => {
      await expect(
        pool.query(
          'INSERT INTO notification_preference (user_id, channel, category) VALUES ($1,$2,$3)',
          ['a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', 'INVALID', 'ASSESSMENT'],
        ),
      ).rejects.toThrow(/violates check constraint/);
    });

    it('enforces composite primary key', async () => {
      await pool.query(
        'INSERT INTO notification_preference (user_id, channel, category) VALUES ($1,$2,$3)',
        ['b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', 'EMAIL', 'CHAT'],
      );
      await expect(
        pool.query(
          'INSERT INTO notification_preference (user_id, channel, category) VALUES ($1,$2,$3)',
          ['b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', 'EMAIL', 'CHAT'],
        ),
      ).rejects.toThrow(/duplicate key/);
    });
  });

  describe('notification table', () => {
    it('rejects invalid priority', async () => {
      await expect(
        pool.query(
          'INSERT INTO notification (recipient_id, category, title, body, priority) VALUES ($1,$2,$3,$4,$5)',
          ['a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', 'ASSESSMENT', 'T', 'B', 'CRITICAL'],
        ),
      ).rejects.toThrow(/violates check constraint/);
    });

    it('has ix_notification_recipient_unread index', async () => {
      const { rows } = await pool.query(
        `SELECT indexname FROM pg_indexes WHERE tablename='notification' AND indexname='ix_notification_recipient_unread'`,
      );
      expect(rows).toHaveLength(1);
    });
  });
});
