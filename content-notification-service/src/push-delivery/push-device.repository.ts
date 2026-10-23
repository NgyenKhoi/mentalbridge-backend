import { Inject, Injectable } from '@nestjs/common';

import { DATABASE_SERVICE_TOKEN } from '../application.tokens.js';
import type { DatabaseService } from '../database/database.service.js';
import type {
  PushDeliveryClaim,
  PushDeviceRegistration,
  PushDeviceRegistrationInput,
} from './push-device.types.js';
import type { EncryptedPushToken } from './push-token.crypto.js';

interface DeviceRow {
  readonly installation_id: string;
  readonly platform: 'ANDROID' | 'IOS';
  readonly provider: 'EXPO';
  readonly permission_status: 'GRANTED' | 'DENIED';
  readonly status: 'ACTIVE' | 'DISABLED' | 'REVOKED' | 'INVALID';
  readonly app_version: string | null;
  readonly last_registered_at: Date | string;
  readonly version: number | string;
}

const mapDevice = (row: DeviceRow): PushDeviceRegistration => ({
  installationId: row.installation_id,
  platform: row.platform,
  provider: row.provider,
  permissionStatus: row.permission_status,
  status: row.status,
  appVersion: row.app_version,
  lastRegisteredAt: new Date(row.last_registered_at).toISOString(),
  version: Number(row.version),
});

@Injectable()
export class PushDeviceRepository {
  constructor(@Inject(DATABASE_SERVICE_TOKEN) private readonly db: DatabaseService) {}

