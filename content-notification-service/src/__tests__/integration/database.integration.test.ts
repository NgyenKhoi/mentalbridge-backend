import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { Pool } from 'pg';
import { GenericContainer, type StartedTestContainer } from 'testcontainers';
import { readFileSync } from 'fs';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';
import type { DatabaseClient, DatabaseService } from '../../database/database.service.js';
import { AppointmentReminderRepository } from '../../appointment-reminders/appointment-reminder.repository.js';
import type { AppointmentStatusChangedEvent } from '../../appointment-reminders/appointment-reminder.types.js';
import {
  NotificationPreferenceRepository,
  NotificationPreferenceVersionMismatchError,
} from '../../notification-preferences/notification-preference.repository.js';
import {
  NotificationDedupeConflictError,
  NotificationRepository,
} from '../../notifications/notification.repository.js';
import { NotificationService } from '../../notifications/notification.service.js';
import { ResourceProgressRepository } from '../../resources/resource-progress.repository.js';
import { ResourceJourneyRepository } from '../../resources/resource-journey.repository.js';
import { WellbeingDigestRepository } from '../../wellbeing-digest/wellbeing-digest.repository.js';

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
      '11_persist_notification_inbox.sql',
      '12_add_journal_emotion_notification_kinds.sql',
      '13_add_resource_daily_progress.sql',
      '14_add_resource_experience_model.sql',
      '15_harden_resource_journey.sql',
      '16_add_wellbeing_digest_delivery.sql',
      '17_add_appointment_email_reminders.sql',
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
      if (migration === '11_persist_notification_inbox.sql') {
        await pool.query(
          `INSERT INTO notification (recipient_id, category, title, body)
           VALUES ('90000000-0000-4000-8000-000000000002', 'SAFETY',
             'Legacy safety copy', 'Legacy record must not become active')`,
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

    it('seeds the reviewed resource catalogue idempotently with provenance and wellbeing summaries', async () => {
      for (const migration of [
        '2_publish_initial_resource_eligibility.sql',
        '3_seed_safety_directory_controlled_demo.sql',
        '4_align_controlled_demo_area.sql',
        '5_seed_safety_directory_area_aliases.sql',
        '6_seed_mb556_reviewed_resource_catalogue.sql',
        '7_seed_mb603_resource_experience.sql',
        '8_correct_mb603_demo_effective_time.sql',
        '9_update_mb603_resource_wellbeing_summaries.sql',
        '10_align_mb603_timed_practice_duration.sql',
        '11_record_mb603_source_content_hashes.sql',
      ]) {
        const sql = readFileSync(join(__dirname, '../../../migrations/review1', migration), 'utf8');
        await pool.query(sql);
        if (
          migration === '6_seed_mb556_reviewed_resource_catalogue.sql' ||
          migration === '7_seed_mb603_resource_experience.sql' ||
          migration === '9_update_mb603_resource_wellbeing_summaries.sql' ||
          migration === '10_align_mb603_timed_practice_duration.sql' ||
          migration === '11_record_mb603_source_content_hashes.sql'
        ) {
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

      const wellbeingSummaries = await pool.query<{
        count: string;
        matching_overview: string;
        source_hashed: string;
      }>(
        `SELECT
           count(*)::text AS count,
           count(*) FILTER (WHERE summary = structured_content ->> 'overview')::text
             AS matching_overview,
           count(*) FILTER (WHERE source_content_hash IS NOT NULL)::text AS source_hashed
         FROM resource
         WHERE id BETWEEN '00000000-0000-4000-8000-000000000201'::uuid
                      AND '00000000-0000-4000-8000-000000000223'::uuid`,
      );
      expect(wellbeingSummaries.rows[0]).toEqual({
        count: '23',
        matching_overview: '23',
        source_hashed: '18',
      });

      const timedDurations = await pool.query<{
        breathing_seconds: string;
        relaxation_seconds: string;
      }>(
        `SELECT
           (SELECT ((interaction_config ->> 'inhaleSeconds')::integer
                    + (interaction_config ->> 'exhaleSeconds')::integer
                    + (interaction_config ->> 'holdSeconds')::integer)
                   * (interaction_config ->> 'cycles')::integer
            FROM resource WHERE id = '00000000-0000-4000-8000-000000000201')::text
             AS breathing_seconds,
           (SELECT sum((step ->> 'seconds')::integer)
            FROM resource,
                 jsonb_array_elements(interaction_config -> 'steps') AS step
            WHERE id = '00000000-0000-4000-8000-000000000216')::text
             AS relaxation_seconds`,
      );
      expect(timedDurations.rows[0]).toEqual({
        breathing_seconds: '297',
        relaxation_seconds: '600',
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

    it('materializes seven same-week dates concurrently without racing the bingo board', async () => {
      const database = {
        query: (text: string, parameters?: unknown[]) => pool.query(text, parameters),
        withTransaction: async <T>(operation: (client: Pool) => Promise<T>) => {
          const client = await pool.connect();
          try {
            await client.query('BEGIN');
            const result = await operation(client as unknown as Pool);
            await client.query('COMMIT');
            return result;
          } catch (error) {
            await client.query('ROLLBACK');
            throw error;
          } finally {
            client.release();
          }
        },
      } as unknown as DatabaseService;
      const repository = new ResourceJourneyRepository(database);
      const owner = '12700000-0000-4000-8000-000000000001';
      const dates = Array.from({ length: 7 }, (_, index) =>
        new Date(Date.UTC(2026, 8, 28 + index)).toISOString().slice(0, 10),
      );
      const journeys = await Promise.all(
        dates.map((requestedDate) =>
          repository.materialize(owner, requestedDate, {
            timeZone: 'Asia/Ho_Chi_Minh',
            supportPlan: {
              supportPlanId: '12700000-0000-4000-8000-000000000099',
              version: 1,
              status: 'ACTIVE',
              activatedAt: '2026-09-27T17:00:00Z',
              domains: ['DEPRESSIVE_SYMPTOMS', 'ANXIETY_SYMPTOMS'],
              selectedResourceIds: [],
            },
          }),
        ),
      );

      expect(journeys.every((journey) => journey !== null)).toBe(true);
      expect(journeys.map((journey) => journey?.weekStart)).toEqual(
        Array.from({ length: 7 }, () => '2026-09-28'),
      );
      const boardCount = await pool.query<{ count: string }>(
        `SELECT count(*)::text AS count
         FROM resource_weekly_bingo
         WHERE owner_id = $1 AND week_start = '2026-09-28'`,
        [owner],
      );
      expect(boardCount.rows[0].count).toBe('1');
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

    it('initializes one default aggregate idempotently from account lifecycle delivery', async () => {
      const database = {
        query: (text: string, parameters?: unknown[]) => pool.query(text, parameters),
      } as unknown as DatabaseService;
      const repository = new NotificationPreferenceRepository(database);
      const owner = 'a1eebc99-9c0b-4ef8-bb6d-6bb9bd380a11';

      await repository.createDefaults(owner);
      await repository.createDefaults(owner);

      const { rows } = await pool.query(
        `SELECT count(*)::int AS aggregate_count,
                bool_and(notifications_enabled AND channel_in_app_enabled) AS defaults_enabled
         FROM notification_preference
         WHERE user_id = $1`,
        [owner],
      );
      expect(rows[0]).toEqual({ aggregate_count: 1, defaults_enabled: true });
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
          dailyDigestTime: '19:00',
          resourceReminderTime: '18:30',
          appointmentRemindersEnabled: true,
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

    it('pages only enabled in-app reminder candidates', async () => {
      const database = {
        query: (text: string, parameters?: unknown[]) => pool.query(text, parameters),
      } as unknown as DatabaseService;
      const repository = new NotificationPreferenceRepository(database);
      const enabledOwner = '11000000-0000-4000-8000-000000000001';
      const disabledOwner = '11000000-0000-4000-8000-000000000002';
      const emailOnlyOwner = '11000000-0000-4000-8000-000000000003';

      await pool.query(
        `INSERT INTO notification_preference
           (user_id, notifications_enabled, channel_in_app_enabled)
         VALUES ($1, true, true), ($2, false, true), ($3, true, false)`,
        [enabledOwner, disabledOwner, emailOnlyOwner],
      );

      const firstPage = await repository.listReminderCandidates(null, 1);
      const secondPage = await repository.listReminderCandidates(firstPage[0]?.ownerId ?? null, 20);
      const owners = [...firstPage, ...secondPage].map((candidate) => candidate.ownerId);

      expect(owners).toContain(enabledOwner);
      expect(owners).not.toContain(disabledOwner);
      expect(owners).not.toContain(emailOnlyOwner);
    });

    it('pages only opted-in email candidates and enforces one delivery kind per local day', async () => {
      const database = {
        query: (text: string, parameters?: unknown[]) => pool.query(text, parameters),
        withTransaction: async <T>(
          operation: (
            client: import('../../database/database.service.js').DatabaseClient,
          ) => Promise<T>,
        ) => {
          const client = await pool.connect();
          try {
            await client.query('BEGIN');
            const result = await operation(client);
            await client.query('COMMIT');
            return result;
          } catch (error) {
            await client.query('ROLLBACK');
            throw error;
          } finally {
            client.release();
          }
        },
      } as unknown as DatabaseService;
      const repository = new NotificationPreferenceRepository(database);
      const digestRepository = new WellbeingDigestRepository(database);
      const owner = '11800000-0000-4000-8000-000000000001';
      await pool.query(
        `INSERT INTO notification_preference
           (user_id, channel_email_enabled, email_wellbeing_digest_enabled, email_cadence)
         VALUES ($1, true, true, 'DAILY_DIGEST')`,
        [owner],
      );
      expect(
        (await repository.listEmailCandidates(null, 500)).map((item) => item.ownerId),
      ).toContain(owner);

      const claims = await Promise.all(
        Array.from({ length: 7 }, () =>
          digestRepository.claim(owner, '2026-09-30', 'Asia/Ho_Chi_Minh', 'DAILY_DIGEST', {
            resources: 2,
          }),
        ),
      );
      expect(claims.filter(Boolean)).toHaveLength(1);
      const count = await pool.query<{ count: string }>(
        `SELECT count(*)::text AS count FROM wellbeing_email_delivery
         WHERE owner_id = $1 AND local_date = '2026-09-30' AND delivery_kind = 'DAILY_DIGEST'`,
        [owner],
      );
      expect(count.rows[0]?.count).toBe('1');

      await pool.query(
        `UPDATE wellbeing_email_delivery
         SET claimed_at = now() - interval '10 minutes'
         WHERE owner_id = $1 AND local_date = '2026-09-30' AND delivery_kind = 'DAILY_DIGEST'`,
        [owner],
      );
      await expect(
        digestRepository.claim(owner, '2026-09-30', 'Asia/Ho_Chi_Minh', 'DAILY_DIGEST', {
          resources: 1,
        }),
      ).resolves.toEqual(expect.objectContaining({ kind: 'DAILY_DIGEST' }));
      const reclaimed = await pool.query<{
        attempt_count: number;
        content_counts: { resources: number };
      }>(
        `SELECT attempt_count, content_counts FROM wellbeing_email_delivery
         WHERE owner_id = $1 AND local_date = '2026-09-30' AND delivery_kind = 'DAILY_DIGEST'`,
        [owner],
      );
      expect(reclaimed.rows[0]).toEqual({ attempt_count: 2, content_counts: { resources: 1 } });
    });
  });

  describe('appointment email reminder tables', () => {
    function reminderRepository() {
      const db = {
        query: (sql: string, parameters?: unknown[]) => pool.query(sql, parameters),
        withTransaction: async <T>(
          operation: (client: DatabaseClient) => Promise<T>,
        ): Promise<T> => {
          const client = await pool.connect();
          try {
            await client.query('BEGIN');
            const value = await operation(client);
            await client.query('COMMIT');
            return value;
          } catch (error) {
            await client.query('ROLLBACK');
            throw error;
          } finally {
            client.release();
          }
        },
      } as unknown as DatabaseService;
      return new AppointmentReminderRepository(db);
    }

    function statusEvent(
      appointmentId: string,
      version: number,
      status: AppointmentStatusChangedEvent['payload']['status'],
      replacementId: string | null = null,
    ): AppointmentStatusChangedEvent {
      return {
        messageId: '24000000-0000-4000-8000-000000000001',
        messageType: 'consultation.appointment.status-changed',
        occurredAt: '2029-01-01T00:00:00.000Z',
        producer: 'consultation-service',
        schemaVersion: '1.0',
        correlationId: '24000000-0000-4000-8000-000000000002',
        aggregateId: appointmentId,
        aggregateVersion: version,
        payload: {
          appointmentId,
          ownerAccountId: '24000000-0000-4000-8000-000000000003',
          status,
          scheduledStartAt: '2030-01-02T03:00:00.000Z',
          modality: 'IN_APP_CHAT',
          replacesAppointmentId: replacementId,
        },
      };
    }

    it('keeps the newest checkpoint under simultaneous, duplicate and reordered events', async () => {
      const repository = reminderRepository();
      const appointmentId = '24000000-0000-4000-8000-000000000004';
      const observedAt = new Date('2030-01-01T00:00:00.000Z');
      await Promise.all([
        repository.apply(statusEvent(appointmentId, 1, 'CONFIRMED'), observedAt),
        repository.apply(statusEvent(appointmentId, 2, 'CANCELLED'), observedAt),
      ]);
      await repository.apply(statusEvent(appointmentId, 1, 'CONFIRMED'), observedAt);

      const checkpoint = await pool.query(
        'SELECT latest_version, latest_status FROM appointment_reminder_checkpoint WHERE appointment_id=$1',
        [appointmentId],
      );
      expect(checkpoint.rows[0]).toMatchObject({ latest_version: '2', latest_status: 'CANCELLED' });
      const reminders = await pool.query(
        'SELECT delivery_state FROM appointment_email_reminder WHERE appointment_id=$1',
        [appointmentId],
      );
      expect(reminders.rows.every((row) => row.delivery_state === 'INVALIDATED')).toBe(true);
    });

    it('creates one replacement intent and claims a due version only once across workers', async () => {
      const repository = reminderRepository();
      const oldId = '24000000-0000-4000-8000-000000000005';
      const replacementId = '24000000-0000-4000-8000-000000000006';
      const observedAt = new Date('2030-01-01T00:00:00.000Z');
      await repository.apply(statusEvent(oldId, 1, 'CONFIRMED'), observedAt);
      await repository.apply(statusEvent(oldId, 2, 'CANCELLED'), observedAt);
      await repository.apply(statusEvent(replacementId, 1, 'CONFIRMED', oldId), observedAt);
      await repository.apply(statusEvent(replacementId, 1, 'CONFIRMED', oldId), observedAt);

      const old = await pool.query(
        'SELECT delivery_state FROM appointment_email_reminder WHERE appointment_id=$1',
        [oldId],
      );
      expect(old.rows).toEqual([{ delivery_state: 'INVALIDATED' }]);
      const replacement = await pool.query(
        'SELECT count(*)::int AS count FROM appointment_email_reminder WHERE appointment_id=$1',
        [replacementId],
      );
      expect(replacement.rows[0].count).toBe(1);
      const claimed = await Promise.all([
        repository.claimDue(new Date('2030-01-02T02:00:00.000Z'), 100),
        repository.claimDue(new Date('2030-01-02T02:00:00.000Z'), 100),
      ]);
      expect(claimed.flat().filter((row) => row.appointmentId === replacementId)).toHaveLength(1);
    });

    it('deduplicates one reminder per recipient, appointment and version', async () => {
      const values = [
        '21000000-0000-4000-8000-000000000001',
        '22000000-0000-4000-8000-000000000001',
        7,
        '2027-01-02T03:00:00.000Z',
        '23000000-0000-5000-8000-000000000001',
      ];
      await pool.query(
        `INSERT INTO appointment_email_reminder
           (recipient_id, appointment_id, appointment_version, appointment_status,
            modality, scheduled_start_at, target_at, due_at, provider_idempotency_key)
         VALUES ($1,$2,$3,'CONFIRMED','IN_APP_CHAT',$4::timestamptz,
                 $4::timestamptz - interval '60 minutes',
                 $4::timestamptz - interval '60 minutes',$5)`,
        values,
      );

      await expect(
        pool.query(
          `INSERT INTO appointment_email_reminder
             (recipient_id, appointment_id, appointment_version, appointment_status,
              modality, scheduled_start_at, target_at, due_at, provider_idempotency_key)
           VALUES ($1,$2,$3,'CONFIRMED','IN_APP_CHAT',$4::timestamptz,
                   $4::timestamptz - interval '60 minutes',
                   $4::timestamptz - interval '60 minutes',$5)`,
          values,
        ),
      ).rejects.toThrow(/duplicate key/);
    });

    it('rejects reminder targets that are not exactly sixty minutes before start', async () => {
      await expect(
        pool.query(
          `INSERT INTO appointment_email_reminder
             (recipient_id, appointment_id, appointment_version, appointment_status,
              modality, scheduled_start_at, target_at, due_at, provider_idempotency_key)
           VALUES ($1,$2,1,'CONFIRMED','IN_APP_VIDEO',$3::timestamptz,
                   $3::timestamptz - interval '30 minutes',
                   $3::timestamptz - interval '30 minutes',$4)`,
          [
            '21000000-0000-4000-8000-000000000002',
            '22000000-0000-4000-8000-000000000002',
            '2027-01-02T04:00:00.000Z',
            '23000000-0000-5000-8000-000000000002',
          ],
        ),
      ).rejects.toThrow(/ck_appointment_email_reminder_time/);
    });

    it('persists late CONFIRMED event after appointment start safely as terminal EXPIRED without provider send', async () => {
      const repository = reminderRepository();
      const appointmentId = '24000000-0000-4000-8000-000000000007';
      const startAt = '2030-01-02T03:00:00.000Z';
      const observedAfterStart = new Date('2030-01-02T03:15:00.000Z');

      const lateEvent = statusEvent(appointmentId, 1, 'CONFIRMED');
      lateEvent.payload.scheduledStartAt = startAt;

      // Consumed after start -> must not throw
      await expect(repository.apply(lateEvent, observedAfterStart)).resolves.not.toThrow();

      // Checkpoint recorded
      const checkpoint = await pool.query(
        'SELECT latest_version, latest_status FROM appointment_reminder_checkpoint WHERE appointment_id=$1',
        [appointmentId],
      );
      expect(checkpoint.rows[0]).toMatchObject({ latest_version: '1', latest_status: 'CONFIRMED' });

      // Terminal EXPIRED recorded with LATE_CONFIRMATION and due_at clamped <= scheduled_start_at
      const reminders = await pool.query<{
        delivery_state: string;
        failure_code: string;
        due_at: string;
        scheduled_start_at: string;
      }>(
        'SELECT delivery_state, failure_code, due_at, scheduled_start_at FROM appointment_email_reminder WHERE appointment_id=$1',
        [appointmentId],
      );
      expect(reminders.rows).toHaveLength(1);
      expect(reminders.rows[0].delivery_state).toBe('EXPIRED');
      expect(reminders.rows[0].failure_code).toBe('LATE_CONFIRMATION');
      expect(new Date(reminders.rows[0].due_at).getTime()).toBeLessThanOrEqual(
        new Date(reminders.rows[0].scheduled_start_at).getTime(),
      );

      // Claim due: must not claim terminal EXPIRED reminders (no provider send)
      const claimed = await repository.claimDue(new Date('2030-01-02T04:00:00.000Z'), 100);
      expect(claimed.some((row) => row.appointmentId === appointmentId)).toBe(false);
    });
  });

  describe('resource_daily_progress table', () => {
    it('persists idempotent owner-scoped daily progress for an active reviewed resource', async () => {
      const database = {
        query: (text: string, parameters?: unknown[]) => pool.query(text, parameters),
        withTransaction: async <T>(operation: (client: Pool) => Promise<T>) => operation(pool),
      } as unknown as DatabaseService;
      const repository = new ResourceProgressRepository(database);
      const owner = '12500000-0000-4000-8000-000000000001';
      const otherOwner = '12500000-0000-4000-8000-000000000002';
      const resource = await pool.query<{ id: string }>(
        `INSERT INTO resource
           (category, locale, title, summary, content_body, source_organization,
            source_title, source_url, source_review_note, status, reviewed_by,
            reviewed_at, effective_at, source_review_status, resource_kind,
            interaction_type, repeatability, completion_mode, streak_eligible)
         VALUES ('BREATHING', 'vi-VN', 'Daily breathing', 'Daily summary', 'Body',
           'Reviewed source', 'Reviewed title', 'https://example.com/source', 'Reviewed note',
           'PUBLISHED', '12500000-0000-4000-8000-000000000099', now(), now(),
           'REVIEWED', 'PRACTICE', 'BREATHING_PACER', 'REPEATABLE', 'TIMED', true)
         RETURNING id`,
      );

      const first = await repository.save(owner, resource.rows[0].id, '2026-09-28', {
        status: 'IN_PROGRESS',
        completedActionIds: ['breath-1'],
      });
      const replay = await repository.save(owner, resource.rows[0].id, '2026-09-28', {
        status: 'IN_PROGRESS',
        completedActionIds: ['breath-1'],
      });
      expect(first?.version).toBe('0');
      expect(replay?.version).toBe('0');

      const completed = await repository.save(owner, resource.rows[0].id, '2026-09-28', {
        status: 'COMPLETED',
        completedActionIds: ['breath-1', 'breath-2'],
      });
      expect(completed).toMatchObject({ status: 'COMPLETED', version: '1' });
      expect(completed?.completedAt).not.toBeNull();

      const editedAfterCompletion = await repository.save(
        owner,
        resource.rows[0].id,
        '2026-09-28',
        {
          status: 'IN_PROGRESS',
          completedActionIds: ['breath-1'],
        },
      );
      expect(editedAfterCompletion).toMatchObject({
        status: 'COMPLETED',
        completedActionIds: ['breath-1'],
        completedAt: completed?.completedAt,
        version: '2',
      });

      const allTicksRemoved = await repository.save(owner, resource.rows[0].id, '2026-09-28', {
        status: 'IN_PROGRESS',
        completedActionIds: [],
      });
      expect(allTicksRemoved).toMatchObject({
        status: 'COMPLETED',
        completedActionIds: [],
        completedAt: completed?.completedAt,
        version: '3',
      });
      expect(await repository.list(otherOwner, '2026-09-22', '2026-09-28')).toEqual([]);
      expect(await repository.list(owner, '2026-09-22', '2026-09-28')).toHaveLength(1);
    });

    it('records multiple idempotent plan-bound practice sessions on one local day', async () => {
      const database = {
        query: (text: string, parameters?: unknown[]) => pool.query(text, parameters),
        withTransaction: async <T>(operation: (client: Pool) => Promise<T>) => operation(pool),
      } as unknown as DatabaseService;
      const repository = new ResourceProgressRepository(database);
      const owner = '12500000-0000-4000-8000-000000000011';
      const planId = '12500000-0000-4000-8000-000000000012';
      const resourceId = '00000000-0000-4000-8000-000000000201';
      const assignment = await pool.query<{ id: string }>(
        `INSERT INTO resource_daily_assignment
           (owner_id, local_date, time_zone, support_plan_id, support_plan_version, plan_tags)
         VALUES ($1, '2026-09-30', 'Asia/Ho_Chi_Minh', $2, 1,
           ARRAY['ANXIETY_SYMPTOMS'])
         RETURNING id`,
        [owner, planId],
      );
      await pool.query(
        `INSERT INTO resource_daily_assignment_item
           (assignment_id, ordinal, resource_id, selection_reason)
         VALUES ($1, 0, $2, 'PLAN_DOMAIN')`,
        [assignment.rows[0].id, resourceId],
      );
      const firstSessionId = '12500000-0000-4000-8000-000000000013';
      const secondSessionId = '12500000-0000-4000-8000-000000000014';
      const update = (practiceSessionId: string) =>
        repository.save(owner, resourceId, '2026-09-30', {
          status: 'COMPLETED',
          completedActionIds: ['pace'],
          practiceSessionId,
          practiceStartedAt: '2026-09-29T08:00:00Z',
          practiceDurationSeconds: 297,
        });

      await update(firstSessionId);
      await update(firstSessionId);
      await update(secondSessionId);

      const sessions = await pool.query<{
        id: string;
        support_plan_id: string;
        duration_seconds: number;
      }>(
        `SELECT id, support_plan_id, duration_seconds
         FROM resource_practice_session
         WHERE owner_id = $1 AND resource_id = $2
         ORDER BY id`,
        [owner, resourceId],
      );
      expect(sessions.rows).toHaveLength(2);
      expect(sessions.rows.every((row) => row.support_plan_id === planId)).toBe(true);
      expect(sessions.rows.every((row) => row.duration_seconds === 297)).toBe(true);
    });

    it('rejects progress for inactive or unknown resources', async () => {
      const database = {
        query: (text: string, parameters?: unknown[]) => pool.query(text, parameters),
        withTransaction: async <T>(operation: (client: Pool) => Promise<T>) => operation(pool),
      } as unknown as DatabaseService;
      const repository = new ResourceProgressRepository(database);
      expect(
        await repository.save(
          '12600000-0000-4000-8000-000000000001',
          '12600000-0000-4000-8000-000000000099',
          '2026-09-28',
          { status: 'IN_PROGRESS', completedActionIds: [] },
        ),
      ).toBeNull();
    });
  });

  describe('notification table', () => {
    it('migrates legacy rows without activating old safety notifications', async () => {
      const { rows } = await pool.query<{
        occurred_at: Date;
        delivery_state: string;
        expires_at: Date;
      }>(
        `SELECT occurred_at, delivery_state, expires_at
         FROM notification
         WHERE recipient_id = '90000000-0000-4000-8000-000000000002'`,
      );
      expect(rows[0].occurred_at).toBeInstanceOf(Date);
      expect(rows[0].expires_at).toBeInstanceOf(Date);
      expect(rows[0].delivery_state).toBe('CANCELLED');
    });

    it('rejects invalid priority', async () => {
      await expect(
        pool.query(
          `INSERT INTO notification
             (recipient_id, category, title, body, priority, occurred_at, expires_at,
              source, source_identity, request_fingerprint)
           VALUES ($1,$2,$3,$4,$5,now(),now() + interval '30 days','TEST','priority:invalid',$6)`,
          [
            'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
            'ASSESSMENT',
            'T',
            'B',
            'CRITICAL',
            'c'.repeat(64),
          ],
        ),
      ).rejects.toThrow(/violates check constraint/);
    });

    it('has ix_notification_recipient_unread index', async () => {
      const { rows } = await pool.query(
        `SELECT indexname FROM pg_indexes WHERE tablename='notification' AND indexname='ix_notification_recipient_unread'`,
      );
      expect(rows).toHaveLength(1);
    });

    it('deduplicates per recipient while allowing one source event to fan out', async () => {
      const database = {
        query: (text: string, parameters?: unknown[]) => pool.query(text, parameters),
        withTransaction: async <T>(operation: (client: Pool) => Promise<T>) => operation(pool),
      } as unknown as DatabaseService;
      const repository = new NotificationRepository(database);
      const service = new NotificationService(repository);
      const command = {
        ownerId: '11000000-0000-4000-8000-000000000001',
        kind: 'SYSTEM_RESOURCE',
        title: 'Tài nguyên mới',
        body: 'Một tài nguyên đã được cập nhật.',
        occurredAt: new Date().toISOString(),
        action: {
          type: 'OPEN_RESOURCE',
          targetId: '11000000-0000-4000-8000-000000000002',
        },
        source: 'CONTENT',
        sourceIdentity: 'resource:11000000-0000-4000-8000-000000000002:0',
        priority: 'NORMAL',
      };

      const [first, retry] = await Promise.all([service.create(command), service.create(command)]);
      expect(retry.id).toBe(first.id);
      expect(retry.action?.href).toBe('/resources/11000000-0000-4000-8000-000000000002');

      const secondOwnerCommand = {
        ...command,
        ownerId: '11000000-0000-4000-8000-000000000003',
      };
      const secondOwner = await service.create(secondOwnerCommand);
      const secondOwnerRetry = await service.create(secondOwnerCommand);
      expect(secondOwner.id).not.toBe(first.id);
      expect(secondOwnerRetry.id).toBe(secondOwner.id);

      await expect(service.create({ ...command, title: 'Changed retry' })).rejects.toBeInstanceOf(
        NotificationDedupeConflictError,
      );
      const count = await pool.query<{ count: string }>(
        `SELECT count(*)::text AS count FROM notification
         WHERE source = 'CONTENT' AND source_identity = $1`,
        [command.sourceIdentity],
      );
      expect(count.rows[0].count).toBe('2');
    });

    it('paginates newest first with an opaque cursor and isolates owners', async () => {
      const database = {
        query: (text: string, parameters?: unknown[]) => pool.query(text, parameters),
        withTransaction: async <T>(operation: (client: Pool) => Promise<T>) => operation(pool),
      } as unknown as DatabaseService;
      const repository = new NotificationRepository(database);
      const owner = '12000000-0000-4000-8000-000000000001';
      for (const [index, occurredAt] of [
        '2026-09-26T01:00:00.000Z',
        '2026-09-26T02:00:00.000Z',
        '2026-09-26T03:00:00.000Z',
      ].entries()) {
        await pool.query(
          `INSERT INTO notification
             (recipient_id, category, title, body, occurred_at, source, source_identity,
              request_fingerprint, expires_at, created_at)
           VALUES ($1, 'REMINDER', $2, 'Safe copy', $3, 'TEST', $4, $5,
             now() + interval '30 days', $3)`,
          [owner, `Reminder ${index}`, occurredAt, `page:${index}`, `${index}`.padStart(64, '0')],
        );
      }
      await pool.query(
        `INSERT INTO notification
           (recipient_id, category, title, body, occurred_at, source, source_identity,
            request_fingerprint, expires_at)
         VALUES ('12000000-0000-4000-8000-000000000099', 'MESSAGE', 'Other owner',
           'Safe copy', now(), 'TEST', 'other-owner', $1, now() + interval '30 days')`,
        ['f'.repeat(64)],
      );

      const first = await repository.list(owner, 2, null);
      expect(first.items.map((item) => item.title)).toEqual(['Reminder 2', 'Reminder 1']);
      expect(first.hasMore).toBe(true);
      expect(first.nextCursor).toBeTruthy();
      expect(first.unreadCount).toBe(3);

      const second = await new NotificationService(repository).list(owner, '2', first.nextCursor!);
      expect(second.items.map((item) => item.title)).toEqual(['Reminder 0']);
      expect(second.hasMore).toBe(false);
    });

    it('keeps one exact read time under races, enforces ownership, and excludes expired/deleted rows', async () => {
      const database = {
        query: (text: string, parameters?: unknown[]) => pool.query(text, parameters),
        withTransaction: async <T>(operation: (client: Pool) => Promise<T>) => operation(pool),
      } as unknown as DatabaseService;
      const repository = new NotificationRepository(database);
      const owner = '13000000-0000-4000-8000-000000000001';
      const otherOwner = '13000000-0000-4000-8000-000000000002';
      const active = await pool.query<{ id: string }>(
        `INSERT INTO notification
           (recipient_id, category, title, body, occurred_at, source, source_identity,
            request_fingerprint, expires_at)
         VALUES ($1, 'MESSAGE', 'New message', 'You have a new message.', now(),
           'REALTIME', 'message:1', $2, now() + interval '30 days') RETURNING id`,
        [owner, 'a'.repeat(64)],
      );
      await pool.query(
        `INSERT INTO notification
           (recipient_id, category, title, body, occurred_at, source, source_identity,
            request_fingerprint, created_at, expires_at)
         VALUES ($1, 'REMINDER', 'Expired', 'Old reminder', now() - interval '91 days',
           'TEST', 'expired:1', $2, now() - interval '91 days', now() - interval '1 day')`,
        [owner, 'b'.repeat(64)],
      );

      const [left, right] = await Promise.all([
        repository.markRead(owner, active.rows[0].id),
        repository.markRead(owner, active.rows[0].id),
      ]);
      expect(left.outcome).toBe('UPDATED');
      expect(right.outcome).toBe('UPDATED');
      if (left.outcome === 'UPDATED' && right.outcome === 'UPDATED') {
        expect(right.item.readAt).toBe(left.item.readAt);
      }
      expect((await repository.markRead(otherOwner, active.rows[0].id)).outcome).toBe('NOT_FOUND');

      const page = await repository.list(owner, 20, null);
      expect(page.items.map((item) => item.title)).toEqual(['New message']);
      const expired = await pool.query<{ deleted_at: Date | null }>(
        `SELECT deleted_at FROM notification WHERE source_identity = 'expired:1'`,
      );
      expect(expired.rows[0].deleted_at).not.toBeNull();

      expect(await repository.delete(owner, active.rows[0].id)).toBe('DELETED');
      expect(await repository.delete(owner, active.rows[0].id)).toBe('DELETED');
      expect((await repository.list(owner, 20, null)).items).toEqual([]);
    });
  });
});
