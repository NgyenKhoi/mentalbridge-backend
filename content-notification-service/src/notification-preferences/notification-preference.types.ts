export type EmailCadence = 'IMMEDIATE' | 'DAILY_DIGEST' | 'WEEKLY_DIGEST';

export interface NotificationPreferenceRow {
  readonly user_id: string;
  readonly notifications_enabled: boolean;
  readonly channel_in_app_enabled: boolean;
  readonly channel_email_enabled: boolean;
  readonly channel_push_enabled: boolean;
  readonly group_journal_reminder_enabled: boolean;
  readonly group_emotion_check_in_enabled: boolean;
  readonly group_streak_milestone_enabled: boolean;
  readonly group_screening_reassessment_enabled: boolean;
  readonly group_appointment_message_enabled: boolean;
  readonly group_resource_system_enabled: boolean;
  readonly quiet_hours_enabled: boolean;
  readonly quiet_hours_start: string;
  readonly quiet_hours_end: string;
  readonly time_zone: string;
  readonly email_cadence: EmailCadence;
  readonly email_wellbeing_digest_enabled: boolean;
  readonly email_resource_reminders_enabled: boolean;
  readonly version: string | number;
  readonly created_at: Date | string;
  readonly updated_at: Date | string;
}

export interface NotificationPreferences {
  readonly notificationsEnabled: boolean;
  readonly channels: {
    readonly inApp: boolean;
    readonly email: boolean;
    readonly push: boolean;
  };
  readonly contentGroups: {
    readonly journalReminder: boolean;
    readonly emotionCheckIn: boolean;
    readonly streakMilestone: boolean;
    readonly screeningReassessment: boolean;
    readonly appointmentMessage: boolean;
    readonly resourceSystem: boolean;
  };
  readonly quietHours: {
    readonly enabled: boolean;
    readonly start: string;
    readonly end: string;
    readonly timeZone: string;
  };
  readonly email: {
    readonly cadence: EmailCadence;
    readonly wellbeingDigestEnabled: boolean;
    readonly resourceRemindersEnabled: boolean;
  };
  readonly version: number;
  readonly updatedAt: string;
}

export interface NotificationPreferenceUpdate {
  readonly notificationsEnabled?: boolean;
  readonly channels?: {
    readonly inApp?: boolean;
    readonly email?: boolean;
    readonly push?: boolean;
  };
  readonly contentGroups?: {
    readonly journalReminder?: boolean;
    readonly emotionCheckIn?: boolean;
    readonly streakMilestone?: boolean;
    readonly screeningReassessment?: boolean;
    readonly appointmentMessage?: boolean;
    readonly resourceSystem?: boolean;
  };
  readonly quietHours?: {
    readonly enabled?: boolean;
    readonly start?: string;
    readonly end?: string;
    readonly timeZone?: string;
  };
  readonly email?: {
    readonly cadence?: EmailCadence;
    readonly wellbeingDigestEnabled?: boolean;
    readonly resourceRemindersEnabled?: boolean;
  };
}
