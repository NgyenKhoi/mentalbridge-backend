import { Inject, Injectable } from '@nestjs/common';

import { DATABASE_SERVICE_TOKEN } from '../application.tokens.js';
import type { DatabaseService } from '../database/database.service.js';
import type {
  PushDeliveryClaim,
  PushReceiptClaim,
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

interface StoredDeviceRow extends DeviceRow {
  readonly provider_token_hash: string;
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
      const lockKeys = [
        `PUSH_INSTALLATION:${input.installationId}`,
        `PUSH_TOKEN:${encrypted.hash}`,
      ].sort();
      for (const lockKey of lockKeys)
        await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [lockKey]);
      const current = await client.query<StoredDeviceRow>(
        `SELECT installation_id, platform, provider, provider_token_hash, permission_status,
                status, app_version, last_registered_at, version
         FROM push_device_registration
         WHERE owner_id = $1 AND installation_id = $2
         FOR UPDATE`,
        [ownerId, input.installationId],
      );
      const desiredStatus = input.permissionStatus === 'GRANTED' ? 'ACTIVE' : 'DISABLED';
      const existing: StoredDeviceRow | undefined = current.rows.at(0);
      if (
        existing &&
        existing.platform === input.platform &&
        existing.provider_token_hash === encrypted.hash &&
        existing.permission_status === input.permissionStatus &&
        existing.status === desiredStatus &&
        existing.app_version === (input.appVersion ?? null)
      )
        return mapDevice(existing);
      await client.query(
        `UPDATE push_device_registration
         SET status = 'REVOKED', revoked_at = now(), updated_at = now(), version = version + 1
         WHERE provider = 'EXPO' AND status IN ('ACTIVE', 'DISABLED')
           AND (installation_id = $1 OR provider_token_hash = $2)
           AND NOT (owner_id = $3 AND installation_id = $1)`,
        [input.installationId, encrypted.hash, ownerId],
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
           delivery_eligible_from_at = CASE
             WHEN push_device_registration.status IN ('REVOKED', 'INVALID')
               OR (push_device_registration.permission_status <> 'GRANTED'
                   AND EXCLUDED.permission_status = 'GRANTED')
               THEN now()
             ELSE push_device_registration.delivery_eligible_from_at
           END,
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

  async consumeCommandRateLimit(
    ownerId: string,
    limit: number,
    windowSeconds: number,
  ): Promise<boolean> {
    const result = await this.db.query(
      `INSERT INTO push_device_command_rate_limit
         (owner_id, window_started_at, request_count, updated_at)
       VALUES ($1, now(), 1, now())
       ON CONFLICT (owner_id) DO UPDATE SET
         window_started_at = CASE
           WHEN push_device_command_rate_limit.window_started_at <= now() - make_interval(secs => $3)
             THEN now()
           ELSE push_device_command_rate_limit.window_started_at
         END,
         request_count = CASE
           WHEN push_device_command_rate_limit.window_started_at <= now() - make_interval(secs => $3)
             THEN 1
           ELSE push_device_command_rate_limit.request_count + 1
         END,
         updated_at = now()
       WHERE push_device_command_rate_limit.window_started_at <= now() - make_interval(secs => $3)
          OR push_device_command_rate_limit.request_count < $2
       RETURNING request_count`,
      [ownerId, limit, windowSeconds],
    );
    return Boolean(result.rows[0]);
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
         AND n.created_at >= d.delivery_eligible_from_at
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
             WHEN n.deleted_at IS NOT NULL THEN 'NOTIFICATION_DELETED'
             WHEN n.expires_at <= now() THEN 'NOTIFICATION_EXPIRED'
             WHEN n.delivery_state <> 'DELIVERED' THEN 'NOTIFICATION_UNAVAILABLE'
             WHEN d.status = 'INVALID' THEN 'DEVICE_NOT_REGISTERED'
             WHEN d.status <> 'ACTIVE' OR d.permission_status <> 'GRANTED' THEN 'DEVICE_DISABLED'
             WHEN NOT p.notifications_enabled OR NOT p.channel_push_enabled THEN 'PREFERENCE_DISABLED'
             ELSE 'CONTENT_GROUP_DISABLED'
           END,
           updated_at = now()
       FROM push_device_registration d, notification n, notification_preference p
       WHERE d.id = a.device_registration_id AND n.id = a.notification_id
         AND p.user_id = n.recipient_id AND a.state IN ('PENDING', 'FAILED')
         AND (n.deleted_at IS NOT NULL OR n.expires_at <= now()
              OR n.delivery_state <> 'DELIVERED'
              OR d.status <> 'ACTIVE' OR d.permission_status <> 'GRANTED'
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
          reminder_recipient_id: string | null;
          reminder_appointment_id: string | null;
          reminder_appointment_version: number | string | null;
          reminder_modality: 'IN_APP_CHAT' | 'IN_APP_VIDEO' | null;
          reminder_scheduled_start_at: Date | string | null;
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
             AND n.delivery_state = 'DELIVERED' AND n.deleted_at IS NULL AND n.expires_at > now()
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
         SET state = 'PROCESSING', attempt_count = a.attempt_count + 1, claimed_at = now(),
             last_attempt_at = now(), failure_code = NULL, updated_at = now()
         FROM due, push_device_registration d, notification n
         LEFT JOIN appointment_push_reminder r ON r.notification_id=n.id
         WHERE a.id = due.id AND d.id = a.device_registration_id AND n.id = a.notification_id
         RETURNING a.id, d.id AS device_id, d.provider_token_ciphertext AS token_ciphertext,
                   d.provider_token_iv AS token_iv, d.provider_token_auth_tag AS token_auth_tag,
                   n.title, n.id AS notification_id, n.action_type, n.action_target_id,
                   a.attempt_count, r.recipient_id AS reminder_recipient_id,
                   r.appointment_id AS reminder_appointment_id,
                   r.appointment_version AS reminder_appointment_version,
                   r.modality AS reminder_modality,
                   r.scheduled_start_at AS reminder_scheduled_start_at`,
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
        appointmentReminder:
          row.reminder_recipient_id &&
          row.reminder_appointment_id &&
          row.reminder_appointment_version !== null &&
          row.reminder_modality &&
          row.reminder_scheduled_start_at
            ? {
                recipientId: row.reminder_recipient_id,
                appointmentId: row.reminder_appointment_id,
                appointmentVersion: Number(row.reminder_appointment_version),
                modality: row.reminder_modality,
                scheduledStartAt: new Date(row.reminder_scheduled_start_at),
              }
            : null,
      }));
    });
  }

  async isDeliverable(id: string): Promise<boolean> {
    const result = await this.db.query(
      `SELECT 1
       FROM push_delivery_attempt a
       JOIN push_device_registration d ON d.id = a.device_registration_id
       JOIN notification n ON n.id = a.notification_id
       JOIN notification_preference p ON p.user_id = n.recipient_id
       WHERE a.id = $1 AND a.state = 'PROCESSING'
         AND n.delivery_state = 'DELIVERED' AND n.deleted_at IS NULL AND n.expires_at > now()
         AND d.status = 'ACTIVE' AND d.permission_status = 'GRANTED'
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
         END)`,
      [id],
    );
    return Boolean(result.rows[0]);
  }

  async suppressClaim(id: string, code: string): Promise<void> {
    await this.db.query(
      `UPDATE push_delivery_attempt
       SET state = 'SUPPRESSED', failure_code = $2, updated_at = now()
       WHERE id = $1 AND state = 'PROCESSING'`,
      [id, code],
    );
  }

  async deferClaim(id: string, availableAt: Date, code: string): Promise<void> {
    await this.db.query(
      `UPDATE push_delivery_attempt
       SET state='FAILED', available_at=$2, failure_code=$3, updated_at=now()
       WHERE id=$1 AND state='PROCESSING'`,
      [id, availableAt, code],
    );
  }

  async accepted(id: string, ticketId: string, receiptDelaySeconds: number): Promise<void> {
    await this.db.query(
      `UPDATE push_delivery_attempt
       SET state = 'ACCEPTED', provider_ticket_id = $2, accepted_at = now(),
           available_at = now() + make_interval(secs => $3), updated_at = now()
       WHERE id = $1 AND state = 'PROCESSING'`,
      [id, ticketId, receiptDelaySeconds],
    );
  }

  async claimReceipts(
    limit: number,
    retryDelaySeconds: number,
  ): Promise<readonly PushReceiptClaim[]> {
    return this.db.withTransaction(async (client) => {
      const result = await client.query<{
        id: string;
        device_id: string;
        provider_ticket_id: string;
        receipt_attempt_count: number | string;
      }>(
        `WITH due AS (
           SELECT id
           FROM push_delivery_attempt
           WHERE state = 'ACCEPTED' AND available_at <= now() AND receipt_attempt_count < 3
           ORDER BY available_at, id
           FOR UPDATE SKIP LOCKED
           LIMIT $1
         )
         UPDATE push_delivery_attempt a
         SET receipt_attempt_count = receipt_attempt_count + 1,
             receipt_checked_at = now(),
             available_at = now() + make_interval(secs => $2),
             updated_at = now()
         FROM due
         WHERE a.id = due.id
         RETURNING a.id, a.device_registration_id AS device_id, a.provider_ticket_id,
                   a.receipt_attempt_count`,
        [limit, retryDelaySeconds],
      );
      return result.rows.map((row) => ({
        id: row.id,
        deviceId: row.device_id,
        providerTicketId: row.provider_ticket_id,
        receiptAttemptCount: Number(row.receipt_attempt_count),
      }));
    });
  }

  async confirmReceipt(id: string): Promise<void> {
    await this.db.query(
      `UPDATE push_delivery_attempt
       SET state = 'CONFIRMED', failure_code = NULL, receipt_checked_at = now(), updated_at = now()
       WHERE id = $1 AND state = 'ACCEPTED'`,
      [id],
    );
  }

  async failReceipt(id: string, code: string): Promise<void> {
    await this.db.query(
      `UPDATE push_delivery_attempt
       SET state = 'SUPPRESSED', failure_code = $2, receipt_checked_at = now(), updated_at = now()
       WHERE id = $1 AND state = 'ACCEPTED'`,
      [id, code],
    );
  }

  async retryReceipt(id: string, code: string): Promise<void> {
    await this.db.query(
      `UPDATE push_delivery_attempt
       SET state = CASE WHEN receipt_attempt_count < 3 THEN 'ACCEPTED' ELSE 'UNKNOWN' END,
           failure_code = $2, receipt_checked_at = now(), updated_at = now()
       WHERE id = $1 AND state = 'ACCEPTED'`,
      [id, code],
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
