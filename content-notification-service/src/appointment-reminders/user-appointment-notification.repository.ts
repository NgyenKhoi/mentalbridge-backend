import { createHash } from 'node:crypto';
import { Inject, Injectable } from '@nestjs/common';

import { DATABASE_SERVICE_TOKEN } from '../application.tokens.js';
import type { DatabaseService } from '../database/database.service.js';
import type { AppointmentStatusChangedEventV2 } from './appointment-reminder.types.js';

const COPY = {
  CONFIRMED: ['Lịch hẹn đã được xác nhận', 'Chuyên gia đã xác nhận lịch hẹn của bạn.'],
  REJECTED: ['Lịch hẹn chưa được nhận', 'Chuyên gia không nhận lịch này.'],
  EXPIRED: ['Lịch hẹn đã hết hạn chờ', 'Chuyên gia chưa phản hồi kịp.'],
  CANCELLED: ['Lịch hẹn đã bị hủy', 'Chuyên gia đã hủy lịch hẹn này.'],
} as const;

@Injectable()
export class UserAppointmentNotificationRepository {
  constructor(@Inject(DATABASE_SERVICE_TOKEN) private readonly db: DatabaseService) {}

  async materialize(event: AppointmentStatusChangedEventV2): Promise<void> {
    const status = event.payload.status;
    if (!(status in COPY)) return;
    if (status === 'CANCELLED' && event.payload.cancellationActorRole !== 'SPECIALIST') return;
    const [title, body] = COPY[status as keyof typeof COPY];
    const recipientId = event.payload.ownerAccountId;
    const sourceIdentity = `${event.payload.appointmentId}:${String(event.aggregateVersion)}`;
    const fingerprint = createHash('sha256')
      .update(
        JSON.stringify({
          recipientId,
          status,
          appointmentId: event.payload.appointmentId,
          aggregateVersion: event.aggregateVersion,
          cancellationActorRole: event.payload.cancellationActorRole ?? null,
        }),
      )
      .digest('hex');

    await this.db.withTransaction(async (client) => {
      await client.query(
        `INSERT INTO notification_preference (user_id, owner_type)
         VALUES ($1,'USER') ON CONFLICT (user_id) DO NOTHING`,
        [recipientId],
      );
      const preference = await client.query<{ enabled: boolean }>(
        `SELECT notifications_enabled AND channel_in_app_enabled
                AND group_appointment_message_enabled AS enabled
         FROM notification_preference
         WHERE user_id=$1 AND owner_type='USER' FOR UPDATE`,
        [recipientId],
      );
      if (!preference.rows.length) throw new Error('User notification preference owner mismatch');
      const deliveryState = preference.rows[0].enabled ? 'DELIVERED' : 'CANCELLED';
      const inserted = await client.query<{
        id: string;
        version: string;
        created_at: Date;
        expires_at: Date;
      }>(
        `INSERT INTO notification (
           recipient_id, category, title, body, action_type, priority,
           expires_at, occurred_at, source, source_identity,
           request_fingerprint, delivery_state
         ) VALUES ($1,'APPOINTMENT',$2,$3,'OPEN_APPOINTMENTS','NORMAL',
           now() + interval '90 days',$4,'CONSULTATION_APPOINTMENT_V1',$5,$6,$7)
         ON CONFLICT (recipient_id, source, source_identity) DO NOTHING
         RETURNING id,version,created_at,expires_at`,
        [recipientId, title, body, event.occurredAt, sourceIdentity, fingerprint, deliveryState],
      );
      if (inserted.rows.length) {
        const notification = inserted.rows[0];
        if (deliveryState === 'DELIVERED')
          await client.query(
            `INSERT INTO specialist_notification_live_outbox (
               notification_id,recipient_account_id,recipient_role,aggregate_version,
               correlation_id,causation_id,occurred_at,expires_at
             ) VALUES ($1,$2,'USER',$3,$4,$5,$6,$7)`,
            [
              notification.id,
              recipientId,
              notification.version,
              event.correlationId,
              event.messageId,
              notification.created_at,
              notification.expires_at,
            ],
          );
        return;
      }
      const existing = await client.query<{ request_fingerprint: string }>(
        `SELECT request_fingerprint FROM notification
         WHERE recipient_id=$1 AND source='CONSULTATION_APPOINTMENT_V1'
           AND source_identity=$2`,
        [recipientId, sourceIdentity],
      );
      if (existing.rows[0]?.request_fingerprint !== fingerprint)
        throw new UserAppointmentNotificationDedupeConflictError();
    });
  }
}

export class UserAppointmentNotificationDedupeConflictError extends Error {
  constructor() {
    super('Conflicting user appointment notification replay');
    this.name = 'UserAppointmentNotificationDedupeConflictError';
  }
}
