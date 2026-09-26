import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { Pool } from 'pg';
import { GenericContainer, type StartedTestContainer } from 'testcontainers';
import { readFileSync } from 'fs';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';
import type { DatabaseService } from '../../database/database.service.js';
import {
  NotificationPreferenceRepository,
  NotificationPreferenceVersionMismatchError,
} from '../../notification-preferences/notification-preference.repository.js';

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
    for (const migration of [
      '1_initial_schema.sql',
      '2_remove_hotline_catalogue.sql',
      '3_add_review_provenance_fields.sql',
      '4_add_idempotency_key.sql',
      '5_add_resource_command_records.sql',
      '6_add_resource_eligibility_v1.sql',
      '7_add_safety_directory.sql',
      '8_add_safety_directory_area_alias.sql',
      '9_add_resource_source_provenance.sql',
      '10_persist_notification_preferences.sql',
    ]) {
      if (migration === '10_persist_notification_preferences.sql') {
        await pool.query(
          `INSERT INTO notification_preference
             (user_id, channel, category, enabled, quiet_hours)
           VALUES
             ('90000000-0000-4000-8000-000000000001', 'IN_APP', 'ASSESSMENT', true,
               '{"enabled":true,"start":"21:30","end":"06:15","timeZone":"Europe/Paris"}'),
             ('90000000-0000-4000-8000-000000000001', 'EMAIL', 'FOLLOW_UP', true,
               '{"enabled":true,"start":"21:30","end":"06:15","timeZone":"Europe/Paris"}'),
             ('90000000-0000-4000-8000-000000000001', 'PUSH', 'SYSTEM', false,
               '{"enabled":true,"start":"21:30","end":"06:15","timeZone":"Europe/Paris"}')`,
        );
      }
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

    it('rejects VIDEO rows without a verified YouTube action', async () => {
      await expect(
        pool.query(
          `INSERT INTO resource (category, locale, title, summary, content_body)
           VALUES ('VIDEO', 'vi-VN', 'Missing action', 'Summary', 'Body')`,
        ),
      ).rejects.toThrow(/ck_resource_video_external_url/);
      await expect(
        pool.query(
          `INSERT INTO resource (category, locale, title, summary, content_body, external_url)
           VALUES ('VIDEO', 'vi-VN', 'Untrusted action', 'Summary', 'Body', 'https://example.com/video')`,
        ),
      ).rejects.toThrow(/ck_resource_video_external_url/);
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

    it('seeds the reviewed MB-556 catalogue idempotently with provenance and real video actions', async () => {
      for (const migration of [
        '2_publish_initial_resource_eligibility.sql',
        '3_seed_safety_directory_controlled_demo.sql',
        '4_align_controlled_demo_area.sql',
        '5_seed_safety_directory_area_aliases.sql',
        '6_seed_mb556_reviewed_resource_catalogue.sql',
      ]) {
        const sql = readFileSync(join(__dirname, '../../../migrations/review1', migration), 'utf8');
        await pool.query(sql);
        if (migration === '6_seed_mb556_reviewed_resource_catalogue.sql') {
          await pool.query(sql);
        }
      }

      const catalogue = await pool.query<{
        count: string;
        sourced: string;
        videos: string;
        actionable_videos: string;
      }>(
        `SELECT
           count(*)::text AS count,
           count(*) FILTER (WHERE source_organization IS NOT NULL
                              AND source_title IS NOT NULL
                              AND source_url IS NOT NULL
                              AND source_review_note IS NOT NULL)::text AS sourced,
           count(*) FILTER (WHERE category = 'VIDEO')::text AS videos,
           count(*) FILTER (WHERE category = 'VIDEO'
                              AND external_url ~ '^https://(www\\.)?(youtube\\.com|youtu\\.be)/')::text
             AS actionable_videos
         FROM resource
         WHERE id BETWEEN '00000000-0000-4000-8000-000000000201'::uuid
                      AND '00000000-0000-4000-8000-000000000215'::uuid
           AND catalogue_visibility = 'LISTED'`,
      );
      expect(catalogue.rows[0]).toEqual({
        count: '15',
        sourced: '15',
        videos: '3',
        actionable_videos: '3',
      });

      const supportGuideCoverage = await pool.query<{
        resource_id: string;
        target_domain: string;
        instrument: string;
        screening_levels: string[];
        support_tiers: string[];
      }>(
        `SELECT p.resource_id::text, d.target_domain, d.instrument,
           d.screening_levels, d.support_tiers
         FROM resource_eligibility_publication p
         JOIN resource_eligibility_declaration d ON d.publication_id = p.id
         WHERE p.resource_id = ANY($1::uuid[])
           AND p.content_version = 0
           AND d.eligibility_role = 'PRIMARY'
         ORDER BY p.resource_id`,
        [
          [
            '00000000-0000-4000-8000-000000000201',
            '00000000-0000-4000-8000-000000000205',
            '00000000-0000-4000-8000-000000000206',
            '00000000-0000-4000-8000-000000000208',
          ],
        ],
      );
      expect(supportGuideCoverage.rows).toEqual([
        {
          resource_id: '00000000-0000-4000-8000-000000000201',
          target_domain: 'ANXIETY_SYMPTOMS',
          instrument: 'GAD_7',
          screening_levels: ['MINIMAL', 'MILD', 'MODERATE', 'SEVERE'],
          support_tiers: [
            'SELF_GUIDED_SUPPORT',
            'PROFESSIONAL_SUPPORT_RECOMMENDED',
            'SAFETY_FOLLOW_UP_RECOMMENDED',
          ],
        },
        {
          resource_id: '00000000-0000-4000-8000-000000000205',
          target_domain: 'DEPRESSIVE_SYMPTOMS',
          instrument: 'PHQ_9',
          screening_levels: ['MINIMAL', 'MILD', 'MODERATE', 'MODERATELY_SEVERE', 'SEVERE'],
          support_tiers: [
            'SELF_GUIDED_SUPPORT',
            'PROFESSIONAL_SUPPORT_RECOMMENDED',
            'SAFETY_FOLLOW_UP_RECOMMENDED',
          ],
        },
        {
          resource_id: '00000000-0000-4000-8000-000000000206',
          target_domain: 'ANXIETY_SYMPTOMS',
          instrument: 'GAD_7',
          screening_levels: ['MINIMAL', 'MILD', 'MODERATE', 'SEVERE'],
          support_tiers: [
            'SELF_GUIDED_SUPPORT',
            'PROFESSIONAL_SUPPORT_RECOMMENDED',
            'SAFETY_FOLLOW_UP_RECOMMENDED',
          ],
        },
        {
          resource_id: '00000000-0000-4000-8000-000000000208',
          target_domain: 'DEPRESSIVE_SYMPTOMS',
          instrument: 'PHQ_9',
          screening_levels: ['MINIMAL', 'MILD', 'MODERATE', 'MODERATELY_SEVERE', 'SEVERE'],
          support_tiers: [
            'SELF_GUIDED_SUPPORT',
            'PROFESSIONAL_SUPPORT_RECOMMENDED',
            'SAFETY_FOLLOW_UP_RECOMMENDED',
          ],
        },
      ]);

      const legacy = await pool.query<{
        listed: string;
        resource_104_category: string;
        resource_104_version: string;
        resource_104_source_organization: string | null;
      }>(
        `SELECT
           count(*) FILTER (WHERE catalogue_visibility = 'LISTED')::text AS listed,
           max(category) FILTER (WHERE id = '00000000-0000-4000-8000-000000000104')
             AS resource_104_category,
           max(version) FILTER (WHERE id = '00000000-0000-4000-8000-000000000104')
             AS resource_104_version,
           max(source_organization)
             FILTER (WHERE id = '00000000-0000-4000-8000-000000000104')
             AS resource_104_source_organization
         FROM resource
         WHERE id BETWEEN '00000000-0000-4000-8000-000000000101'::uuid
                      AND '00000000-0000-4000-8000-000000000106'::uuid`,
      );
      expect(legacy.rows[0]).toEqual({
        listed: '0',
        resource_104_category: 'VIDEO',
        resource_104_version: '0',
        resource_104_source_organization: null,
      });
    });
  });

  describe('removed hotline catalogue', () => {
    it('does not retain the hotline table after all migrations', async () => {
      const { rows } = await pool.query(`SELECT to_regclass('public.hotline') AS relation`);
      expect(rows[0].relation).toBeNull();
    });
  });

  describe('notification_preference table', () => {
    it('maps legacy channel, group and quiet-hour choices forward', async () => {
      const { rows } = await pool.query(
        `SELECT channel_in_app_enabled, channel_email_enabled, channel_push_enabled,
           group_journal_reminder_enabled, group_screening_reassessment_enabled,
           group_resource_system_enabled, quiet_hours_enabled,
           quiet_hours_start::text, quiet_hours_end::text, time_zone
         FROM notification_preference
         WHERE user_id = '90000000-0000-4000-8000-000000000001'`,
      );
      expect(rows[0]).toEqual({
        channel_in_app_enabled: true,
        channel_email_enabled: true,
        channel_push_enabled: false,
        group_journal_reminder_enabled: true,
        group_screening_reassessment_enabled: true,
        group_resource_system_enabled: false,
        quiet_hours_enabled: true,
        quiet_hours_start: '21:30:00',
        quiet_hours_end: '06:15:00',
        time_zone: 'Europe/Paris',
      });
    });

    it('persists one complete default aggregate per owner', async () => {
      const userId = 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11';
      const { rows } = await pool.query(
        `INSERT INTO notification_preference (user_id)
         VALUES ($1)
         RETURNING notifications_enabled, channel_in_app_enabled, channel_email_enabled,
           channel_push_enabled, quiet_hours_enabled, quiet_hours_start::text,
           quiet_hours_end::text, time_zone, email_cadence, version`,
        [userId],
      );

      expect(rows[0]).toMatchObject({
        notifications_enabled: true,
        channel_in_app_enabled: true,
        channel_email_enabled: false,
        channel_push_enabled: false,
        quiet_hours_enabled: false,
        quiet_hours_start: '22:00:00',
        quiet_hours_end: '07:00:00',
        time_zone: 'Asia/Ho_Chi_Minh',
        email_cadence: 'IMMEDIATE',
        version: '0',
      });
    });

    it('rejects invalid quiet windows and email cadence', async () => {
      await expect(
        pool.query(
          `INSERT INTO notification_preference
             (user_id, quiet_hours_enabled, quiet_hours_start, quiet_hours_end)
           VALUES ($1, true, '08:00', '08:00')`,
          ['c0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'],
        ),
      ).rejects.toThrow(/ck_notification_preference_quiet_window/);

      await expect(
        pool.query('INSERT INTO notification_preference (user_id, email_cadence) VALUES ($1,$2)', [
          'd0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
          'HOURLY',
        ]),
      ).rejects.toThrow(/ck_notification_preference_email_cadence/);
    });

    it('enforces one aggregate per owner', async () => {
      await pool.query('INSERT INTO notification_preference (user_id) VALUES ($1)', [
        'b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
      ]);
      await expect(
        pool.query('INSERT INTO notification_preference (user_id) VALUES ($1)', [
          'b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
        ]),
      ).rejects.toThrow(/duplicate key/);
    });

    it('persists a partial/full aggregate across repository instances and isolates owners', async () => {
      const database = {
        query: (text: string, parameters?: unknown[]) => pool.query(text, parameters),
      } as unknown as DatabaseService;
      const firstDevice = new NotificationPreferenceRepository(database);
      const secondDevice = new NotificationPreferenceRepository(database);
      const owner = 'e0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11';

      const initial = await firstDevice.getOrCreate(owner);
      const saved = await firstDevice.update(owner, initial.version, {
        ...initial,
        channels: { inApp: true, email: true, push: true },
        contentGroups: {
          ...initial.contentGroups,
          emotionCheckIn: false,
          appointmentMessage: false,
        },
        quietHours: {
          enabled: true,
          start: '23:15',
          end: '06:45',
          timeZone: 'America/New_York',
        },
        email: {
          cadence: 'WEEKLY_DIGEST',
          wellbeingDigestEnabled: true,
          resourceRemindersEnabled: true,
        },
      });

      const reloaded = await secondDevice.getOrCreate(owner);
      expect(reloaded).toEqual(saved);
      expect(reloaded.version).toBe(1);
      expect(reloaded.quietHours.timeZone).toBe('America/New_York');

      const otherOwner = await secondDevice.getOrCreate('f0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11');
      expect(otherOwner.channels.email).toBe(false);
      expect(otherOwner.contentGroups.appointmentMessage).toBe(true);

      await expect(firstDevice.update(owner, 0, saved)).rejects.toBeInstanceOf(
        NotificationPreferenceVersionMismatchError,
      );
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
