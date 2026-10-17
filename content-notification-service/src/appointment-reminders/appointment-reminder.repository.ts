import { createHash } from 'node:crypto';
import { Inject, Injectable } from '@nestjs/common';

import { DATABASE_SERVICE_TOKEN } from '../application.tokens.js';
import type { DatabaseService } from '../database/database.service.js';
import type {
  AppointmentReminderRow,
  AppointmentStatusChangedEvent,
  ClaimedAppointmentReminder,
} from './appointment-reminder.types.js';

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
    });
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