  async register(
    ownerId: string,
    input: PushDeviceRegistrationInput,
    encrypted: EncryptedPushToken,
  ): Promise<PushDeviceRegistration> {
    return this.db.withTransaction(async (client) => {
      await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [
        `PUSH_TOKEN:${encrypted.hash}`,
      ]);
      await client.query(
        `UPDATE push_device_registration
         SET status = 'REVOKED', revoked_at = now(), updated_at = now(), version = version + 1
         WHERE provider = 'EXPO' AND provider_token_hash = $1
           AND (owner_id <> $2 OR installation_id <> $3) AND status IN ('ACTIVE', 'DISABLED')`,
        [encrypted.hash, ownerId, input.installationId],
      );
      const result = await client.query<DeviceRow>(
        `INSERT INTO push_device_registration
           (owner_id, installation_id, platform, provider, provider_token_ciphertext,
            provider_token_iv, provider_token_auth_tag, provider_token_hash,
            permission_status, status, app_version, last_registered_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9::varchar,
                 CASE WHEN $9::varchar = 'GRANTED' THEN 'ACTIVE' ELSE 'DISABLED' END, $10, now())
         ON CONFLICT (owner_id, installation_id) DO UPDATE SET
           platform = EXCLUDED.platform,
           provider = EXCLUDED.provider,
           provider_token_ciphertext = EXCLUDED.provider_token_ciphertext,
           provider_token_iv = EXCLUDED.provider_token_iv,
           provider_token_auth_tag = EXCLUDED.provider_token_auth_tag,
           provider_token_hash = EXCLUDED.provider_token_hash,
           permission_status = EXCLUDED.permission_status,
           status = CASE WHEN EXCLUDED.permission_status = 'GRANTED' THEN 'ACTIVE' ELSE 'DISABLED' END,
           app_version = EXCLUDED.app_version,
           last_registered_at = now(), revoked_at = NULL, invalidated_at = NULL,
           updated_at = now(), version = push_device_registration.version + 1
         RETURNING installation_id, platform, provider, permission_status, status,
                   app_version, last_registered_at, version`,
        [
          ownerId,
          input.installationId,
          input.platform,
          input.provider,
          encrypted.ciphertext,
          encrypted.iv,
          encrypted.authTag,
          encrypted.hash,
          input.permissionStatus,
          input.appVersion ?? null,
        ],
      );
      return mapDevice(result.rows[0]);
    });
  }

  async revoke(ownerId: string, installationId: string): Promise<boolean> {
    const result = await this.db.query(
      `UPDATE push_device_registration
       SET status = 'REVOKED', revoked_at = COALESCE(revoked_at, now()), invalidated_at = NULL,
           updated_at = now(), version = version + 1
       WHERE owner_id = $1 AND installation_id = $2 AND status <> 'REVOKED'`,
      [ownerId, installationId],
    );
    if ((result.rowCount ?? 0) > 0) return true;
    const existing = await this.db.query(
      'SELECT 1 FROM push_device_registration WHERE owner_id = $1 AND installation_id = $2',
      [ownerId, installationId],
    );
    return Boolean(existing.rows[0]);
  }

  async materializeCandidates(limit: number): Promise<number> {
    const result = await this.db.query(
      `INSERT INTO push_delivery_attempt (notification_id, device_registration_id, state, failure_code)
       SELECT n.id, d.id,
              CASE
                WHEN NOT p.notifications_enabled OR NOT p.channel_push_enabled THEN 'SUPPRESSED'
                WHEN NOT (CASE
                  WHEN n.category = 'JOURNAL_REMINDER' THEN p.group_journal_reminder_enabled
                  WHEN n.category = 'EMOTION_CHECKIN_REMINDER' THEN p.group_emotion_check_in_enabled
                  WHEN n.category IN ('JOURNAL_STREAK_MILESTONE', 'EMOTION_STREAK_MILESTONE', 'STREAK_MILESTONE') THEN p.group_streak_milestone_enabled
                  WHEN n.category IN ('ASSESSMENT_REASSESSMENT', 'ASSESSMENT_RESULT_AVAILABLE', 'ASSESSMENT') THEN p.group_screening_reassessment_enabled
                  WHEN n.category IN ('APPOINTMENT', 'MESSAGE', 'CHAT') THEN p.group_appointment_message_enabled
                  WHEN n.category IN ('COMMUNITY_COMMENT', 'COMMUNITY_REPLY', 'COMMUNITY_REACTION', 'COMMUNITY_MESSAGE', 'COMMUNITY_REQUEST', 'COMMUNITY_INVITATION') THEN p.group_community_interaction_enabled
                  WHEN n.category IN ('SYSTEM_RESOURCE', 'SUPPORT_GUIDE_AVAILABLE', 'ACCOUNT_STATUS', 'SYSTEM') THEN p.group_resource_system_enabled
                  ELSE true
                END) THEN 'SUPPRESSED'
                WHEN d.status <> 'ACTIVE' OR d.permission_status <> 'GRANTED' THEN 'SUPPRESSED'
                ELSE 'PENDING'
              END,
              CASE
                WHEN NOT p.notifications_enabled OR NOT p.channel_push_enabled THEN 'PREFERENCE_DISABLED'
                WHEN NOT (CASE
                  WHEN n.category = 'JOURNAL_REMINDER' THEN p.group_journal_reminder_enabled
                  WHEN n.category = 'EMOTION_CHECKIN_REMINDER' THEN p.group_emotion_check_in_enabled
                  WHEN n.category IN ('JOURNAL_STREAK_MILESTONE', 'EMOTION_STREAK_MILESTONE', 'STREAK_MILESTONE') THEN p.group_streak_milestone_enabled
                  WHEN n.category IN ('ASSESSMENT_REASSESSMENT', 'ASSESSMENT_RESULT_AVAILABLE', 'ASSESSMENT') THEN p.group_screening_reassessment_enabled
                  WHEN n.category IN ('APPOINTMENT', 'MESSAGE', 'CHAT') THEN p.group_appointment_message_enabled
                  WHEN n.category IN ('COMMUNITY_COMMENT', 'COMMUNITY_REPLY', 'COMMUNITY_REACTION', 'COMMUNITY_MESSAGE', 'COMMUNITY_REQUEST', 'COMMUNITY_INVITATION') THEN p.group_community_interaction_enabled
                  WHEN n.category IN ('SYSTEM_RESOURCE', 'SUPPORT_GUIDE_AVAILABLE', 'ACCOUNT_STATUS', 'SYSTEM') THEN p.group_resource_system_enabled
                  ELSE true
                END) THEN 'CONTENT_GROUP_DISABLED'
                WHEN d.status <> 'ACTIVE' OR d.permission_status <> 'GRANTED' THEN 'DEVICE_DISABLED'
                ELSE NULL
              END
       FROM notification n
       JOIN notification_preference p ON p.user_id = n.recipient_id
       JOIN push_device_registration d ON d.owner_id = n.recipient_id
       WHERE n.delivery_state = 'DELIVERED' AND n.deleted_at IS NULL AND n.expires_at > now()
         AND n.created_at >= d.last_registered_at
         AND NOT EXISTS (
           SELECT 1 FROM push_delivery_attempt a
           WHERE a.notification_id = n.id AND a.device_registration_id = d.id
         )
       ORDER BY n.created_at, n.id
       LIMIT $1
       ON CONFLICT DO NOTHING`,
      [limit],
    );
    return result.rowCount ?? 0;
  }

  async settleIneligible(): Promise<void> {
    await this.db.query(
      `UPDATE push_delivery_attempt a
       SET state = 'SUPPRESSED',
           failure_code = CASE
             WHEN d.status = 'INVALID' THEN 'DEVICE_NOT_REGISTERED'
             WHEN d.status <> 'ACTIVE' OR d.permission_status <> 'GRANTED' THEN 'DEVICE_DISABLED'
             WHEN NOT p.notifications_enabled OR NOT p.channel_push_enabled THEN 'PREFERENCE_DISABLED'
             ELSE 'CONTENT_GROUP_DISABLED'
           END,
           updated_at = now()
       FROM push_device_registration d, notification n, notification_preference p
       WHERE d.id = a.device_registration_id AND n.id = a.notification_id
         AND p.user_id = n.recipient_id AND a.state IN ('PENDING', 'FAILED')
         AND (d.status <> 'ACTIVE' OR d.permission_status <> 'GRANTED'
              OR NOT p.notifications_enabled OR NOT p.channel_push_enabled
              OR NOT (CASE
                WHEN n.category = 'JOURNAL_REMINDER' THEN p.group_journal_reminder_enabled
                WHEN n.category = 'EMOTION_CHECKIN_REMINDER' THEN p.group_emotion_check_in_enabled
                WHEN n.category IN ('JOURNAL_STREAK_MILESTONE', 'EMOTION_STREAK_MILESTONE', 'STREAK_MILESTONE') THEN p.group_streak_milestone_enabled
                WHEN n.category IN ('ASSESSMENT_REASSESSMENT', 'ASSESSMENT_RESULT_AVAILABLE', 'ASSESSMENT') THEN p.group_screening_reassessment_enabled
                WHEN n.category IN ('APPOINTMENT', 'MESSAGE', 'CHAT') THEN p.group_appointment_message_enabled
                WHEN n.category IN ('COMMUNITY_COMMENT', 'COMMUNITY_REPLY', 'COMMUNITY_REACTION', 'COMMUNITY_MESSAGE', 'COMMUNITY_REQUEST', 'COMMUNITY_INVITATION') THEN p.group_community_interaction_enabled
                WHEN n.category IN ('SYSTEM_RESOURCE', 'SUPPORT_GUIDE_AVAILABLE', 'ACCOUNT_STATUS', 'SYSTEM') THEN p.group_resource_system_enabled
                ELSE true
              END))`,
    );
    await this.db.query(
      `UPDATE push_delivery_attempt
       SET state = 'UNKNOWN', failure_code = 'WORKER_OUTCOME_UNKNOWN', updated_at = now()
       WHERE state = 'PROCESSING' AND claimed_at < now() - interval '5 minutes'`,
    );
  }

  async claim(limit: number): Promise<readonly PushDeliveryClaim[]> {
    return this.db.withTransaction(async (client) => {
      const result = await client.query<
        PushDeliveryClaim & {
          device_id: string;
          token_ciphertext: string;
          token_iv: string;
          token_auth_tag: string;
          notification_id: string;
          action_type: string | null;
          action_target_id: string | null;
          attempt_count: number | string;
        }
      >(
        `WITH due AS (
           SELECT a.id
           FROM push_delivery_attempt a
           JOIN push_device_registration d ON d.id = a.device_registration_id
           JOIN notification n ON n.id = a.notification_id
           JOIN notification_preference p ON p.user_id = n.recipient_id
           WHERE a.state IN ('PENDING', 'FAILED') AND a.available_at <= now()
             AND a.attempt_count < 3 AND d.status = 'ACTIVE' AND d.permission_status = 'GRANTED'
             AND p.notifications_enabled AND p.channel_push_enabled
             AND (CASE
               WHEN n.category = 'JOURNAL_REMINDER' THEN p.group_journal_reminder_enabled
               WHEN n.category = 'EMOTION_CHECKIN_REMINDER' THEN p.group_emotion_check_in_enabled
               WHEN n.category IN ('JOURNAL_STREAK_MILESTONE', 'EMOTION_STREAK_MILESTONE', 'STREAK_MILESTONE') THEN p.group_streak_milestone_enabled
               WHEN n.category IN ('ASSESSMENT_REASSESSMENT', 'ASSESSMENT_RESULT_AVAILABLE', 'ASSESSMENT') THEN p.group_screening_reassessment_enabled
               WHEN n.category IN ('APPOINTMENT', 'MESSAGE', 'CHAT') THEN p.group_appointment_message_enabled
               WHEN n.category IN ('COMMUNITY_COMMENT', 'COMMUNITY_REPLY', 'COMMUNITY_REACTION', 'COMMUNITY_MESSAGE', 'COMMUNITY_REQUEST', 'COMMUNITY_INVITATION') THEN p.group_community_interaction_enabled
               WHEN n.category IN ('SYSTEM_RESOURCE', 'SUPPORT_GUIDE_AVAILABLE', 'ACCOUNT_STATUS', 'SYSTEM') THEN p.group_resource_system_enabled
               ELSE true
             END)
             AND NOT (p.quiet_hours_enabled AND (
               CASE WHEN p.quiet_hours_start < p.quiet_hours_end
                 THEN (now() AT TIME ZONE p.time_zone)::time >= p.quiet_hours_start
                      AND (now() AT TIME ZONE p.time_zone)::time < p.quiet_hours_end
                 ELSE (now() AT TIME ZONE p.time_zone)::time >= p.quiet_hours_start
                      OR (now() AT TIME ZONE p.time_zone)::time < p.quiet_hours_end
               END
             ))
           ORDER BY a.available_at, a.id
           FOR UPDATE OF a SKIP LOCKED
           LIMIT $1
         )
         UPDATE push_delivery_attempt a
         SET state = 'PROCESSING', attempt_count = attempt_count + 1, claimed_at = now(),
             last_attempt_at = now(), failure_code = NULL, updated_at = now()
         FROM due, push_device_registration d, notification n
         WHERE a.id = due.id AND d.id = a.device_registration_id AND n.id = a.notification_id
         RETURNING a.id, d.id AS device_id, d.provider_token_ciphertext AS token_ciphertext,
                   d.provider_token_iv AS token_iv, d.provider_token_auth_tag AS token_auth_tag,
                   n.title, n.id AS notification_id, n.action_type, n.action_target_id,
                   a.attempt_count`,
        [limit],
      );
      return result.rows.map((row) => ({
        id: row.id,
        deviceId: row.device_id,
        tokenCiphertext: row.token_ciphertext,
        tokenIv: row.token_iv,
        tokenAuthTag: row.token_auth_tag,
        title: row.title,
        notificationId: row.notification_id,
        actionType: row.action_type,
        actionTargetId: row.action_target_id,
        attemptCount: Number(row.attempt_count),
      }));
    });
  }

  async accepted(id: string, ticketId: string): Promise<void> {
    await this.db.query(
      `UPDATE push_delivery_attempt
       SET state = 'ACCEPTED', provider_ticket_id = $2, accepted_at = now(), updated_at = now()
       WHERE id = $1 AND state = 'PROCESSING'`,
      [id, ticketId],
    );
  }

  async failed(id: string, code: string, retryable: boolean): Promise<void> {
    await this.db.query(
      `UPDATE push_delivery_attempt
       SET state = CASE WHEN $3 AND attempt_count < 3 THEN 'FAILED' ELSE 'SUPPRESSED' END,
           failure_code = $2,
           available_at = CASE WHEN $3 AND attempt_count < 3
             THEN now() + make_interval(secs => (30 * power(2, attempt_count - 1))::int)
             ELSE available_at END,
           updated_at = now()
       WHERE id = $1 AND state = 'PROCESSING'`,
      [id, code, retryable],
    );
  }

  async unknown(id: string, code: string): Promise<void> {
    await this.db.query(
      `UPDATE push_delivery_attempt
       SET state = 'UNKNOWN', failure_code = $2, updated_at = now()
       WHERE id = $1 AND state = 'PROCESSING'`,
      [id, code],
    );
  }

  async invalidateDevice(deviceId: string): Promise<void> {
    await this.db.withTransaction(async (client) => {
      await client.query(
        `UPDATE push_device_registration
         SET status = 'INVALID', invalidated_at = now(), revoked_at = NULL,
             updated_at = now(), version = version + 1
         WHERE id = $1 AND status = 'ACTIVE'`,
        [deviceId],
      );
      await client.query(
        `UPDATE push_delivery_attempt
         SET state = 'SUPPRESSED', failure_code = 'DEVICE_NOT_REGISTERED', updated_at = now()
         WHERE device_registration_id = $1 AND state IN ('PENDING', 'FAILED')`,
        [deviceId],
      );
    });
  }
}
