import { createHash } from 'node:crypto';
import { Inject, Injectable } from '@nestjs/common';

import { DATABASE_SERVICE_TOKEN } from '../application.tokens.js';
import type { DatabaseService } from '../database/database.service.js';
import type {
  AppointmentPushReminderRow,
  AppointmentReminderRow,
  AppointmentStatusChangedEvent,
  ClaimedAppointmentPushReminder,
  ClaimedAppointmentReminder,
} from './appointment-reminder.types.js';

export class AppointmentPushReminderConflictError extends Error {}

function providerKey(ownerId: string, appointmentId: string, version: number): string {
  const value = Buffer.from(
    createHash('sha256')
      .update(`${ownerId}:${appointmentId}:${String(version)}`)
      .digest()
      .subarray(0, 16),
  );
  value[6] = (value[6] & 0x0f) | 0x50;
  value[8] = (value[8] & 0x3f) | 0x80;
  const hex = value.toString('hex');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function claimed(row: AppointmentReminderRow): ClaimedAppointmentReminder {
  return {
    id: row.id,
    recipientId: row.recipient_id,
    appointmentId: row.appointment_id,
    appointmentVersion: Number(row.appointment_version),
    modality: row.modality,
    scheduledStartAt: new Date(row.scheduled_start_at),
    targetAt: new Date(row.target_at),
    dueAt: new Date(row.due_at),
    attemptCount: row.attempt_count,
    firstAttemptAt: new Date(row.first_attempt_at ?? new Date()),
    providerIdempotencyKey: row.provider_idempotency_key,
  };
}

const RETURNING = `id, recipient_id, appointment_id, appointment_version,
  appointment_status, modality, scheduled_start_at, target_at, due_at,
  attempt_count, first_attempt_at, provider_idempotency_key`;

const PUSH_RETURNING = `id, recipient_id, appointment_id, appointment_version,
  modality, scheduled_start_at, target_at, due_at, attempt_count, first_attempt_at`;

function claimedPush(row: AppointmentPushReminderRow): ClaimedAppointmentPushReminder {
  return {
    id: row.id,
    recipientId: row.recipient_id,
    appointmentId: row.appointment_id,
    appointmentVersion: Number(row.appointment_version),
    modality: row.modality,
    scheduledStartAt: new Date(row.scheduled_start_at),
    targetAt: new Date(row.target_at),
    dueAt: new Date(row.due_at),
    attemptCount: row.attempt_count,
    firstAttemptAt: new Date(row.first_attempt_at ?? new Date()),
  };
}

@Injectable()
export class AppointmentReminderRepository {
  constructor(@Inject(DATABASE_SERVICE_TOKEN) private readonly db: DatabaseService) {}

  async apply(event: AppointmentStatusChangedEvent, observedAt: Date): Promise<void> {
    await this.db.withTransaction(async (client) => {
      const checkpoint = await client.query<{ latest_version: string | number }>(
        `INSERT INTO appointment_reminder_checkpoint
           (appointment_id, latest_version, latest_status, last_message_id, updated_at)
         VALUES ($1,$2,$3,$4,$5)
         ON CONFLICT (appointment_id) DO UPDATE SET
           latest_version=EXCLUDED.latest_version, latest_status=EXCLUDED.latest_status,
           last_message_id=EXCLUDED.last_message_id, updated_at=EXCLUDED.updated_at
         WHERE appointment_reminder_checkpoint.latest_version < EXCLUDED.latest_version
         RETURNING latest_version`,
        [
          event.payload.appointmentId,
          event.aggregateVersion,
          event.payload.status,
          event.messageId,
          observedAt,
        ],
      );
      if (!checkpoint.rows.length) return;
      await client.query(
        `UPDATE appointment_email_reminder
         SET delivery_state='INVALIDATED', invalidated_at=$3, updated_at=$3,
             failure_code='APPOINTMENT_VERSION_INVALIDATED'
         WHERE appointment_id=$1 AND appointment_version<$2
           AND delivery_state IN ('PENDING','PROCESSING')`,
        [event.payload.appointmentId, event.aggregateVersion, observedAt],
      );
      const invalidatedPush = await client.query<{ notification_id: string | null }>(
        `UPDATE appointment_push_reminder
         SET state='INVALIDATED', invalidated_at=$3, updated_at=$3,
             terminal_at=$3, failure_code='APPOINTMENT_VERSION_INVALIDATED'
         WHERE appointment_id=$1 AND appointment_version<$2
           AND state IN ('PENDING','PROCESSING','MATERIALIZED')
         RETURNING notification_id`,
        [event.payload.appointmentId, event.aggregateVersion, observedAt],
      );
      const notificationIds = invalidatedPush.rows.flatMap((row) =>
        row.notification_id ? [row.notification_id] : [],
      );
      if (notificationIds.length > 0) {
        await client.query(
          `UPDATE notification
           SET delivery_state='CANCELLED', deleted_at=COALESCE(deleted_at,$2), version=version+1
           WHERE id=ANY($1::uuid[]) AND delivery_state='DELIVERED'`,
          [notificationIds, observedAt],
        );
        await client.query(
          `UPDATE push_delivery_attempt
           SET state='SUPPRESSED', failure_code='APPOINTMENT_VERSION_INVALIDATED', updated_at=$2
           WHERE notification_id=ANY($1::uuid[]) AND state IN ('PENDING','FAILED','PROCESSING')`,
          [notificationIds, observedAt],
        );
      }
      if (event.payload.status !== 'CONFIRMED') return;

      const startAt = new Date(event.payload.scheduledStartAt);
      const targetAt = new Date(startAt.getTime() - 60 * 60 * 1000);
      const state = observedAt < startAt ? 'PENDING' : 'EXPIRED';
      const dueAt = observedAt >= startAt ? startAt : targetAt > observedAt ? targetAt : observedAt;
      await client.query(
        `INSERT INTO appointment_email_reminder (
           recipient_id, appointment_id, appointment_version, appointment_status,
           modality, scheduled_start_at, target_at, due_at, delivery_state,
           provider_idempotency_key, failure_code
         ) VALUES ($1,$2,$3,'CONFIRMED',$4,$5,$6,$7,$8,$9,$10)
         ON CONFLICT (recipient_id, appointment_id, appointment_version) DO NOTHING`,
        [
          event.payload.ownerAccountId,
          event.payload.appointmentId,
          event.aggregateVersion,
          event.payload.modality,
          startAt,
          targetAt,
          dueAt,
          state,
          providerKey(
            event.payload.ownerAccountId,
            event.payload.appointmentId,
            event.aggregateVersion,
          ),
          state === 'EXPIRED' ? 'LATE_CONFIRMATION' : null,
        ],
      );
      await client.query(
        `INSERT INTO appointment_push_reminder (
           recipient_id, appointment_id, appointment_version, modality,
           scheduled_start_at, target_at, due_at, state, failure_code, terminal_at
         ) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)
         ON CONFLICT (recipient_id, appointment_id, appointment_version) DO NOTHING`,
        [
          event.payload.ownerAccountId,
          event.payload.appointmentId,
          event.aggregateVersion,
          event.payload.modality,
          startAt,
          targetAt,
          dueAt,
          state === 'EXPIRED' ? 'EXPIRED' : 'PENDING',
          state === 'EXPIRED' ? 'LATE_CONFIRMATION' : null,
          state === 'EXPIRED' ? observedAt : null,
        ],
      );
    });
  }

  async reconcilePushLeases(now: Date): Promise<number> {
    const result = await this.db.query(
      `UPDATE appointment_push_reminder
       SET state='FAILED', next_attempt_at=NULL,
           failure_code='PROCESSING_LEASE_EXHAUSTED', terminal_at=$1, updated_at=$1
       WHERE state='PROCESSING' AND attempt_count>=3 AND next_attempt_at<=$1`,
      [now],
    );
    return result.rowCount ?? 0;
  }

  async claimPushDue(now: Date, limit: number): Promise<readonly ClaimedAppointmentPushReminder[]> {
    return this.db.withTransaction(async (client) => {
      const selected = await client.query<{ id: string }>(
        `SELECT id FROM appointment_push_reminder
         WHERE state IN ('PENDING','PROCESSING')
           AND coalesce(next_attempt_at,due_at)<=$1
           AND attempt_count<3
         ORDER BY coalesce(next_attempt_at,due_at), id
         FOR UPDATE SKIP LOCKED LIMIT $2`,
        [now, limit],
      );
      if (!selected.rows.length) return [];
      const result = await client.query<AppointmentPushReminderRow>(
        `UPDATE appointment_push_reminder SET
           state='PROCESSING', attempt_count=attempt_count+1,
           first_attempt_at=coalesce(first_attempt_at,$2), last_attempt_at=$2,
           next_attempt_at=$2 + interval '30 seconds', updated_at=$2
         WHERE id=ANY($1::uuid[]) RETURNING ${PUSH_RETURNING}`,
        [selected.rows.map((row) => row.id), now],
      );
      return result.rows.map(claimedPush);
    });
  }

  async materializePush(reminder: ClaimedAppointmentPushReminder, now: Date): Promise<boolean> {
    return this.db.withTransaction(async (client) => {
      const current = await client.query<{ state: string }>(
        `SELECT r.state
         FROM appointment_push_reminder r
         JOIN appointment_reminder_checkpoint c ON c.appointment_id=r.appointment_id
         WHERE r.id=$1 AND r.state='PROCESSING'
           AND c.latest_version=r.appointment_version AND c.latest_status='CONFIRMED'
         FOR UPDATE OF r`,
        [reminder.id],
      );
      if (!current.rows[0]) {
        await client.query(
          `UPDATE appointment_push_reminder
           SET state='INVALIDATED', invalidated_at=$2, failure_code='APPOINTMENT_NOT_ELIGIBLE',
               next_attempt_at=NULL, terminal_at=$2, updated_at=$2
           WHERE id=$1 AND state='PROCESSING'`,
          [reminder.id, now],
        );
        return false;
      }
      const sourceIdentity = `${reminder.appointmentId}:${String(reminder.appointmentVersion)}`;
      const fingerprint = createHash('sha256')
        .update(
          JSON.stringify({
            recipientId: reminder.recipientId,
            appointmentId: reminder.appointmentId,
            appointmentVersion: reminder.appointmentVersion,
            scheduledStartAt: reminder.scheduledStartAt.toISOString(),
            modality: reminder.modality,
          }),
        )
        .digest('hex');
      const inserted = await client.query<{ id: string }>(
        `INSERT INTO notification (
           recipient_id, category, title, body, action_type, action_target_id,
           priority, expires_at, occurred_at, source, source_identity,
           request_fingerprint, delivery_state, created_at
         ) VALUES ($1,'APPOINTMENT','Sắp đến giờ hẹn của bạn',
           'Bạn có một lịch hẹn sắp bắt đầu trong MentalBridge.',
           'OPEN_APPOINTMENT',$2,'HIGH',$3,$4,
           'CONSULTATION_APPOINTMENT_REMINDER_V1',$5,$6,'DELIVERED',$4)
         ON CONFLICT (recipient_id, source, source_identity) DO NOTHING
         RETURNING id`,
        [
          reminder.recipientId,
          reminder.appointmentId,
          reminder.scheduledStartAt,
          now,
          sourceIdentity,
          fingerprint,
        ],
      );
      let notificationId = inserted.rows[0]?.id;
      if (!notificationId) {
        const existing = await client.query<{ id: string; request_fingerprint: string }>(
          `SELECT id, request_fingerprint FROM notification
           WHERE recipient_id=$1 AND source='CONSULTATION_APPOINTMENT_REMINDER_V1'
             AND source_identity=$2`,
          [reminder.recipientId, sourceIdentity],
        );
        if (existing.rows[0]?.request_fingerprint !== fingerprint)
          throw new AppointmentPushReminderConflictError(
            'Conflicting appointment push reminder identity',
          );
        notificationId = existing.rows[0].id;
      }
      await client.query(
        `UPDATE appointment_push_reminder
         SET state='MATERIALIZED', notification_id=$2, next_attempt_at=NULL,
             failure_code=NULL, terminal_at=$3, updated_at=$3
         WHERE id=$1 AND state='PROCESSING'`,
        [reminder.id, notificationId, now],
      );
      return true;
    });
  }

  async retryPush(id: string, nextAttemptAt: Date, code: string, now: Date): Promise<void> {
    await this.db.query(
      `UPDATE appointment_push_reminder
       SET state='PENDING', next_attempt_at=$2, failure_code=$3, updated_at=$4
       WHERE id=$1 AND state='PROCESSING'`,
      [id, nextAttemptAt, code, now],
    );
  }

  async finishPush(
    id: string,
    state: 'INVALIDATED' | 'SUPPRESSED' | 'FAILED' | 'EXPIRED',
    now: Date,
    code: string,
  ): Promise<void> {
    await this.db.query(
      `UPDATE appointment_push_reminder
       SET state=$2, next_attempt_at=NULL, failure_code=$3,
           invalidated_at=CASE WHEN $2='INVALIDATED' THEN $4 ELSE invalidated_at END,
           terminal_at=$4, updated_at=$4
       WHERE id=$1 AND state='PROCESSING'`,
      [id, state, code, now],
    );
  }

  async claimDue(now: Date, limit: number): Promise<readonly ClaimedAppointmentReminder[]> {
    return this.db.withTransaction(async (client) => {
      const selected = await client.query<{ id: string }>(
        `SELECT id FROM appointment_email_reminder
         WHERE delivery_state IN ('PENDING','PROCESSING')
           AND coalesce(next_attempt_at,due_at)<=$1
           AND attempt_count<3
         ORDER BY coalesce(next_attempt_at,due_at), id
         FOR UPDATE SKIP LOCKED LIMIT $2`,
        [now, limit],
      );
      if (!selected.rows.length) return [];
      const ids = selected.rows.map((row) => row.id);
      const result = await client.query<AppointmentReminderRow>(
        `UPDATE appointment_email_reminder SET
           delivery_state='PROCESSING', attempt_count=attempt_count+1,
           first_attempt_at=coalesce(first_attempt_at,$2), last_attempt_at=$2,
           next_attempt_at=$2 + interval '2 minutes', updated_at=$2
         WHERE id = ANY($1::uuid[]) RETURNING ${RETURNING}`,
        [ids, now],
      );
      return result.rows.map(claimed);
    });
  }

  async delivered(id: string, providerMessageId: string, now: Date): Promise<void> {
    await this.complete(id, 'DELIVERED', now, null, providerMessageId);
  }

  async finish(
    id: string,
    state: 'INVALIDATED' | 'SUPPRESSED' | 'FAILED' | 'EXPIRED' | 'UNKNOWN',
    now: Date,
    code: string,
  ): Promise<void> {
    await this.complete(id, state, now, code, null);
  }

  async retry(id: string, nextAttemptAt: Date, code: string, now: Date): Promise<void> {
    await this.db.query(
      `UPDATE appointment_email_reminder SET delivery_state='PENDING', next_attempt_at=$2,
         failure_code=$3, updated_at=$4 WHERE id=$1 AND delivery_state='PROCESSING'`,
      [id, nextAttemptAt, code, now],
    );
  }

  private async complete(
    id: string,
    state: string,
    now: Date,
    code: string | null,
    providerMessageId: string | null,
  ): Promise<void> {
    await this.db.query(
      `UPDATE appointment_email_reminder SET delivery_state=$2, next_attempt_at=null,
         failure_code=$3, provider_message_id=coalesce($4,provider_message_id),
         delivered_at=case when $2='DELIVERED' then $5 else delivered_at end,
         invalidated_at=case when $2='INVALIDATED' then $5 else invalidated_at end,
         updated_at=$5 WHERE id=$1 AND delivery_state='PROCESSING'`,
      [id, state, code, providerMessageId, now],
    );
  }
}
