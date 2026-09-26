import { Inject, Injectable } from '@nestjs/common';
import { DATABASE_SERVICE_TOKEN } from '../application.tokens.js';
import type { DatabaseService } from '../database/database.service.js';
import type {
  NotificationPreferenceRow,
  NotificationPreferences,
} from './notification-preference.types.js';

const COLUMNS = `user_id, notifications_enabled,
  channel_in_app_enabled, channel_email_enabled, channel_push_enabled,
  group_journal_reminder_enabled, group_emotion_check_in_enabled,
  group_streak_milestone_enabled, group_screening_reassessment_enabled,
  group_appointment_message_enabled, group_resource_system_enabled,
  quiet_hours_enabled, quiet_hours_start::text, quiet_hours_end::text, time_zone,
  email_cadence, email_wellbeing_digest_enabled, email_resource_reminders_enabled,
  version, created_at, updated_at`;

export class NotificationPreferenceVersionMismatchError extends Error {
  constructor() {
    super('Notification preference version mismatch');
    this.name = 'NotificationPreferenceVersionMismatchError';
  }
}

function time(value: string): string {
  return value.slice(0, 5);
}

export function toNotificationPreferences(row: NotificationPreferenceRow): NotificationPreferences {
  return {
    notificationsEnabled: row.notifications_enabled,
    channels: {
      inApp: row.channel_in_app_enabled,
      email: row.channel_email_enabled,
      push: row.channel_push_enabled,
    },
    contentGroups: {
      journalReminder: row.group_journal_reminder_enabled,
      emotionCheckIn: row.group_emotion_check_in_enabled,
      streakMilestone: row.group_streak_milestone_enabled,
      screeningReassessment: row.group_screening_reassessment_enabled,
      appointmentMessage: row.group_appointment_message_enabled,
      resourceSystem: row.group_resource_system_enabled,
    },
    quietHours: {
      enabled: row.quiet_hours_enabled,
      start: time(row.quiet_hours_start),
      end: time(row.quiet_hours_end),
      timeZone: row.time_zone,
    },
    email: {
      cadence: row.email_cadence,
      wellbeingDigestEnabled: row.email_wellbeing_digest_enabled,
      resourceRemindersEnabled: row.email_resource_reminders_enabled,
    },
    version: Number(row.version),
    updatedAt: new Date(row.updated_at).toISOString(),
  };
}

@Injectable()
export class NotificationPreferenceRepository {
  constructor(
    @Inject(DATABASE_SERVICE_TOKEN)
    private readonly db: DatabaseService,
  ) {}

  async getOrCreate(userId: string): Promise<NotificationPreferences> {
    await this.db.query(
      `INSERT INTO notification_preference (user_id)
       VALUES ($1)
       ON CONFLICT (user_id) DO NOTHING`,
      [userId],
    );
    const result = await this.db.query<NotificationPreferenceRow>(
      `SELECT ${COLUMNS} FROM notification_preference WHERE user_id = $1`,
      [userId],
    );
    return toNotificationPreferences(result.rows[0]);
  }

  async update(
    userId: string,
    expectedVersion: number,
    preferences: NotificationPreferences,
  ): Promise<NotificationPreferences> {
    const result = await this.db.query<NotificationPreferenceRow>(
      `UPDATE notification_preference
       SET notifications_enabled = $3,
           channel_in_app_enabled = $4,
           channel_email_enabled = $5,
           channel_push_enabled = $6,
           group_journal_reminder_enabled = $7,
           group_emotion_check_in_enabled = $8,
           group_streak_milestone_enabled = $9,
           group_screening_reassessment_enabled = $10,
           group_appointment_message_enabled = $11,
           group_resource_system_enabled = $12,
           quiet_hours_enabled = $13,
           quiet_hours_start = $14::time,
           quiet_hours_end = $15::time,
           time_zone = $16,
           email_cadence = $17,
           email_wellbeing_digest_enabled = $18,
           email_resource_reminders_enabled = $19,
           version = version + 1,
           updated_at = now()
       WHERE user_id = $1 AND version = $2
       RETURNING ${COLUMNS}`,
      [
        userId,
        expectedVersion,
        preferences.notificationsEnabled,
        preferences.channels.inApp,
        preferences.channels.email,
        preferences.channels.push,
        preferences.contentGroups.journalReminder,
        preferences.contentGroups.emotionCheckIn,
        preferences.contentGroups.streakMilestone,
        preferences.contentGroups.screeningReassessment,
        preferences.contentGroups.appointmentMessage,
        preferences.contentGroups.resourceSystem,
        preferences.quietHours.enabled,
        preferences.quietHours.start,
        preferences.quietHours.end,
        preferences.quietHours.timeZone,
        preferences.email.cadence,
        preferences.email.wellbeingDigestEnabled,
        preferences.email.resourceRemindersEnabled,
      ],
    );
    if (!result.rows[0]) throw new NotificationPreferenceVersionMismatchError();
    return toNotificationPreferences(result.rows[0]);
  }
}
