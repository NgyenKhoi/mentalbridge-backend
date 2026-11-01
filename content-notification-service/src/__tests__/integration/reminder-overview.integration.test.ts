import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { Pool } from 'pg';
import { GenericContainer, type StartedTestContainer } from 'testcontainers';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { DatabaseService } from '../../database/database.service.js';
import { loadConfiguration } from '../../configuration/configuration.js';
import { NotificationRepository } from '../../notifications/notification.repository.js';
import { WellbeingDigestRepository } from '../../wellbeing-digest/wellbeing-digest.repository.js';
import { ReminderOverviewRepository } from '../../reminders/reminder-overview.repository.js';

const owner = 'a13e4567-e89b-42d3-a456-426614174000';
let container: StartedTestContainer | undefined;
let db: DatabaseService | undefined;
let pool: Pool | undefined;
let repository: ReminderOverviewRepository;

beforeAll(async () => {
  container = await new GenericContainer('postgres:16-alpine')
    .withEnvironment({ POSTGRES_USER: 'test', POSTGRES_PASSWORD: 'test', POSTGRES_DB: 'test' })
    .withExposedPorts(5432)
    .start();
  const connection = `postgres://test:test@${container.getHost()}:${container.getMappedPort(5432)}/test`;
  pool = new Pool({ connectionString: connection });
  const directory = fileURLToPath(new URL('../../../migrations/', import.meta.url));
  for (const file of readdirSync(directory)
    .filter((name) => /^\d+_.*\.sql$/.test(name))
    .sort((a, b) => Number(a.split('_')[0]) - Number(b.split('_')[0])))
    await pool.query(readFileSync(`${directory}/${file}`, 'utf8').split('-- Down Migration')[0]);
  db = new DatabaseService(
    loadConfiguration({
      NODE_ENV: 'test',
      DATABASE_URL: connection,
      IDENTITY_JWT_ISSUER: 'https://identity.test.invalid',
      IDENTITY_JWT_AUDIENCE: 'mentalbridge-api',
      IDENTITY_JWT_PUBLIC_KEY: 'unused-by-database-test',
    }),
  );
  repository = new ReminderOverviewRepository(db);
});
afterAll(async () => {
  await db?.onModuleDestroy();
  await pool?.end();
  await container?.stop();
});

describe('reminder overview PostgreSQL reads', () => {
  it('returns only the current owner latest appointment delivery fact, not another account or an old version', async () => {
    await pool!.query(
      `INSERT INTO appointment_email_reminder
      (recipient_id, appointment_id, appointment_version, appointment_status, modality, scheduled_start_at, target_at, due_at, provider_idempotency_key, delivery_state, updated_at)
      VALUES
      ($1,'11111111-1111-4111-8111-111111111111',1,'CONFIRMED','IN_APP_CHAT','2026-10-22T20:00:00Z','2026-10-22T19:00:00Z','2026-10-22T19:00:00Z',gen_random_uuid(),'PENDING','2026-10-22T01:00:00Z'),
      ($1,'11111111-1111-4111-8111-111111111111',2,'CANCELLED','IN_APP_CHAT','2026-10-22T20:00:00Z','2026-10-22T19:00:00Z','2026-10-22T19:00:00Z',gen_random_uuid(),'INVALIDATED','2026-10-22T02:00:00Z'),
      ('b13e4567-e89b-42d3-a456-426614174000','22222222-2222-4222-8222-222222222222',1,'CONFIRMED','IN_APP_CHAT','2026-10-22T20:00:00Z','2026-10-22T19:00:00Z','2026-10-22T19:00:00Z',gen_random_uuid(),'DELIVERED','2026-10-22T03:00:00Z')`,
      [owner],
    );
    expect((await repository.deliveries(owner, '2026-10-22')).APPOINTMENT_EMAIL).toEqual({
      state: 'INVALIDATED',
      recordedAt: '2026-10-22T02:00:00.000Z',
    });
    await pool!.query('DELETE FROM appointment_email_reminder');
  });
  it('returns owner/day-specific delivery facts without writing jobs or resurrecting deleted inbox items', async () => {
    const date = '2026-10-22';
    const notifications = new NotificationRepository(db!);
    await notifications.create(
      {
        ownerId: owner,
        kind: 'JOURNAL_REMINDER',
        title: 'Generic reminder',
        body: 'Generic body',
        priority: 'NORMAL',
        source: 'JOURNAL_AI_ACTIVITY',
        sourceIdentity: `JOURNAL_REMINDER:${date}`,
        occurredAt: '2026-10-22T00:00:00Z',
      },
      'a'.repeat(64),
    );
    await pool!.query(`UPDATE notification SET deleted_at = now() WHERE recipient_id = $1`, [
      owner,
    ]);
    await pool!.query(
      `INSERT INTO wellbeing_email_delivery(owner_id, local_date, delivery_kind, time_zone, state) VALUES ($1,$2,'DAILY_DIGEST','Asia/Ho_Chi_Minh','DELIVERED')`,
      [owner, date],
    );
    const before = await pool!.query('SELECT count(*) FROM notification');
    const facts = await repository.deliveries(owner, date);
    expect(facts.JOURNAL_REMINDER?.state).toBe('DELIVERED');
    expect(facts.DAILY_DIGEST?.state).toBe('DELIVERED');
    expect(await repository.deliveries('b13e4567-e89b-42d3-a456-426614174000', date)).toEqual({});
    expect(await repository.deliveries(owner, '2026-10-21')).toEqual({});
    expect((await pool!.query('SELECT count(*) FROM notification')).rows).toEqual(before.rows);
    expect(
      (await pool!.query('SELECT deleted_at FROM notification WHERE recipient_id=$1', [owner]))
        .rows[0].deleted_at,
    ).not.toBeNull();
  });
  it('keeps existing delivery dedupe for concurrent retries and repeated DST local dates', async () => {
    const digest = new WellbeingDigestRepository(db!);
    const claims = await Promise.all(
      Array.from({ length: 4 }, () =>
        digest.claim(owner, '2026-10-25', 'Europe/Paris', 'DAILY_DIGEST', {}),
      ),
    );
    expect(claims.filter(Boolean)).toHaveLength(1);
    const claim = claims.find(Boolean)!;
    await digest.delivered(claim.id, null);
    expect(await digest.claim(owner, '2026-10-25', 'Europe/Paris', 'DAILY_DIGEST', {})).toBeNull();
    expect((await repository.deliveries(owner, '2026-10-25')).DAILY_DIGEST?.state).toBe(
      'DELIVERED',
    );
    expect(
      await digest.claim(owner, '2026-10-26', 'Europe/Paris', 'DAILY_DIGEST', {}),
    ).not.toBeNull();
  });
});
