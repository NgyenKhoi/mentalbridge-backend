import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { Pool } from 'pg';
import { GenericContainer, type StartedTestContainer } from 'testcontainers';
import { readFileSync } from 'fs';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';
import { randomUUID } from 'node:crypto';
import type { PlanResourceActions } from '../../resources/resource-engagement.types.js';
import type { DatabaseClient, DatabaseService } from '../../database/database.service.js';
import { AppointmentReminderRepository } from '../../appointment-reminders/appointment-reminder.repository.js';
import {
  SpecialistAppointmentNotificationDedupeConflictError,
  SpecialistAppointmentNotificationRepository,
} from '../../appointment-reminders/specialist-appointment-notification.repository.js';
import type { AppointmentStatusChangedEventV2 } from '../../appointment-reminders/appointment-reminder.types.js';
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
import {
  ResourceBingoVersionMismatchError,
  ResourceBingoItemRetiredError,
  ResourceEngagementRepository,
} from '../../resources/resource-engagement.repository.js';
import { ResourceRewardRepository } from '../../resources/resource-reward.repository.js';
import { calculateResourceRewards } from '../../resources/resource-reward.policy.js';
import {
  AchievementPreferenceVersionMismatchError,
  AchievementRepository,
} from '../../achievements/achievement.repository.js';
import { calculateAchievements } from '../../achievements/achievement.policy.js';
import { WellbeingDigestRepository } from '../../wellbeing-digest/wellbeing-digest.repository.js';
import {
  CommunityNotificationDedupeConflictError,
  CommunityNotificationRepository,
} from '../../community-notifications/community-notification.repository.js';
import {
  UserNotificationDedupeConflictError,
  UserNotificationRepository,
} from '../../user-notifications/user-notification.repository.js';
import { PushDeviceRepository } from '../../push-delivery/push-device.repository.js';

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
      '18_add_community_interaction_notifications.sql',
      '19_complete_resource_editorial_lifecycle.sql',
      '20_add_resource_reward_visibility.sql',
      '21_persist_resource_engagement.sql',
      '22_add_private_achievement_preference.sql',
      '24_add_specialist_notification_preferences.sql',
      '25_add_general_user_notification_producers.sql',
      '26_add_community_notice_delivery.sql',
      '27_plan_scoped_resource_bingo.sql',
      '28_add_native_push_delivery.sql',
      '29_harden_native_push_delivery.sql',
      '30_add_appointment_push_reminders.sql',
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
      if (migration === '21_persist_resource_engagement.sql') {
        const reviewer = '66000000-0000-4000-8000-000000000990';
        for (const [resourceId, title, status] of [
          ['66000000-0000-4000-8000-000000000201', 'Legacy draft', 'DRAFT'],
          ['66000000-0000-4000-8000-000000000202', 'Later publication', 'PUBLISHED'],
          ['66000000-0000-4000-8000-000000000203', 'Unknown provenance', 'DRAFT'],
        ]) {
          await pool.query(
            `INSERT INTO resource (
               id, category, locale, title, summary, content_body, status, reviewed_by,
               reviewed_at, effective_at, version, source_organization, source_title,
               source_url, source_review_note, catalogue_visibility, resource_kind,
               interaction_type, repeatability, completion_mode, streak_eligible,
               expected_duration_minutes, cooldown_days, recommended_frequency_per_week,
               plan_tags, structured_content, interaction_config, safety_notes,
               content_version_label, source_review_status
             ) VALUES (
               $1, 'BREATHING', 'vi-VN', $2, 'Legacy summary', 'Legacy body', $3,
               $4, '2026-08-31T00:00:00Z', '2026-09-01T00:00:00Z', 1,
               'MentalBridge fixture', 'Legacy migration fixture',
               'https://example.com/legacy', 'Migration regression', 'DIRECT_ONLY',
               'PRACTICE', 'BREATHING_PACER', 'REPEATABLE', 'TIMED', true, 5, 0, 4,
               ARRAY['ANXIETY_SYMPTOMS'], jsonb_build_object('overview', 'Legacy summary'),
               '{}'::jsonb, ARRAY['Stop if uncomfortable'], 'legacy-v1', 'REVIEWED'
             )`,
            [resourceId, title, status, reviewer],
          );
        }
        await pool.query(
          `INSERT INTO resource_publication_snapshot
             (resource_id, content_version, snapshot, published_by, published_at)
           VALUES
             ('66000000-0000-4000-8000-000000000201', 0,
               '{"title":"Legacy draft v0"}'::jsonb, $1, '2026-09-01T00:00:00Z'),
             ('66000000-0000-4000-8000-000000000202', 0,
               '{"title":"Later publication v0"}'::jsonb, $1, '2026-09-01T00:00:00Z'),
             ('66000000-0000-4000-8000-000000000202', 1,
               '{"title":"Later publication v1"}'::jsonb, $1, '2026-10-10T00:00:00Z')`,
          [reviewer],
        );
        const legacyBoard = await pool.query<{ id: string }>(
          `INSERT INTO resource_weekly_bingo
             (owner_id, week_start, time_zone, support_plan_id, support_plan_version,
              plan_tags, created_at)
           VALUES (
             '66000000-0000-4000-8000-000000000299', '2026-10-05',
             'Asia/Ho_Chi_Minh', '66000000-0000-4000-8000-000000000298', 1,
             ARRAY['ANXIETY_SYMPTOMS'], '2026-10-05T00:00:00Z')
           RETURNING id`,
        );
        await pool.query(
          `INSERT INTO resource_weekly_bingo_item (board_id, ordinal, resource_id)
           VALUES
             ($1, 0, '66000000-0000-4000-8000-000000000201'),
             ($1, 1, '66000000-0000-4000-8000-000000000202'),
             ($1, 2, '66000000-0000-4000-8000-000000000203')`,
          [legacyBoard.rows[0].id],
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

  describe('native push device lifecycle', () => {
    const database = (): DatabaseService =>
      ({
        query: (text: string, parameters?: never[]) => pool.query(text, parameters),
        withTransaction: async <T>(operation: (client: DatabaseClient) => Promise<T>) => {
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
      }) as DatabaseService;

    it('rotates one provider token to its latest owner and revokes idempotently', async () => {
      const repository = new PushDeviceRepository(database());
      const token = {
        ciphertext: 'encrypted-token',
        iv: 'synthetic-iv',
        authTag: 'synthetic-tag',
        hash: 'a'.repeat(64),
      };
      const firstOwner = randomUUID();
      const secondOwner = randomUUID();
      await repository.register(
        firstOwner,
        {
          installationId: randomUUID(),
          platform: 'ANDROID',
          provider: 'EXPO',
          providerToken: 'not-persisted-by-repository',
          permissionStatus: 'GRANTED',
        },
        token,
      );
      const secondInstallation = randomUUID();
      await repository.register(
        secondOwner,
        {
          installationId: secondInstallation,
          platform: 'IOS',
          provider: 'EXPO',
          providerToken: 'not-persisted-by-repository',
          permissionStatus: 'GRANTED',
        },
        token,
      );

      const states = await pool.query<{ owner_id: string; status: string }>(
        `SELECT owner_id::text, status FROM push_device_registration
         WHERE provider_token_hash = $1 ORDER BY created_at`,
        [token.hash],
      );
      expect(states.rows).toEqual([
        { owner_id: firstOwner, status: 'REVOKED' },
        { owner_id: secondOwner, status: 'ACTIVE' },
      ]);
      await new NotificationPreferenceRepository(database()).getOrCreate(secondOwner, 'USER');
      await pool.query(
        'UPDATE notification_preference SET channel_push_enabled = true WHERE user_id = $1',
        [secondOwner],
      );
      const notificationId = randomUUID();
      await pool.query(
        `INSERT INTO notification
           (id, recipient_id, category, title, body, priority, expires_at, occurred_at,
            source, source_identity, request_fingerprint, delivery_state, version)
         VALUES ($1, $2, 'SYSTEM_RESOURCE', 'MentalBridge', 'Generic inbox copy', 'NORMAL',
                 now() + interval '1 day', now(), 'CONTENT', $3, $4, 'DELIVERED', 0)`,
        [notificationId, secondOwner, `push-test:${notificationId}`, 'b'.repeat(64)],
      );
      await expect(repository.materializeCandidates(10)).resolves.toBe(1);
      expect(
        (
          await pool.query<{ state: string }>(
            'SELECT state FROM push_delivery_attempt WHERE notification_id = $1',
            [notificationId],
          )
        ).rows[0].state,
      ).toBe('PENDING');
      await expect(repository.revoke(secondOwner, secondInstallation)).resolves.toBe(true);
      await repository.settleIneligible();
      expect(
        (
          await pool.query<{ state: string; failure_code: string }>(
            'SELECT state, failure_code FROM push_delivery_attempt WHERE notification_id = $1',
            [notificationId],
          )
        ).rows[0],
      ).toEqual({ state: 'SUPPRESSED', failure_code: 'DEVICE_DISABLED' });
      await expect(repository.revoke(secondOwner, secondInstallation)).resolves.toBe(true);
      await expect(repository.revoke(firstOwner, randomUUID())).resolves.toBe(false);
    });

    it('revokes the previous owner when one installation is reassigned with a new token', async () => {
      const repository = new PushDeviceRepository(database());
      const installationId = randomUUID();
      const firstOwner = randomUUID();
      const secondOwner = randomUUID();
      await repository.register(
        firstOwner,
        {
          installationId,
          platform: 'ANDROID',
          provider: 'EXPO',
          providerToken: 'not-persisted-by-repository',
          permissionStatus: 'GRANTED',
        },
        { ciphertext: 'first', iv: 'first-iv', authTag: 'first-tag', hash: 'c'.repeat(64) },
      );
      await repository.register(
        secondOwner,
        {
          installationId,
          platform: 'ANDROID',
          provider: 'EXPO',
          providerToken: 'not-persisted-by-repository',
          permissionStatus: 'GRANTED',
        },
        { ciphertext: 'second', iv: 'second-iv', authTag: 'second-tag', hash: 'd'.repeat(64) },
      );

      const states = await pool.query<{ owner_id: string; status: string }>(
        `SELECT owner_id::text, status
         FROM push_device_registration
         WHERE installation_id = $1
         ORDER BY created_at`,
        [installationId],
      );
      expect(states.rows).toEqual([
        { owner_id: firstOwner, status: 'REVOKED' },
        { owner_id: secondOwner, status: 'ACTIVE' },
      ]);
    });

    it('keeps an identical PUT timestamp stable and does not skip a pending notification', async () => {
      const repository = new PushDeviceRepository(database());
      const owner = randomUUID();
      const installationId = randomUUID();
      const token = {
        ciphertext: 'encrypted-token',
        iv: 'synthetic-iv',
        authTag: 'synthetic-tag',
        hash: 'e'.repeat(64),
      };
      const input = {
        installationId,
        platform: 'ANDROID' as const,
        provider: 'EXPO' as const,
        providerToken: 'not-persisted-by-repository',
        permissionStatus: 'GRANTED' as const,
        appVersion: '1.0.0',
      };
      const first = await repository.register(owner, input, token);
      await new NotificationPreferenceRepository(database()).getOrCreate(owner, 'USER');
      await pool.query(
        'UPDATE notification_preference SET channel_push_enabled = true WHERE user_id = $1',
        [owner],
      );
      const notificationId = randomUUID();
      await pool.query(
        `INSERT INTO notification
           (id, recipient_id, category, title, body, priority, expires_at, occurred_at,
            source, source_identity, request_fingerprint, delivery_state, version)
         VALUES ($1, $2, 'SYSTEM_RESOURCE', 'MentalBridge', 'Generic inbox copy', 'NORMAL',
                 now() + interval '1 day', now(), 'CONTENT', $3, $4, 'DELIVERED', 0)`,
        [notificationId, owner, `push-noop:${notificationId}`, 'f'.repeat(64)],
      );

      const repeated = await repository.register(owner, input, {
        ...token,
        ciphertext: 'randomized-ciphertext-that-must-not-be-written',
      });

      expect(repeated.lastRegisteredAt).toBe(first.lastRegisteredAt);
      expect(repeated.version).toBe(first.version);
      await expect(repository.materializeCandidates(10)).resolves.toBe(1);
      const persisted = await pool.query<{ provider_token_ciphertext: string }>(
        `SELECT provider_token_ciphertext
         FROM push_device_registration
         WHERE owner_id = $1 AND installation_id = $2`,
        [owner, installationId],
      );
      expect(persisted.rows[0].provider_token_ciphertext).toBe('encrypted-token');
    });

    it('does not claim expired or deleted notifications for provider delivery', async () => {
      const repository = new PushDeviceRepository(database());
      const owner = randomUUID();
      const installationId = randomUUID();
      await repository.register(
        owner,
        {
          installationId,
          platform: 'ANDROID',
          provider: 'EXPO',
          providerToken: 'not-persisted-by-repository',
          permissionStatus: 'GRANTED',
        },
        { ciphertext: 'token', iv: 'iv', authTag: 'tag', hash: '1'.repeat(64) },
      );
      await new NotificationPreferenceRepository(database()).getOrCreate(owner, 'USER');
      await pool.query(
        'UPDATE notification_preference SET channel_push_enabled = true WHERE user_id = $1',
        [owner],
      );
      const notificationId = randomUUID();
      await pool.query(
        `INSERT INTO notification
           (id, recipient_id, category, title, body, priority, expires_at, occurred_at,
            source, source_identity, request_fingerprint, delivery_state, version)
         VALUES ($1, $2, 'SYSTEM_RESOURCE', 'MentalBridge', 'Generic inbox copy', 'NORMAL',
                 now() + interval '1 day', now(), 'CONTENT', $3, $4, 'DELIVERED', 0)`,
        [notificationId, owner, `push-lifecycle:${notificationId}`, '2'.repeat(64)],
      );
      await repository.materializeCandidates(10);
      await pool.query('UPDATE notification SET deleted_at = now() WHERE id = $1', [
        notificationId,
      ]);

      const claims = await repository.claim(10);
      expect(claims.some((claim) => claim.notificationId === notificationId)).toBe(false);
      await repository.settleIneligible();
      const attempt = await pool.query<{ state: string; failure_code: string }>(
        'SELECT state, failure_code FROM push_delivery_attempt WHERE notification_id = $1',
        [notificationId],
      );
      expect(attempt.rows[0]).toEqual({
        state: 'SUPPRESSED',
        failure_code: 'NOTIFICATION_DELETED',
      });

      const expiredNotificationId = randomUUID();
      await pool.query(
        `INSERT INTO notification
           (id, recipient_id, category, title, body, priority, expires_at, occurred_at,
            source, source_identity, request_fingerprint, delivery_state, version, created_at)
         VALUES ($1, $2, 'SYSTEM_RESOURCE', 'MentalBridge', 'Generic inbox copy', 'NORMAL',
                 now() - interval '1 hour', now() - interval '2 hours', 'CONTENT', $3, $4,
                 'DELIVERED', 0, now() - interval '2 hours')`,
        [expiredNotificationId, owner, `push-expired:${expiredNotificationId}`, '5'.repeat(64)],
      );
      await pool.query(
        `INSERT INTO push_delivery_attempt (notification_id, device_registration_id)
         SELECT $1, id
         FROM push_device_registration
         WHERE owner_id = $2 AND installation_id = $3`,
        [expiredNotificationId, owner, installationId],
      );

      const expiredClaims = await repository.claim(10);
      expect(expiredClaims.some((claim) => claim.notificationId === expiredNotificationId)).toBe(
        false,
      );
      await repository.settleIneligible();
      const expiredAttempt = await pool.query<{ state: string; failure_code: string }>(
        'SELECT state, failure_code FROM push_delivery_attempt WHERE notification_id = $1',
        [expiredNotificationId],
      );
      expect(expiredAttempt.rows[0]).toEqual({
        state: 'SUPPRESSED',
        failure_code: 'NOTIFICATION_EXPIRED',
      });
    });

    it('enforces the configured owner command rate limit atomically', async () => {
      const repository = new PushDeviceRepository(database());
      const owner = randomUUID();

      await expect(repository.consumeCommandRateLimit(owner, 2, 60)).resolves.toBe(true);
      await expect(repository.consumeCommandRateLimit(owner, 2, 60)).resolves.toBe(true);
      await expect(repository.consumeCommandRateLimit(owner, 2, 60)).resolves.toBe(false);
    });

    it('persists delayed Expo receipt confirmation separately from ticket acceptance', async () => {
      const repository = new PushDeviceRepository(database());
      const owner = randomUUID();
      const installationId = randomUUID();
      await repository.register(
        owner,
        {
          installationId,
          platform: 'ANDROID',
          provider: 'EXPO',
          providerToken: 'not-persisted-by-repository',
          permissionStatus: 'GRANTED',
        },
        {
          ciphertext: 'receipt-token',
          iv: 'receipt-iv',
          authTag: 'receipt-tag',
          hash: '3'.repeat(64),
        },
      );
      await new NotificationPreferenceRepository(database()).getOrCreate(owner, 'USER');
      await pool.query(
        'UPDATE notification_preference SET channel_push_enabled = true WHERE user_id = $1',
        [owner],
      );
      const notificationId = randomUUID();
      await pool.query(
        `INSERT INTO notification
           (id, recipient_id, category, title, body, priority, expires_at, occurred_at,
            source, source_identity, request_fingerprint, delivery_state, version)
         VALUES ($1, $2, 'SYSTEM_RESOURCE', 'MentalBridge', 'Generic inbox copy', 'NORMAL',
                 now() + interval '1 day', now(), 'CONTENT', $3, $4, 'DELIVERED', 0)`,
        [notificationId, owner, `push-receipt:${notificationId}`, '4'.repeat(64)],
      );
      await repository.materializeCandidates(100);
      const claims = await repository.claim(100);
      const claim = claims.find((candidate) => candidate.notificationId === notificationId);
      expect(claim).toBeDefined();
      await repository.accepted(claim!.id, 'ticket-receipt-1', 0);

      const receiptClaims = await repository.claimReceipts(100, 60);
      const receiptClaim = receiptClaims.find(
        (candidate) => candidate.providerTicketId === 'ticket-receipt-1',
      );
      expect(receiptClaim).toBeDefined();
      await repository.confirmReceipt(receiptClaim!.id);
      const attempt = await pool.query<{
        state: string;
        receipt_attempt_count: number;
        receipt_checked_at: Date;
      }>(
        `SELECT state, receipt_attempt_count, receipt_checked_at
         FROM push_delivery_attempt
         WHERE notification_id = $1`,
        [notificationId],
      );
      expect(attempt.rows[0].state).toBe('CONFIRMED');
      expect(attempt.rows[0].receipt_attempt_count).toBe(1);
      expect(attempt.rows[0].receipt_checked_at).toBeInstanceOf(Date);
    });
  });

  describe('resource engagement migration', () => {
    it('binds legacy items to publications available at board creation and drops unknown provenance', async () => {
      const result = await pool.query<{
        resource_id: string;
        resource_version: string;
      }>(
        `SELECT item.resource_id::text, item.resource_version::text
         FROM resource_weekly_bingo board
         JOIN resource_weekly_bingo_item item ON item.board_id = board.id
         WHERE board.owner_id = '66000000-0000-4000-8000-000000000299'
           AND board.week_start = '2026-10-05'
         ORDER BY item.ordinal`,
      );
      expect(result.rows).toEqual([
        {
          resource_id: '66000000-0000-4000-8000-000000000201',
          resource_version: '0',
        },
        {
          resource_id: '66000000-0000-4000-8000-000000000202',
          resource_version: '0',
        },
      ]);
      const board = await pool.query<{ count: string }>(
        `SELECT count(*)::text AS count
         FROM resource_weekly_bingo
         WHERE owner_id = '66000000-0000-4000-8000-000000000299'
           AND week_start = '2026-10-05'`,
      );
      expect(board.rows[0].count).toBe('1');
    });
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

    it('materializes seven same-week dates without creating Bingo boards', async () => {
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
      expect(boardCount.rows[0].count).toBe('0');
      expect(journeys.every((journey) => journey?.bingo.length === 0)).toBe(true);
    });

    it('persists owner-scoped bookmarks and reconciles versioned Bingo evidence', async () => {
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
      const repository = new ResourceEngagementRepository(database);
      const ownerA = '66000000-0000-4000-8000-000000000001';
      const ownerB = '66000000-0000-4000-8000-000000000002';
      const planId = '66000000-0000-4000-8000-000000000099';
      const resourceA = '66000000-0000-4000-8000-000000000101';
      const resourceB = '66000000-0000-4000-8000-000000000102';
      const reviewer = '66000000-0000-4000-8000-000000000900';

      await pool.query(
        `INSERT INTO resource_weekly_bingo
           (owner_id, week_start, time_zone, support_plan_id, support_plan_version, plan_tags)
         VALUES ($1, '2026-10-19', 'Asia/Ho_Chi_Minh', $2, 3,
           ARRAY['ANXIETY_SYMPTOMS'])`,
        [ownerB, planId],
      );
      await expect(repository.readBingo(ownerB, '2026-10-19')).resolves.toMatchObject({
        weekStart: '2026-10-19',
        timeZone: 'Asia/Ho_Chi_Minh',
        version: '0',
        items: [],
      });
      await expect(repository.readBingo(ownerB, '2026-10-26')).resolves.toBeNull();

      for (const [resourceId, title] of [
        [resourceA, 'Nhịp thở dịu lại'],
        [resourceB, 'Một phút thả lỏng'],
      ]) {
        await pool.query(
          `INSERT INTO resource (
             id, category, locale, title, summary, content_body, status, reviewed_by,
             reviewed_at, effective_at, source_organization, source_title, source_url,
             source_review_note, catalogue_visibility, resource_kind, interaction_type,
             repeatability, completion_mode, streak_eligible, expected_duration_minutes,
             cooldown_days, recommended_frequency_per_week, plan_tags, structured_content,
             interaction_config, safety_notes, content_version_label, source_review_status
           ) VALUES (
             $1, 'BREATHING', 'vi-VN', $2, 'Tóm tắt đã rà soát', 'Nội dung đã rà soát',
             'PUBLISHED', $3, now(), now() - interval '1 day', 'MentalBridge fixture',
             'Reviewed fixture', 'https://example.com/resource', 'Integration evidence',
             'LISTED', 'PRACTICE', 'BREATHING_PACER', 'REPEATABLE', 'TIMED', true,
             5, 0, 4, ARRAY['ANXIETY_SYMPTOMS'],
             jsonb_build_object('overview', 'Tóm tắt đã rà soát'), '{}'::jsonb,
             ARRAY['Dừng lại nếu không thoải mái'], 'mb-660-v0', 'REVIEWED'
           )`,
          [resourceId, title, reviewer],
        );
        await pool.query(
          `INSERT INTO resource_publication_snapshot
             (resource_id, content_version, snapshot, published_by)
           SELECT id, version, to_jsonb(resource), $2 FROM resource WHERE id = $1`,
          [resourceId, reviewer],
        );
      }

      const boardId = (
        await pool.query<{ id: string }>(
          `INSERT INTO resource_weekly_bingo
             (owner_id, week_start, time_zone, support_plan_id, support_plan_version, plan_tags)
           VALUES ($1, '2026-10-12', 'Asia/Ho_Chi_Minh', $2, 3,
             ARRAY['ANXIETY_SYMPTOMS'])
           RETURNING id`,
          [ownerA, planId],
        )
      ).rows[0].id;
      await pool.query(
        `INSERT INTO resource_weekly_bingo_item
           (board_id, ordinal, resource_id, resource_version)
         VALUES ($1, 0, $2, 0), ($1, 1, $3, 0)`,
        [boardId, resourceA, resourceB],
      );

      const saved = await repository.saveBookmark(ownerA, resourceA);
      const identicalRetry = await repository.saveBookmark(ownerA, resourceA);
      expect(saved).toMatchObject({ resourceId: resourceA, contentVersion: '0', version: '0' });
      expect(identicalRetry).toEqual(saved);
      expect(await repository.listBookmarks(ownerB)).toEqual([]);

      const concurrent = await Promise.allSettled([
        repository.setBingoSelfConfirmed(ownerA, '2026-10-12', 1, '0', true),
        repository.setBingoSelfConfirmed(ownerA, '2026-10-12', 2, '0', true),
      ]);
      expect(concurrent.filter(({ status }) => status === 'fulfilled')).toHaveLength(1);
      const rejected = concurrent.find(({ status }) => status === 'rejected');
      expect(rejected).toMatchObject({
        status: 'rejected',
        reason: expect.any(ResourceBingoVersionMismatchError),
      });

      let current = await repository.readBingo(ownerA, '2026-10-12');
      expect(current?.items.filter((item) => item.evidence === 'SELF_CONFIRMED')).toHaveLength(1);
      expect(current?.version).toBe('1');
      const confirmedPosition = current?.items.find((item) => item.selfConfirmedAt)?.position;
      expect(confirmedPosition).toBeDefined();
      const idempotentRetry = await repository.setBingoSelfConfirmed(
        ownerA,
        '2026-10-12',
        confirmedPosition!,
        '0',
        true,
      );
      expect(idempotentRetry?.version).toBe('1');
      current = await repository.setBingoSelfConfirmed(
        ownerA,
        '2026-10-12',
        confirmedPosition!,
        current!.version,
        false,
      );
      expect(current?.items.every((item) => item.evidence === 'NONE')).toBe(true);

      const assignmentId = (
        await pool.query<{ id: string }>(
          `INSERT INTO resource_daily_assignment
             (owner_id, local_date, time_zone, support_plan_id, support_plan_version, plan_tags)
           VALUES ($1, '2026-10-12', 'Asia/Ho_Chi_Minh', $2, 3,
             ARRAY['ANXIETY_SYMPTOMS']) RETURNING id`,
          [ownerA, planId],
        )
      ).rows[0].id;
      await pool.query(
        `INSERT INTO resource_daily_assignment_item
           (assignment_id, ordinal, resource_id, selection_reason)
         VALUES ($1, 0, $2, 'PLAN_SELECTED')`,
        [assignmentId, resourceA],
      );
      await pool.query(
        `INSERT INTO resource_daily_progress
           (owner_id, resource_id, local_date, resource_version, status, completed_at)
         VALUES ($1, $2, '2026-10-12', 0, 'COMPLETED', now())`,
        [ownerA, resourceA],
      );
      current = await repository.readBingo(ownerA, '2026-10-12');
      expect(current?.items[0]).toMatchObject({
        evidence: 'CONTENT_VERIFIED',
        verificationSource: 'RESOURCE_COMPLETION',
      });

      await pool.query(
        `UPDATE resource SET version = 1, content_version_label = 'mb-660-v1' WHERE id = $1`,
        [resourceA],
      );
      await pool.query(
        `INSERT INTO resource_publication_snapshot
           (resource_id, content_version, snapshot, published_by)
         SELECT id, version, to_jsonb(resource), $2 FROM resource WHERE id = $1`,
        [resourceA, reviewer],
      );
      await pool.query(
        `UPDATE resource_daily_progress SET resource_version = 1 WHERE owner_id = $1 AND resource_id = $2`,
        [ownerA, resourceA],
      );
      current = await repository.readBingo(ownerA, '2026-10-12');
      expect(current?.items[0].evidence).toBe('NONE');

      const refreshed = await repository.saveBookmark(ownerA, resourceA);
      expect(refreshed).toMatchObject({ resourceId: resourceA, contentVersion: '1', version: '1' });
      await repository.deleteBookmark(ownerA, resourceA);
      expect(await repository.listBookmarks(ownerA)).toEqual([]);
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

      await repository.createDefaults(owner, 'USER');
      await repository.createDefaults(owner, 'USER');

      const { rows } = await pool.query(
        `SELECT count(*)::int AS aggregate_count,
                bool_and(notifications_enabled AND channel_in_app_enabled) AS defaults_enabled
         FROM notification_preference
         WHERE user_id = $1`,
        [owner],
      );
      expect(rows[0]).toEqual({ aggregate_count: 1, defaults_enabled: true });
    });

    it('initializes specialist defaults without user wellbeing categories', async () => {
      const database = {
        query: (text: string, parameters?: unknown[]) => pool.query(text, parameters),
      } as unknown as DatabaseService;
      const repository = new NotificationPreferenceRepository(database);
      const owner = 'a2eebc99-9c0b-4ef8-bb6d-6bb9bd380a11';

      await repository.createDefaults(owner, 'SPECIALIST');
      const { rows } = await pool.query(
        `SELECT owner_type, group_journal_reminder_enabled,
                group_emotion_check_in_enabled, group_appointment_message_enabled
         FROM notification_preference WHERE user_id=$1`,
        [owner],
      );
      expect(rows[0]).toEqual({
        owner_type: 'SPECIALIST',
        group_journal_reminder_enabled: false,
        group_emotion_check_in_enabled: false,
        group_appointment_message_enabled: true,
      });
      await expect(
        pool.query(
          `UPDATE notification_preference
           SET group_journal_reminder_enabled=true WHERE user_id=$1`,
          [owner],
        ),
      ).rejects.toThrow(/ck_specialist_notification_preference_scope/);
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

      const initial = await firstDevice.getOrCreate(owner, 'USER');
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

      const reloaded = await secondDevice.getOrCreate(owner, 'USER');
      expect(reloaded).toEqual(saved);
      expect(reloaded.version).toBe(1);
      expect(reloaded.quietHours.timeZone).toBe('America/New_York');

      const otherOwner = await secondDevice.getOrCreate(
        'f0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
        'USER',
      );
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
      status: AppointmentStatusChangedEventV2['payload']['status'],
      replacementId: string | null = null,
    ): AppointmentStatusChangedEventV2 {
      return {
        messageId: '24000000-0000-4000-8000-000000000001',
        messageType: 'consultation.appointment.status-changed',
        occurredAt: '2029-01-01T00:00:00.000Z',
        producer: 'consultation-service',
        schemaVersion: '2.0',
        correlationId: '24000000-0000-4000-8000-000000000002',
        aggregateId: appointmentId,
        aggregateVersion: version,
        payload: {
          appointmentId,
          ownerAccountId: '24000000-0000-4000-8000-000000000003',
          specialistAccountId: '24000000-0000-4000-8000-000000000004',
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
      const pushReminders = await pool.query(
        'SELECT state FROM appointment_push_reminder WHERE appointment_id=$1',
        [appointmentId],
      );
      expect(pushReminders.rows.every((row) => row.state === 'INVALIDATED')).toBe(true);
    });

    it('deduplicates minimized specialist appointment notifications and honors preferences', async () => {
      const database = {
        query: (sql: string, parameters?: unknown[]) => pool.query(sql, parameters),
        withTransaction: async <T>(operation: (client: DatabaseClient) => Promise<T>) =>
          operation(pool as unknown as DatabaseClient),
      } as unknown as DatabaseService;
      const repository = new SpecialistAppointmentNotificationRepository(database);
      const appointmentId = '24000000-0000-4000-8000-000000000020';
      const first = statusEvent(appointmentId, 1, 'REQUESTED');

      await repository.materialize(first);
      await repository.materialize(first);
      await expect(
        repository.materialize({
          ...first,
          payload: { ...first.payload, status: 'CANCELLED' },
        }),
      ).rejects.toBeInstanceOf(SpecialistAppointmentNotificationDedupeConflictError);
      await pool.query(
        `UPDATE notification_preference
         SET group_appointment_message_enabled=false
         WHERE user_id=$1`,
        [first.payload.specialistAccountId],
      );
      await repository.materialize(statusEvent(appointmentId, 2, 'CONFIRMED'));

      const { rows } = await pool.query(
        `SELECT category, body, action_type, delivery_state
         FROM notification
         WHERE recipient_id=$1 AND source='CONSULTATION_APPOINTMENT_V1'
         ORDER BY source_identity`,
        [first.payload.specialistAccountId],
      );
      expect(rows).toEqual([
        {
          category: 'APPOINTMENT',
          body: 'Một yêu cầu lịch hẹn mới đang chờ bạn xem xét.',
          action_type: 'OPEN_SPECIALIST_APPOINTMENTS',
          delivery_state: 'DELIVERED',
        },
        expect.objectContaining({ delivery_state: 'CANCELLED' }),
      ]);
      expect(JSON.stringify(rows)).not.toMatch(/journal|assessment|brief|chat body/i);
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
      const replacementPush = await pool.query(
        'SELECT count(*)::int AS count FROM appointment_push_reminder WHERE appointment_id=$1',
        [replacementId],
      );
      expect(replacementPush.rows[0].count).toBe(1);
      const claimed = await Promise.all([
        repository.claimDue(new Date('2030-01-02T02:00:00.000Z'), 100),
        repository.claimDue(new Date('2030-01-02T02:00:00.000Z'), 100),
      ]);
      expect(claimed.flat().filter((row) => row.appointmentId === replacementId)).toHaveLength(1);
      const claimedPush = await Promise.all([
        repository.claimPushDue(new Date('2030-01-02T02:00:00.000Z'), 100),
        repository.claimPushDue(new Date('2030-01-02T02:00:00.000Z'), 100),
      ]);
      expect(claimedPush.flat().filter((row) => row.appointmentId === replacementId)).toHaveLength(
        1,
      );
    });

    it('materializes one durable inbox fact and one attempt per active device', async () => {
      const repository = reminderRepository();
      const appointmentId = '24000000-0000-4000-8000-000000000030';
      const ownerId = '24000000-0000-4000-8000-000000000003';
      const observedAt = new Date('2030-01-01T00:00:00.000Z');
      const dueAt = new Date('2030-01-02T02:00:00.000Z');
      await new NotificationPreferenceRepository({
        query: (sql: string, parameters?: unknown[]) => pool.query(sql, parameters),
      } as DatabaseService).getOrCreate(ownerId, 'USER');
      await pool.query(
        `UPDATE notification_preference
         SET notifications_enabled=true, channel_push_enabled=true,
             group_appointment_message_enabled=true, quiet_hours_enabled=false
         WHERE user_id=$1`,
        [ownerId],
      );
      for (const suffix of ['31', '32']) {
        await pool.query(
          `INSERT INTO push_device_registration (
             owner_id, installation_id, platform, provider,
             provider_token_ciphertext, provider_token_iv, provider_token_auth_tag,
             provider_token_hash, permission_status, status, last_registered_at,
             delivery_eligible_from_at
           ) VALUES ($1,$2,'ANDROID','EXPO',$3,$4,$5,$6,'GRANTED','ACTIVE',now(),
                     now() - interval '1 minute')`,
          [
            ownerId,
            `24000000-0000-4000-8000-0000000000${suffix}`,
            `ciphertext-${suffix}`,
            `iv-${suffix}`,
            `tag-${suffix}`,
            suffix.repeat(32),
          ],
        );
      }

      await repository.apply(statusEvent(appointmentId, 1, 'CONFIRMED'), observedAt);
      const [claim] = await repository.claimPushDue(dueAt, 10);
      expect(claim).toEqual(expect.objectContaining({ appointmentId }));
      await expect(repository.materializePush(claim, dueAt)).resolves.toBe(true);
      await expect(repository.materializePush(claim, dueAt)).resolves.toBe(false);

      const notification = await pool.query<{
        id: string;
        title: string;
        body: string;
        action_type: string;
        action_target_id: string;
      }>(
        `SELECT id, title, body, action_type, action_target_id
         FROM notification
         WHERE recipient_id=$1 AND source='CONSULTATION_APPOINTMENT_REMINDER_V1'
           AND source_identity=$2`,
        [ownerId, `${appointmentId}:1`],
      );
      expect(notification.rows).toEqual([
        expect.objectContaining({
          title: 'Sắp đến giờ hẹn của bạn',
          body: 'Bạn có một lịch hẹn sắp bắt đầu trong MentalBridge.',
          action_type: 'OPEN_APPOINTMENT',
          action_target_id: appointmentId,
        }),
      ]);
      expect(JSON.stringify(notification.rows)).not.toMatch(/journal|assessment|symptom|diagnos/i);

      const database = {
        query: (sql: string, parameters?: unknown[]) => pool.query(sql, parameters),
        withTransaction: async <T>(operation: (client: DatabaseClient) => Promise<T>) =>
          operation(pool as unknown as DatabaseClient),
      } as DatabaseService;
      await new PushDeviceRepository(database).materializeCandidates(1000);
      const attempts = await pool.query<{ count: number }>(
        `SELECT count(*)::int AS count FROM push_delivery_attempt WHERE notification_id=$1`,
        [notification.rows[0].id],
      );
      expect(attempts.rows[0].count).toBe(2);
    });

    it('terminally fails a third deferred push claim instead of stranding it as PENDING', async () => {
      const repository = reminderRepository();
      const appointmentId = randomUUID();
      await repository.apply(
        statusEvent(appointmentId, 1, 'CONFIRMED'),
        new Date('2030-01-01T00:00:00.000Z'),
      );

      for (const [claimAt, retryAt] of [
        ['2030-01-02T02:00:00.000Z', '2030-01-02T02:01:00.000Z'],
        ['2030-01-02T02:01:00.000Z', '2030-01-02T02:02:00.000Z'],
        ['2030-01-02T02:02:00.000Z', '2030-01-02T02:30:00.000Z'],
      ]) {
        const claimTime = new Date(claimAt);
        const reminder = (await repository.claimPushDue(claimTime, 100)).find(
          (row) => row.appointmentId === appointmentId,
        );
        expect(reminder).toBeDefined();
        await repository.retryPush(
          reminder!.id,
          new Date(retryAt),
          'QUIET_HOURS_DEFERRED',
          claimTime,
        );
      }

      const result = await pool.query<{
        state: string;
        attempt_count: number;
        failure_code: string;
        next_attempt_at: Date | null;
        terminal_at: Date | null;
      }>(
        `SELECT state, attempt_count, failure_code, next_attempt_at, terminal_at
         FROM appointment_push_reminder WHERE appointment_id=$1`,
        [appointmentId],
      );
      expect(result.rows).toEqual([
        {
          state: 'FAILED',
          attempt_count: 3,
          failure_code: 'RETRY_LIMIT_EXHAUSTED',
          next_attempt_at: null,
          terminal_at: new Date('2030-01-02T02:02:00.000Z'),
        },
      ]);
      const next = await repository.claimPushDue(new Date('2030-01-02T02:31:00.000Z'), 100);
      expect(next.some((row) => row.appointmentId === appointmentId)).toBe(false);
    });

    it('terminally reconciles a crashed final processing lease without another claim', async () => {
      const repository = reminderRepository();
      const appointmentId = '24000000-0000-4000-8000-000000000040';
      await repository.apply(
        statusEvent(appointmentId, 1, 'CONFIRMED'),
        new Date('2030-01-01T00:00:00.000Z'),
      );

      for (const claimAt of [
        '2030-01-02T02:00:00.000Z',
        '2030-01-02T02:00:31.000Z',
        '2030-01-02T02:01:02.000Z',
      ]) {
        const claims = await repository.claimPushDue(new Date(claimAt), 100);
        expect(claims.some((row) => row.appointmentId === appointmentId)).toBe(true);
      }

      await expect(
        repository.reconcilePushLeases(new Date('2030-01-02T02:01:31.000Z')),
      ).resolves.toBe(0);
      await expect(
        repository.reconcilePushLeases(new Date('2030-01-02T02:01:33.000Z')),
      ).resolves.toBeGreaterThanOrEqual(1);

      const terminal = await pool.query<{
        state: string;
        attempt_count: number;
        failure_code: string;
        terminal_at: Date;
      }>(
        `SELECT state, attempt_count, failure_code, terminal_at
         FROM appointment_push_reminder WHERE appointment_id=$1`,
        [appointmentId],
      );
      expect(terminal.rows).toEqual([
        {
          state: 'FAILED',
          attempt_count: 3,
          failure_code: 'PROCESSING_LEASE_EXHAUSTED',
          terminal_at: new Date('2030-01-02T02:01:33.000Z'),
        },
      ]);
      const afterTerminal = await repository.claimPushDue(
        new Date('2030-01-02T02:02:00.000Z'),
        100,
      );
      expect(afterTerminal.some((row) => row.appointmentId === appointmentId)).toBe(false);
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
      const pushReminder = await pool.query<{ state: string; failure_code: string }>(
        'SELECT state, failure_code FROM appointment_push_reminder WHERE appointment_id=$1',
        [appointmentId],
      );
      expect(pushReminder.rows).toEqual([{ state: 'EXPIRED', failure_code: 'LATE_CONFIRMATION' }]);
      const claimedPush = await repository.claimPushDue(new Date('2030-01-02T04:00:00.000Z'), 100);
      expect(claimedPush.some((row) => row.appointmentId === appointmentId)).toBe(false);
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
      await pool.query(`UPDATE resource SET version = version + 1 WHERE id = $1`, [
        resource.rows[0].id,
      ]);
      const afterResourceEdit = await repository.save(owner, resource.rows[0].id, '2026-09-28', {
        status: 'IN_PROGRESS',
        completedActionIds: [],
      });
      expect(afterResourceEdit?.contentVersion).toBe(completed?.contentVersion);
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

  describe('resource reward read model', () => {
    it('only awards otherwise eligible practice evidence when it is plan-bound', async () => {
      const database = {
        query: (text: string, parameters?: unknown[]) => pool.query(text, parameters),
      } as unknown as DatabaseService;
      const repository = new ResourceRewardRepository(database);
      const owner = '12690000-0000-4000-8000-000000000001';
      const resourceId = '12690000-0000-4000-8000-000000000002';
      const supportPlanId = '12690000-0000-4000-8000-000000000003';
      await pool.query(
        `INSERT INTO resource
           (id, category, locale, title, summary, content_body, source_organization,
            source_title, source_url, source_review_note, status, reviewed_by,
            reviewed_at, effective_at, source_review_status, resource_kind,
            interaction_type, repeatability, completion_mode, streak_eligible)
         VALUES ($1, 'BREATHING', 'vi-VN', 'Plan-bound evidence', 'Summary', 'Body',
           'Reviewed source', 'Reviewed title', 'https://example.com/source', 'Reviewed note',
           'PUBLISHED', '12690000-0000-4000-8000-000000000099', now(), now(),
           'REVIEWED', 'PRACTICE', 'BREATHING_PACER', 'REPEATABLE', 'TIMED', true)`,
        [resourceId],
      );
      await pool.query(
        `INSERT INTO resource_publication_snapshot
           (resource_id, content_version, snapshot, published_by, published_at)
         SELECT id, version, to_jsonb(resource), reviewed_by, reviewed_at
         FROM resource WHERE id = $1`,
        [resourceId],
      );
      await pool.query(
        `INSERT INTO resource_practice_session
           (id, owner_id, resource_id, resource_version, support_plan_id, local_date,
            completed_at, started_at, duration_seconds)
         VALUES
           ('12690000-0000-4000-8000-000000000010', $1, $2, 0, NULL, '2026-10-10',
            '2026-10-10T04:05:00Z', '2026-10-10T04:00:00Z', 300),
           ('12690000-0000-4000-8000-000000000011', $1, $2, 0, $3, '2026-10-11',
            '2026-10-11T05:05:00Z', '2026-10-11T05:00:00Z', 300)`,
        [owner, resourceId, supportPlanId],
      );

      const source = await repository.read(owner);
      expect(source.facts).toHaveLength(1);
      expect(source.facts[0]).toMatchObject({
        kind: 'VERIFIED_PRACTICE',
        resourceId,
        occurredAt: '2026-10-11T05:05:00.000Z',
      });
      expect(calculateResourceRewards(source.facts, owner).totalXp).toBe(5);
    });

    it('derives bounded owner-scoped rewards from exact reviewed evidence and corrections', async () => {
      const database = {
        query: (text: string, parameters?: unknown[]) => pool.query(text, parameters),
      } as unknown as DatabaseService;
      const repository = new ResourceRewardRepository(database);
      const owner = '12700000-0000-4000-8000-000000000001';
      const otherOwner = '12700000-0000-4000-8000-000000000002';
      const resourceId = '12700000-0000-4000-8000-000000000003';
      await pool.query(
        `INSERT INTO resource
           (id, category, locale, title, summary, content_body, source_organization,
            source_title, source_url, source_review_note, status, reviewed_by,
            reviewed_at, effective_at, source_review_status, resource_kind,
            interaction_type, repeatability, completion_mode, streak_eligible)
         VALUES ($1, 'BREATHING', 'vi-VN', 'Reward evidence', 'Summary', 'Body',
           'Reviewed source', 'Reviewed title', 'https://example.com/source', 'Reviewed note',
           'PUBLISHED', '12700000-0000-4000-8000-000000000099', now(), now(),
           'REVIEWED', 'PRACTICE', 'BREATHING_PACER', 'REPEATABLE', 'TIMED', true)`,
        [resourceId],
      );
      await pool.query(
        `INSERT INTO resource_publication_snapshot
           (resource_id, content_version, snapshot, published_by, published_at)
         SELECT id, version, to_jsonb(resource), reviewed_by, reviewed_at
         FROM resource WHERE id = $1`,
        [resourceId],
      );
      await pool.query(
        `INSERT INTO resource_daily_progress
           (owner_id, resource_id, local_date, resource_version, status,
            completed_action_ids, completed_at)
         VALUES ($1, $2, '2026-10-12', 0, 'COMPLETED', ARRAY['done'],
           '2026-10-12T04:00:00Z')`,
        [owner, resourceId],
      );
      await pool.query(
        `INSERT INTO resource_practice_session
           (id, owner_id, resource_id, resource_version, support_plan_id, local_date,
            completed_at, started_at, duration_seconds)
         VALUES
           ('12700000-0000-4000-8000-000000000010', $1, $2, 0, $3, '2026-10-12',
            '2026-10-12T04:05:00Z', '2026-10-12T04:00:00Z', 300),
           ('12700000-0000-4000-8000-000000000011', $1, $2, 0, $3, '2026-10-12',
            '2026-10-12T05:05:00Z', '2026-10-12T05:00:00Z', 300),
           ('12700000-0000-4000-8000-000000000012', $1, $2, 0, $3, '2026-10-13',
            '2026-10-13T05:00:30Z', '2026-10-13T05:00:00Z', 30)`,
        [owner, resourceId, '12700000-0000-4000-8000-000000000004'],
      );

      const source = await repository.read(owner);
      expect(source.facts).toHaveLength(2);
      expect(calculateResourceRewards(source.facts, owner).totalXp).toBe(15);
      expect(
        calculateResourceRewards((await repository.read(otherOwner)).facts, otherOwner).totalXp,
      ).toBe(0);
      const concurrentReads = await Promise.all(
        Array.from({ length: 8 }, async () =>
          calculateResourceRewards((await repository.read(owner)).facts, owner),
        ),
      );
      expect(new Set(concurrentReads.map((result) => result.totalXp))).toEqual(new Set([15]));
      expect(new Set(concurrentReads.map((result) => result.grants[0]?.grantId)).size).toBe(1);

      await repository.setVisibility(owner, 'HIDDEN');
      await repository.setVisibility(owner, 'HIDDEN');
      expect((await repository.read(owner)).preference).toEqual({
        visibilityState: 'HIDDEN',
        version: '0',
      });

      await pool.query(
        `DELETE FROM resource_daily_progress
         WHERE owner_id = $1 AND resource_id = $2 AND local_date = '2026-10-12'`,
        [owner, resourceId],
      );
      expect(calculateResourceRewards((await repository.read(owner)).facts, owner).totalXp).toBe(5);
    });
  });

  describe('private achievement read model', () => {
    it('keeps resource evidence owner-scoped, reflects deletion, and versions visibility', async () => {
      const database = {
        query: (text: string, parameters?: unknown[]) => pool.query(text, parameters),
      } as unknown as DatabaseService;
      const repository = new AchievementRepository(database);
      const owner = '12710000-0000-4000-8000-000000000001';
      const otherOwner = '12710000-0000-4000-8000-000000000002';
      const resourceId = '12710000-0000-4000-8000-000000000003';
      await pool.query(
        `INSERT INTO resource
           (id, category, locale, title, summary, content_body, source_organization,
            source_title, source_url, source_review_note, status, reviewed_by,
            reviewed_at, effective_at, source_review_status, resource_kind,
            interaction_type, repeatability, completion_mode, streak_eligible)
         VALUES ($1, 'BREATHING', 'vi-VN', 'Achievement evidence', 'Summary', 'Body',
           'Reviewed source', 'Reviewed title', 'https://example.com/source', 'Reviewed note',
           'PUBLISHED', '12710000-0000-4000-8000-000000000099', now(), now(),
           'REVIEWED', 'PRACTICE', 'BREATHING_PACER', 'REPEATABLE', 'TIMED', true)`,
        [resourceId],
      );
      await pool.query(
        `INSERT INTO resource_publication_snapshot
           (resource_id, content_version, snapshot, published_by, published_at)
         SELECT id, version, to_jsonb(resource), reviewed_by, reviewed_at
         FROM resource WHERE id = $1`,
        [resourceId],
      );
      await pool.query(
        `INSERT INTO resource_daily_progress
           (owner_id, resource_id, local_date, resource_version, status,
            completed_action_ids, completed_at)
         VALUES ($1, $2, '2026-10-12', 0, 'COMPLETED', ARRAY['done'],
           '2026-10-12T04:00:00Z')`,
        [owner, resourceId],
      );

      const source = await repository.read(owner);
      expect(source.preference).toEqual({ visibilityState: 'VISIBLE', version: '0' });
      expect(
        calculateAchievements({ activity: null, resourceFacts: source.facts }).find(
          ({ definitionId }) => definitionId === 'resource.first-completion',
        ),
      ).toMatchObject({ status: 'EARNED', unlockedAt: '2026-10-12T04:00:00.000Z' });
      expect((await repository.read(otherOwner)).facts).toHaveLength(0);

      await repository.setVisibility(owner, 'HIDDEN', '0');
      await repository.setVisibility(owner, 'HIDDEN', '1');
      expect((await repository.read(owner)).preference).toEqual({
        visibilityState: 'HIDDEN',
        version: '1',
      });
      await expect(repository.setVisibility(owner, 'VISIBLE', '0')).rejects.toBeInstanceOf(
        AchievementPreferenceVersionMismatchError,
      );

      await pool.query(
        `DELETE FROM resource_daily_progress WHERE owner_id = $1 AND resource_id = $2`,
        [owner, resourceId],
      );
      expect(
        calculateAchievements({
          activity: null,
          resourceFacts: (await repository.read(owner)).facts,
        }).find(({ definitionId }) => definitionId === 'resource.first-completion'),
      ).toMatchObject({ status: 'UNEARNED', unlockedAt: null });
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

    it('materializes Community facts once and preserves a disabled preference across replay', async () => {
      const database = {
        query: (text: string, parameters?: unknown[]) => pool.query(text, parameters),
        withTransaction: async <T>(operation: (client: DatabaseClient) => Promise<T>) => {
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
      const communityRepository = new CommunityNotificationRepository(database);
      const inboxRepository = new NotificationRepository(database);
      const enabledOwner = '11500000-0000-4000-8000-000000000001';
      const disabledOwner = '11500000-0000-4000-8000-000000000002';
      const postId = '11500000-0000-4000-8000-000000000003';
      const enabled = {
        eventId: '11500000-0000-4000-8000-000000000004',
        ownerId: enabledOwner,
        kind: 'COMMUNITY_COMMENT' as const,
        title: 'Bài viết của bạn có phản hồi mới',
        body: 'Một thành viên đã để lại lời nhắn hỗ trợ.',
        occurredAt: '2026-10-05T08:00:00.000Z',
        postId,
      };

      const first = await communityRepository.materialize(enabled, 'a'.repeat(64));
      const replay = await communityRepository.materialize(enabled, 'a'.repeat(64));
      expect(first).toMatchObject({ delivered: true, replayed: false });
      expect(replay).toMatchObject({
        notificationId: first.notificationId,
        delivered: true,
        replayed: true,
      });
      const page = await inboxRepository.list(enabledOwner, 20, null);
      expect(page.items).toHaveLength(1);
      expect(page.items[0]).toMatchObject({
        kind: 'COMMUNITY_COMMENT',
        action: {
          type: 'OPEN_COMMUNITY_POST',
          targetId: postId,
          href: `/community/${postId}`,
        },
      });

      await pool.query(
        `INSERT INTO notification_preference
           (user_id, group_community_interaction_enabled)
         VALUES ($1, false)`,
        [disabledOwner],
      );
      const disabled = {
        ...enabled,
        eventId: '11500000-0000-4000-8000-000000000005',
        ownerId: disabledOwner,
      };
      const suppressed = await communityRepository.materialize(disabled, 'b'.repeat(64));
      expect(suppressed).toMatchObject({ delivered: false, replayed: false });
      await pool.query(
        `UPDATE notification_preference
         SET group_community_interaction_enabled = true
         WHERE user_id = $1`,
        [disabledOwner],
      );
      const suppressedReplay = await communityRepository.materialize(disabled, 'b'.repeat(64));
      expect(suppressedReplay).toMatchObject({
        notificationId: suppressed.notificationId,
        delivered: false,
        replayed: true,
      });
      expect((await inboxRepository.list(disabledOwner, 20, null)).items).toEqual([]);

      await expect(
        communityRepository.materialize({ ...enabled, body: 'Changed' }, 'c'.repeat(64)),
      ).rejects.toBeInstanceOf(CommunityNotificationDedupeConflictError);
      const audit = await pool.query<{
        count: string;
        source: string;
        source_identity: string;
        delivery_state: string;
      }>(
        `SELECT count(*)::text AS count, min(source) AS source,
                min(source_identity) AS source_identity,
                min(delivery_state) AS delivery_state
         FROM notification
         WHERE recipient_id = $1 AND source_identity = $2`,
        [enabledOwner, enabled.eventId],
      );
      expect(audit.rows[0]).toMatchObject({
        count: '1',
        source: 'COMMUNITY_INTERACTION_V1',
        source_identity: enabled.eventId,
        delivery_state: 'DELIVERED',
      });
    });

    it('materializes MB-565 facts once and preserves preference decisions across replay', async () => {
      const database = {
        query: (text: string, parameters?: unknown[]) => pool.query(text, parameters),
        withTransaction: async <T>(operation: (client: DatabaseClient) => Promise<T>) => {
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
      const facts = new UserNotificationRepository(database);
      const inbox = new NotificationRepository(database);
      const owner = '11600000-0000-4000-8000-000000000001';
      const guideId = '11600000-0000-4000-8000-000000000002';
      const projection = {
        sourceIdentity: `SUPPORT_GUIDE_AVAILABLE:${guideId}:1`,
        ownerId: owner,
        kind: 'SUPPORT_GUIDE_AVAILABLE' as const,
        title: 'Hướng dẫn hỗ trợ đã sẵn sàng',
        body: 'Hướng dẫn đã được lưu để bạn xem lại khi thuận tiện.',
        occurredAt: '2026-10-19T08:00:00.000Z',
        preferenceGroup: 'RESOURCE_SYSTEM' as const,
        source: 'CARE_USER_NOTIFICATION_V1' as const,
        actionType: 'OPEN_SUPPORT_GUIDE' as const,
        actionTargetId: guideId,
      };

      await facts.materialize(projection, 'd'.repeat(64));
      await facts.materialize(projection, 'd'.repeat(64));
      const page = await inbox.list(owner, 20, null);
      expect(page.items).toHaveLength(1);
      expect(page.items[0]).toMatchObject({
        kind: 'SUPPORT_GUIDE_AVAILABLE',
        action: {
          type: 'OPEN_SUPPORT_GUIDE',
          targetId: guideId,
          href: `/support-guides/${guideId}`,
        },
      });
      await expect(
        facts.materialize({ ...projection, title: 'Changed replay' }, 'e'.repeat(64)),
      ).rejects.toBeInstanceOf(UserNotificationDedupeConflictError);

      const disabledOwner = '11600000-0000-4000-8000-000000000003';
      await pool.query(
        `INSERT INTO notification_preference
           (user_id, group_screening_reassessment_enabled)
         VALUES ($1, false)`,
        [disabledOwner],
      );
      const disabledProjection = {
        ...projection,
        sourceIdentity: 'ASSESSMENT_RESULT_AVAILABLE:11600000-0000-4000-8000-000000000004:0',
        ownerId: disabledOwner,
        kind: 'ASSESSMENT_RESULT_AVAILABLE' as const,
        preferenceGroup: 'SCREENING_REASSESSMENT' as const,
        actionType: 'OPEN_ASSESSMENTS' as const,
        actionTargetId: null,
      };
      await facts.materialize(disabledProjection, 'f'.repeat(64));
      await pool.query(
        `UPDATE notification_preference
         SET group_screening_reassessment_enabled = true WHERE user_id = $1`,
        [disabledOwner],
      );
      await facts.materialize(disabledProjection, 'f'.repeat(64));
      expect((await inbox.list(disabledOwner, 20, null)).items).toEqual([]);
      const cancelled = await pool.query<{ count: string; delivery_state: string }>(
        `SELECT count(*)::text AS count, min(delivery_state) AS delivery_state
         FROM notification WHERE recipient_id = $1 AND source_identity = $2`,
        [disabledOwner, disabledProjection.sourceIdentity],
      );
      expect(cancelled.rows[0]).toEqual({ count: '1', delivery_state: 'CANCELLED' });
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
  describe('MB-670 plan-scoped resource progress', () => {
    const weekStart = '2026-10-19';
    const timeZone = 'Asia/Ho_Chi_Minh';
    function database(): DatabaseService {
      return {
        query: (text: string, values?: unknown[]) => pool.query(text, values),
        withTransaction: async <T>(operation: (client: DatabaseClient) => Promise<T>) => {
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
    }
    async function fixture(count = 3) {
      const owner = randomUUID();
      const planId = randomUUID();
      const reviewer = randomUUID();
      const resourceIds: string[] = [];
      for (let index = 0; index < count; index++) {
        const resourceId = randomUUID();
        resourceIds.push(resourceId);
        await pool.query(
          `INSERT INTO resource
             (id, category, locale, title, summary, content_body, source_organization,
              source_title, source_url, source_review_note, status, reviewed_by,
              reviewed_at, effective_at, source_review_status, resource_kind,
              interaction_type, repeatability, completion_mode, streak_eligible,
              catalogue_visibility)
           VALUES ($1, 'BREATHING', 'vi-VN', $3, 'Summary', 'Body',
             'Reviewed source', 'Reviewed title', 'https://example.com/source', 'Reviewed note',
             'PUBLISHED', $2, now(), now(), 'REVIEWED', 'PRACTICE',
             'BREATHING_PACER', 'REPEATABLE', 'TIMED', true, 'LISTED')`,
          [resourceId, reviewer, `Plan resource ${index}`],
        );
        await pool.query(
          `INSERT INTO resource_publication_snapshot
          (resource_id, content_version, snapshot, published_by)
          SELECT id, version, to_jsonb(resource), reviewed_by FROM resource WHERE id = $1`,
          [resourceId],
        );
      }
      const actions = resourceIds.map((resourceId) => ({
        actionId: randomUUID(),
        actionVersion: '1',
        resource: { resourceId, resourceVersion: '0', publicationId: randomUUID() },
      }));
      const projection: PlanResourceActions = {
        planState: 'ACTIVE',
        supportPlanId: planId,
        supportPlanVersion: '1',
        timeZone,
        actions,
      };
      const db = database();
      return {
        owner,
        planId,
        actions,
        projection,
        resourceIds,
        db,
        repository: new ResourceEngagementRepository(db),
      };
    }

    it('creates only current plan tiles, deduplicates resources, and keeps identical/concurrent sync versions stable', async () => {
      const f = await fixture(2);
      const first = await f.repository.syncPlanBingo(f.owner, weekStart, timeZone, {
        ...f.projection,
        actions: [...f.actions, { ...f.actions[0], actionId: randomUUID() }],
      });
      expect(first.items).toHaveLength(2);
      expect(first.items.map((item) => item.actionId)).toEqual(
        f.actions.map((action) => action.actionId),
      );
      expect(first.items.every((item) => !item.retired)).toBe(true);
      const replay = await f.repository.syncPlanBingo(f.owner, weekStart, timeZone, {
        ...f.projection,
        supportPlanVersion: '2',
        actions: f.actions.map((action) => ({ ...action, actionVersion: '2' })),
      });
      expect(replay.version).toBe(first.version);
      expect(replay.updatedAt).toBe(first.updatedAt);
      const concurrent = await Promise.all(
        Array.from({ length: 3 }, () =>
          f.repository.syncPlanBingo(f.owner, weekStart, timeZone, f.projection),
        ),
      );
      expect(
        concurrent.every((board) => board.version === first.version && board.items.length === 2),
      ).toBe(true);
      const stale = await f.repository.syncPlanBingo(f.owner, weekStart, timeZone, {
        ...f.projection,
        actions: [],
      });
      expect(stale.items).toHaveLength(2);
      expect(stale.version).toBe(first.version);
      const rows = await pool.query('SELECT id FROM resource_weekly_bingo WHERE owner_id = $1', [
        f.owner,
      ]);
      expect(rows.rows).toHaveLength(1);
      const empty = await fixture(0);
      expect(
        (await empty.repository.syncPlanBingo(empty.owner, weekStart, timeZone, empty.projection))
          .items,
      ).toEqual([]);
    });

    it('retires removed actions, rejects their toggles, rebinds in place and clears personal checks only on version changes', async () => {
      const f = await fixture(2);
      let board = await f.repository.syncPlanBingo(f.owner, weekStart, timeZone, f.projection);
      board = {
        ...(await f.repository.setBingoSelfConfirmed(f.owner, weekStart, 1, board.version, true))!,
        unavailableActions: [],
      };
      const confirmedAt = board.items[0].selfConfirmedAt;
      const retired = await f.repository.syncPlanBingo(f.owner, weekStart, timeZone, {
        ...f.projection,
        actions: f.actions.slice(1),
      });
      expect(retired.items[0]).toMatchObject({
        position: 1,
        retired: true,
        selfConfirmedAt: confirmedAt,
      });
      await expect(
        f.repository.setBingoSelfConfirmed(f.owner, weekStart, 1, retired.version, false),
      ).rejects.toBeInstanceOf(ResourceBingoItemRetiredError);
      const restored = await f.repository.syncPlanBingo(f.owner, weekStart, timeZone, f.projection);
      expect(restored.items[0]).toMatchObject({
        position: 1,
        retired: false,
        selfConfirmedAt: confirmedAt,
      });
      await pool.query(
        `INSERT INTO resource_publication_snapshot (resource_id, content_version, snapshot, published_by)
        SELECT id, 1, to_jsonb(resource), reviewed_by FROM resource WHERE id = $1`,
        [f.resourceIds[0]],
      );
      const upgraded = await f.repository.syncPlanBingo(f.owner, weekStart, timeZone, {
        ...f.projection,
        actions: [
          { ...f.actions[0], resource: { ...f.actions[0].resource, resourceVersion: '1' } },
          f.actions[1],
        ],
      });
      expect(upgraded.items[0]).toMatchObject({
        position: 1,
        contentVersion: '1',
        selfConfirmedAt: null,
        retired: false,
      });
    });

    it('reports unavailable versions for each action even when another action shares its resource tile', async () => {
      const f = await fixture(1);
      const unavailable = {
        ...f.actions[0],
        actionId: randomUUID(),
        resource: { ...f.actions[0].resource, resourceVersion: '99' },
      };
      const board = await f.repository.syncPlanBingo(f.owner, weekStart, timeZone, {
        ...f.projection,
        actions: [f.actions[0], unavailable],
      });
      expect(board.items).toHaveLength(1);
      expect(board.items[0]).toMatchObject({
        actionId: f.actions[0].actionId,
        contentVersion: '0',
      });
      expect(board.unavailableActions).toEqual([
        { actionId: unavailable.actionId, reason: 'RESOURCE_VERSION_UNAVAILABLE' },
      ]);
    });

    it('keeps plan switch history, returns the last synced plan, and retires only the latest board for NONE', async () => {
      const f = await fixture(2);
      const original = await f.repository.syncPlanBingo(f.owner, weekStart, timeZone, {
        ...f.projection,
        actions: [f.actions[0]],
      });
      const other = { ...f.projection, supportPlanId: randomUUID(), actions: [f.actions[1]] };
      const switched = await f.repository.syncPlanBingo(f.owner, weekStart, timeZone, other);
      expect(switched.version).not.toBe(original.version);
      await expect(
        f.repository.setBingoSelfConfirmed(f.owner, weekStart, 1, original.version, true),
      ).rejects.toBeInstanceOf(ResourceBingoVersionMismatchError);
      expect((await f.repository.readBingo(f.owner, weekStart))?.items[0].resourceId).toBe(
        f.resourceIds[1],
      );
      const resynced = await f.repository.syncPlanBingo(f.owner, weekStart, timeZone, {
        ...f.projection,
        actions: [f.actions[0]],
      });
      expect(resynced.version).toBe(original.version);
      expect((await f.repository.readBingo(f.owner, weekStart))?.items[0].resourceId).toBe(
        f.resourceIds[0],
      );
      const none = await f.repository.syncPlanBingo(f.owner, weekStart, timeZone, {
        planState: 'NONE',
        actions: [],
      });
      expect(none.items[0].retired).toBe(true);
      expect(
        (
          await f.repository.syncPlanBingo(f.owner, weekStart, timeZone, {
            planState: 'NONE',
            actions: [],
          })
        ).version,
      ).toBe(none.version);
      const historical = await pool.query<{ retired_at: Date | null }>(
        `SELECT item.retired_at FROM resource_weekly_bingo board JOIN resource_weekly_bingo_item item ON item.board_id = board.id WHERE board.owner_id = $1 AND board.support_plan_id = $2`,
        [f.owner, other.supportPlanId],
      );
      expect(historical.rows[0].retired_at).toBeNull();
      const missingOwner = randomUUID();
      expect(
        await f.repository.syncPlanBingo(missingOwner, weekStart, timeZone, {
          planState: 'NONE',
          actions: [],
        }),
      ).toMatchObject({ version: '0', items: [], unavailableActions: [] });
      expect(await f.repository.readBingo(missingOwner, weekStart)).toBeNull();
    });

    it('caps all historical slots at nine without padding and reports BOARD_FULL plus unavailable exact versions', async () => {
      const f = await fixture(11);
      const first = await f.repository.syncPlanBingo(f.owner, weekStart, timeZone, f.projection);
      expect(first.items).toHaveLength(9);
      expect(first.unavailableActions).toEqual(
        f.actions.slice(9).map(({ actionId }) => ({ actionId, reason: 'BOARD_FULL' })),
      );
      const overflowDuplicate = { ...f.actions[9], actionId: randomUUID() };
      const overflow = await f.repository.syncPlanBingo(f.owner, weekStart, timeZone, {
        ...f.projection,
        actions: [...f.actions, overflowDuplicate],
      });
      expect(overflow.unavailableActions).toEqual([
        ...first.unavailableActions,
        { actionId: overflowDuplicate.actionId, reason: 'BOARD_FULL' },
      ]);
      const next = await f.repository.syncPlanBingo(f.owner, weekStart, timeZone, {
        ...f.projection,
        actions: f.actions.slice(1),
      });
      expect(next.items).toHaveLength(9);
      expect(next.items[0].retired).toBe(true);
      expect(next.unavailableActions.every(({ reason }) => reason === 'BOARD_FULL')).toBe(true);
      const absent = {
        ...f.actions[0],
        resource: { ...f.actions[0].resource, resourceVersion: '99' },
      };
      const unavailable = await f.repository.syncPlanBingo(f.owner, weekStart, timeZone, {
        ...f.projection,
        supportPlanId: randomUUID(),
        actions: [absent],
      });
      expect(unavailable.items).toEqual([]);
      expect(unavailable.unavailableActions).toEqual([
        { actionId: absent.actionId, reason: 'RESOURCE_VERSION_UNAVAILABLE' },
      ]);
      const outOfRange = await f.repository.syncPlanBingo(f.owner, weekStart, timeZone, {
        ...f.projection,
        supportPlanId: randomUUID(),
        actions: [
          { ...absent, resource: { ...absent.resource, resourceVersion: '9999999999999999999' } },
        ],
      });
      expect(outOfRange.items).toEqual([]);
      expect(outOfRange.unavailableActions).toEqual(unavailable.unavailableActions);
    });

    it('retires padded legacy resources while binding matching plan resources in their original ordinals', async () => {
      const f = await fixture(2);
      const boardId = (
        await pool.query<{ id: string }>(
          `INSERT INTO resource_weekly_bingo (owner_id, week_start, time_zone, support_plan_id, support_plan_version, plan_tags) VALUES ($1, $2::date, $3, $4, 1, '{}') RETURNING id`,
          [f.owner, weekStart, timeZone, f.planId],
        )
      ).rows[0].id;
      await pool.query(
        `INSERT INTO resource_weekly_bingo_item (board_id, ordinal, resource_id, resource_version, self_confirmed_at) VALUES ($1, 0, $2, 0, now()), ($1, 1, $3, 0, NULL)`,
        [boardId, f.resourceIds[0], f.resourceIds[1]],
      );
      const board = await f.repository.syncPlanBingo(f.owner, weekStart, timeZone, {
        ...f.projection,
        actions: [f.actions[1]],
      });
      expect(board.items[0]).toMatchObject({ actionId: null, retired: true, position: 1 });
      expect(board.items[0].selfConfirmedAt).not.toBeNull();
      expect(board.items[1]).toMatchObject({
        actionId: f.actions[1].actionId,
        retired: false,
        position: 2,
      });
    });

    it('frees unchecked legacy padding on a full nine-tile board while preserving personal checks and matching ordinals', async () => {
      const f = await fixture(12);
      const boardId = (
        await pool.query<{ id: string }>(
          `INSERT INTO resource_weekly_bingo (owner_id, week_start, time_zone, support_plan_id, support_plan_version, plan_tags) VALUES ($1, $2::date, $3, $4, 1, '{}') RETURNING id`,
          [f.owner, weekStart, timeZone, f.planId],
        )
      ).rows[0].id;
      for (let ordinal = 0; ordinal < 9; ordinal++) {
        await pool.query(
          `INSERT INTO resource_weekly_bingo_item (board_id, ordinal, resource_id, resource_version, self_confirmed_at) VALUES ($1, $2::smallint, $3, 0, CASE WHEN $2::smallint = 0 THEN now() ELSE NULL END)`,
          [boardId, ordinal, f.resourceIds[ordinal]],
        );
      }
      const projection = { ...f.projection, actions: [f.actions[1], ...f.actions.slice(9)] };
      const board = await f.repository.syncPlanBingo(f.owner, weekStart, timeZone, projection);
      expect(board.unavailableActions).toEqual([]);
      expect(board.items).toHaveLength(5);
      expect(board.items[0]).toMatchObject({ actionId: null, retired: true, position: 1 });
      expect(board.items[0].selfConfirmedAt).not.toBeNull();
      expect(board.items.slice(1).map((item) => item.actionId)).toEqual(
        projection.actions.map((action) => action.actionId),
      );
      expect(board.items.slice(1).every((item) => !item.retired)).toBe(true);
      expect(board.items[1].position).toBe(2);
      const stored = await pool.query<{ resource_id: string }>(
        `SELECT resource_id FROM resource_weekly_bingo_item WHERE board_id = $1 ORDER BY ordinal`,
        [boardId],
      );
      expect(stored.rows.map((item) => item.resource_id)).toEqual([
        f.resourceIds[0],
        f.resourceIds[1],
        ...f.resourceIds.slice(9),
      ]);
      expect(
        (await f.repository.syncPlanBingo(f.owner, weekStart, timeZone, projection)).version,
      ).toBe(board.version);
    });

    it('ignores legacy client practice start dates for action-bound Bingo and evidence near midnight', async () => {
      const f = await fixture(4);
      await f.repository.syncPlanBingo(f.owner, weekStart, timeZone, f.projection);
      await pool.query(
        `INSERT INTO resource_practice_session (owner_id, resource_id, resource_version, local_date, started_at, completed_at, duration_seconds)
         VALUES ($1, $2, 0, '2026-10-21', '2026-10-21T03:00:00Z', '2026-10-26T03:01:00Z', 60),
                ($1, $3, 0, '2000-01-01', '2000-01-01T00:00:00Z', '2026-10-21T03:01:00Z', 60),
                ($1, $4, 0, '2026-10-19', '2026-10-18T17:00:00Z', '2026-10-18T17:00:30Z', 60),
                ($1, $5, 0, '2000-01-01', NULL, '2026-10-18T17:01:00Z', 60)`,
        [f.owner, ...f.resourceIds],
      );
      expect(
        (await f.repository.readBingo(f.owner, weekStart))?.items.map((item) => item.evidence),
      ).toEqual(['NONE', 'CONTENT_VERIFIED', 'NONE', 'CONTENT_VERIFIED']);
      expect(
        await f.repository.completionEvidence(
          f.owner,
          f.resourceIds[0],
          '0',
          '2026-10-21',
          timeZone,
        ),
      ).toBeNull();
      expect(
        await f.repository.completionEvidence(
          f.owner,
          f.resourceIds[1],
          '0',
          '2026-10-21',
          timeZone,
        ),
      ).toMatchObject({ occurredAt: '2026-10-21T03:00:00.000Z' });
      expect(
        await f.repository.completionEvidence(
          f.owner,
          f.resourceIds[2],
          '0',
          '2026-10-19',
          timeZone,
        ),
      ).toBeNull();
      expect(
        await f.repository.completionEvidence(
          f.owner,
          f.resourceIds[2],
          '0',
          '2026-10-18',
          timeZone,
        ),
      ).toMatchObject({ occurredAt: '2026-10-18T16:59:30.000Z' });
      expect(
        await f.repository.completionEvidence(
          f.owner,
          f.resourceIds[3],
          '0',
          '2026-10-19',
          timeZone,
        ),
      ).toMatchObject({ occurredAt: '2026-10-18T17:00:00.000Z' });
    });

    it('verifies facts using database time with verification never earlier than occurrence', async () => {
      const f = await fixture(1);
      await pool.query(
        `INSERT INTO resource_daily_progress (owner_id, resource_id, resource_version, local_date, status, completed_at) VALUES ($1, $2, 0, '2099-10-21', 'COMPLETED', '2099-10-21T03:00:00Z')`,
        [f.owner, f.resourceIds[0]],
      );
      const evidence = await f.repository.completionEvidence(
        f.owner,
        f.resourceIds[0],
        '0',
        '2099-10-21',
        timeZone,
      );
      expect(evidence).toMatchObject({
        occurredAt: '2099-10-21T03:00:00.000Z',
        verifiedAt: '2099-10-21T03:00:00.000Z',
      });
    });

    it('verifies action-bound tiles using exact versions and server instants in board timezone, without journey assignments', async () => {
      const f = await fixture(3);
      await f.repository.syncPlanBingo(f.owner, weekStart, timeZone, f.projection);
      await pool.query(
        `INSERT INTO resource_daily_progress (owner_id, resource_id, local_date, resource_version, status, completed_at) VALUES ($1, $2, '2000-01-01', 0, 'COMPLETED', '2026-10-18T17:30:00Z'), ($1, $3, '2026-10-21', 0, 'COMPLETED', '2026-10-18T16:59:00Z')`,
        [f.owner, f.resourceIds[0], f.resourceIds[1]],
      );
      await pool.query(
        `INSERT INTO resource_practice_session (owner_id, resource_id, resource_version, local_date, started_at, completed_at, duration_seconds) VALUES ($1, $2, 0, '2000-01-01', '2026-10-21T03:00:00Z', '2026-10-21T03:01:00Z', 60)`,
        [f.owner, f.resourceIds[2]],
      );
      const board = await f.repository.readBingo(f.owner, weekStart);
      expect(board?.items.map((item) => item.evidence)).toEqual([
        'CONTENT_VERIFIED',
        'NONE',
        'CONTENT_VERIFIED',
      ]);
      const evidence = await f.repository.completionEvidence(
        f.owner,
        f.resourceIds[0],
        '0',
        '2026-10-19',
        timeZone,
      );
      expect(evidence).toMatchObject({
        factKind: 'RESOURCE_COMPLETION',
        factId: `${f.resourceIds[0]}:2000-01-01`,
        resourceVersion: '0',
        occurredAt: '2026-10-18T17:30:00.000Z',
      });
      expect(
        await f.repository.completionEvidence(
          f.owner,
          f.resourceIds[0],
          '0',
          '2000-01-01',
          timeZone,
        ),
      ).toBeNull();
      expect(
        await f.repository.completionEvidence(
          f.owner,
          f.resourceIds[0],
          '1',
          '2026-10-19',
          timeZone,
        ),
      ).toBeNull();
      expect(
        await f.repository.completionEvidence(
          f.owner,
          f.resourceIds[0],
          '9999999999999999999',
          '2026-10-19',
          timeZone,
        ),
      ).toBeNull();
      expect(
        await f.repository.completionEvidence(
          randomUUID(),
          f.resourceIds[0],
          '0',
          '2026-10-19',
          timeZone,
        ),
      ).toBeNull();
      const practice = await f.repository.completionEvidence(
        f.owner,
        f.resourceIds[2],
        '0',
        '2026-10-21',
        timeZone,
      );
      expect(practice).toMatchObject({
        factKind: 'RESOURCE_PRACTICE_SESSION',
        occurredAt: '2026-10-21T03:00:00.000Z',
      });
      await pool.query(
        `UPDATE resource_practice_session SET duration_seconds = 59 WHERE owner_id = $1`,
        [f.owner],
      );
      expect(
        await f.repository.completionEvidence(
          f.owner,
          f.resourceIds[2],
          '0',
          '2026-10-21',
          timeZone,
        ),
      ).toBeNull();
      await pool.query(
        `UPDATE resource_daily_progress SET resource_version = 1 WHERE owner_id = $1 AND resource_id = $2`,
        [f.owner, f.resourceIds[0]],
      );
      expect((await f.repository.readBingo(f.owner, weekStart))?.items[0].evidence).toBe('NONE');
    });

    it('returns the latest recorded qualifying fact, excludes used facts, and ignores learning history', async () => {
      const f = await fixture(1);
      await pool.query(
        `INSERT INTO resource_learning_completion (owner_id, resource_id, resource_version, local_date) VALUES ($1, $2, 0, '2026-10-21')`,
        [f.owner, f.resourceIds[0]],
      );
      expect(
        await f.repository.completionEvidence(
          f.owner,
          f.resourceIds[0],
          '0',
          '2026-10-21',
          timeZone,
        ),
      ).toBeNull();
      const firstId = randomUUID();
      const newestId = randomUUID();
      await pool.query(
        `INSERT INTO resource_practice_session (id, owner_id, resource_id, resource_version, local_date, started_at, completed_at, duration_seconds) VALUES ($1, $3, $4, 0, '2026-10-21', '2026-10-21T03:59:00Z', '2026-10-21T04:00:00Z', 60), ($2, $3, $4, 0, '2026-10-21', '2026-10-21T02:02:00Z', '2026-10-21T04:02:00Z', 7200)`,
        [firstId, newestId, f.owner, f.resourceIds[0]],
      );
      expect(
        await f.repository.completionEvidence(
          f.owner,
          f.resourceIds[0],
          '0',
          '2026-10-21',
          timeZone,
        ),
      ).toMatchObject({ factId: newestId });
      expect(
        await f.repository.completionEvidence(
          f.owner,
          f.resourceIds[0],
          '0',
          '2026-10-21',
          timeZone,
          [newestId],
        ),
      ).toMatchObject({ factId: firstId, occurredAt: '2026-10-21T03:59:00.000Z' });
      expect(
        await f.repository.completionEvidence(
          f.owner,
          f.resourceIds[0],
          '0',
          '2026-10-21',
          timeZone,
          [newestId, firstId],
        ),
      ).toBeNull();
    });

    it('records the pinned publication version, retains completed progress binding, and derives practice time from the server', async () => {
      const f = await fixture(1);
      const progress = new ResourceProgressRepository(f.db);
      const resourceId = f.resourceIds[0];
      await pool.query('UPDATE resource SET version = 1 WHERE id = $1', [resourceId]);
      await pool.query(
        `INSERT INTO resource_publication_snapshot (resource_id, content_version, snapshot, published_by) SELECT id, version, to_jsonb(resource), reviewed_by FROM resource WHERE id = $1`,
        [resourceId],
      );
      const before = Date.now();
      const initial = await progress.save(f.owner, resourceId, '2000-01-01', {
        status: 'COMPLETED',
        completedActionIds: [],
        resourceVersion: '0',
      });
      expect(initial?.contentVersion).toBe('0');
      const practiceSessionId = randomUUID();
      const updated = await progress.save(f.owner, resourceId, '2000-01-01', {
        status: 'COMPLETED',
        completedActionIds: ['new-step'],
        resourceVersion: '0',
        practiceSessionId,
        practiceStartedAt: '2000-01-01T00:00:00Z',
        practiceDurationSeconds: 120,
      });
      expect(updated?.contentVersion).toBe('0');
      expect(updated?.completedAt).toBe(initial?.completedAt);
      await expect(
        progress.save(f.owner, resourceId, '2000-01-01', {
          status: 'COMPLETED',
          completedActionIds: [],
          resourceVersion: '1',
        }),
      ).rejects.toMatchObject({ status: 409 });
      expect((await progress.list(f.owner, '2000-01-01', '2000-01-01'))[0]).toMatchObject({
        contentVersion: '0',
        completedAt: initial?.completedAt,
      });
      const fact = (
        await pool.query<{
          resource_version: string;
          started_at: Date;
          support_plan_id: string | null;
        }>(
          'SELECT resource_version, started_at, support_plan_id FROM resource_practice_session WHERE id = $1',
          [practiceSessionId],
        )
      ).rows[0];
      expect(fact.resource_version).toBe('0');
      expect(fact.support_plan_id).toBeNull();
      expect(fact.started_at.getTime()).toBeGreaterThanOrEqual(before - 120_000);
      expect(fact.started_at.getTime()).toBeLessThanOrEqual(Date.now());
      await expect(
        progress.save(f.owner, resourceId, '2026-10-21', {
          status: 'COMPLETED',
          completedActionIds: [],
          resourceVersion: '99',
        }),
      ).rejects.toMatchObject({ status: 409 });
      await expect(
        progress.save(f.owner, resourceId, '2026-10-21', {
          status: 'COMPLETED',
          completedActionIds: [],
          resourceVersion: '9999999999999999999',
        }),
      ).rejects.toMatchObject({ status: 409 });
      expect(
        await f.repository.completionEvidence(f.owner, resourceId, '1', '2000-01-01', timeZone),
      ).toBeNull();
    });

    it('journey reads the latest board without materializing, replacing, or syncing it', async () => {
      const f = await fixture(2);
      await f.repository.syncPlanBingo(f.owner, weekStart, timeZone, {
        ...f.projection,
        actions: [f.actions[1]],
      });
      const before = (
        await pool.query('SELECT * FROM resource_weekly_bingo WHERE owner_id = $1', [f.owner])
      ).rows;
      const journey = await new ResourceJourneyRepository(f.db).materialize(f.owner, '2026-10-21', {
        timeZone,
        supportPlan: {
          supportPlanId: randomUUID(),
          version: 1,
          status: 'ACTIVE',
          activatedAt: '2026-10-19T00:00:00Z',
          domains: ['ANXIETY_SYMPTOMS'],
          selectedResourceIds: f.resourceIds,
        },
      });
      expect(journey?.bingo.map((item) => item.resourceId)).toEqual([f.resourceIds[1]]);
      const after = (
        await pool.query('SELECT * FROM resource_weekly_bingo WHERE owner_id = $1', [f.owner])
      ).rows;
      expect(after).toEqual(before);
    });
  });
});
