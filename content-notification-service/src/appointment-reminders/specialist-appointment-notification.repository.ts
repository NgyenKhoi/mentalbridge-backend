import { createHash } from 'node:crypto';
import { Inject, Injectable } from '@nestjs/common';

import { DATABASE_SERVICE_TOKEN } from '../application.tokens.js';
import type { DatabaseService } from '../database/database.service.js';
import type { AppointmentStatusChangedEvent } from './appointment-reminder.types.js';

const COPY: Readonly<
  Record<AppointmentStatusChangedEvent['payload']['status'], readonly [string, string]>
> = {
  REQUESTED: ['Yêu cầu lịch hẹn mới', 'Một yêu cầu lịch hẹn mới đang chờ bạn xem xét.'],
  CONFIRMED: ['Lịch hẹn đã được xác nhận', 'Lịch hẹn trực tuyến đã được xác nhận.'],
  REJECTED: ['Yêu cầu lịch hẹn đã được từ chối', 'Yêu cầu lịch hẹn không còn chờ xử lý.'],
  EXPIRED: ['Yêu cầu lịch hẹn đã hết hạn', 'Yêu cầu lịch hẹn đã hết thời gian xử lý.'],
  CANCELLED: ['Lịch hẹn đã bị hủy', 'Một lịch hẹn trong danh sách của bạn đã bị hủy.'],
  IN_PROGRESS: ['Phiên tư vấn đã bắt đầu', 'Phiên tư vấn trực tuyến đang diễn ra.'],
  SESSION_ENDED: ['Phiên tư vấn đã kết thúc', 'Phiên tư vấn trực tuyến đã kết thúc.'],
  COMPLETED: ['Lịch hẹn đã hoàn tất', 'Lịch hẹn đã chuyển sang trạng thái hoàn tất.'],
  USER_NO_SHOW: ['Người dùng vắng mặt', 'Lịch hẹn đã được ghi nhận người dùng vắng mặt.'],
  SPECIALIST_NO_SHOW: ['Chuyên gia vắng mặt', 'Lịch hẹn đã được ghi nhận chuyên gia vắng mặt.'],
  DISPUTED: ['Lịch hẹn cần được xem xét', 'Lịch hẹn đã chuyển sang trạng thái tranh chấp.'],
};

@Injectable()
export class SpecialistAppointmentNotificationRepository {
  constructor(@Inject(DATABASE_SERVICE_TOKEN) private readonly db: DatabaseService) {}

  async materialize(event: AppointmentStatusChangedEvent): Promise<void> {
    const [title, body] = COPY[event.payload.status];
    const sourceIdentity = `${event.payload.appointmentId}:${String(event.aggregateVersion)}`;
    const fingerprint = createHash('sha256')
      .update(
        JSON.stringify({
          recipientId: event.payload.specialistAccountId,
          status: event.payload.status,
          appointmentId: event.payload.appointmentId,
          aggregateVersion: event.aggregateVersion,
        }),
      )
      .digest('hex');

    await this.db.withTransaction(async (client) => {
      await client.query(
        `INSERT INTO notification_preference (
           user_id, owner_type,
           group_journal_reminder_enabled, group_emotion_check_in_enabled,
           group_streak_milestone_enabled, group_screening_reassessment_enabled,
           group_community_interaction_enabled,
           email_wellbeing_digest_enabled, email_resource_reminders_enabled
         ) VALUES ($1,'SPECIALIST',false,false,false,false,false,false,false)
         ON CONFLICT (user_id) DO NOTHING`,
        [event.payload.specialistAccountId],
      );
      const preference = await client.query<{ enabled: boolean }>(
        `SELECT notifications_enabled AND channel_in_app_enabled
                AND group_appointment_message_enabled AS enabled
         FROM notification_preference
         WHERE user_id=$1 AND owner_type='SPECIALIST'`,
        [event.payload.specialistAccountId],
      );
      const deliveryState = preference.rows[0]?.enabled ? 'DELIVERED' : 'CANCELLED';
      await client.query(
        `INSERT INTO notification (
           recipient_id, category, title, body, action_type, priority,
           expires_at, occurred_at, source, source_identity,
           request_fingerprint, delivery_state
         ) VALUES ($1,'APPOINTMENT',$2,$3,'OPEN_SPECIALIST_APPOINTMENTS','NORMAL',
           now() + interval '90 days',$4,'CONSULTATION_APPOINTMENT_V1',$5,$6,$7)
         ON CONFLICT (recipient_id, source, source_identity) DO NOTHING`,
        [
          event.payload.specialistAccountId,
          title,
          body,
          event.occurredAt,
          sourceIdentity,
          fingerprint,
          deliveryState,
        ],
      );
    });
  }
}
