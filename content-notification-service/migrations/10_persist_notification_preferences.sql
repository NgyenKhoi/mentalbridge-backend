-- Up Migration
-- Migration: 10_persist_notification_preferences
-- Service: content-notification-service
-- Database: mentalbridge_content_notification
-- Story: MB-562 - Persist notification channels, content groups, quiet hours, and email preferences

ALTER TABLE notification_preference RENAME TO notification_preference_legacy;

CREATE TABLE notification_preference (
  user_id uuid PRIMARY KEY,
  notifications_enabled boolean NOT NULL DEFAULT true,
  channel_in_app_enabled boolean NOT NULL DEFAULT true,
  channel_email_enabled boolean NOT NULL DEFAULT false,
  channel_push_enabled boolean NOT NULL DEFAULT false,
  group_journal_reminder_enabled boolean NOT NULL DEFAULT true,
  group_emotion_check_in_enabled boolean NOT NULL DEFAULT true,
  group_streak_milestone_enabled boolean NOT NULL DEFAULT true,
  group_screening_reassessment_enabled boolean NOT NULL DEFAULT true,
  group_appointment_message_enabled boolean NOT NULL DEFAULT true,
  group_resource_system_enabled boolean NOT NULL DEFAULT true,
  quiet_hours_enabled boolean NOT NULL DEFAULT false,
  quiet_hours_start time NOT NULL DEFAULT '22:00',
  quiet_hours_end time NOT NULL DEFAULT '07:00',
  time_zone varchar(64) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
  email_cadence varchar(24) NOT NULL DEFAULT 'IMMEDIATE',
  email_wellbeing_digest_enabled boolean NOT NULL DEFAULT false,
  email_resource_reminders_enabled boolean NOT NULL DEFAULT false,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_notification_preference_time_zone
    CHECK (btrim(time_zone) <> ''),
  CONSTRAINT ck_notification_preference_quiet_window
    CHECK (NOT quiet_hours_enabled OR quiet_hours_start <> quiet_hours_end),
  CONSTRAINT ck_notification_preference_email_cadence
    CHECK (email_cadence IN ('IMMEDIATE', 'DAILY_DIGEST', 'WEEKLY_DIGEST')),
  CONSTRAINT ck_notification_preference_version
    CHECK (version >= 0)
);

INSERT INTO notification_preference (
  user_id,
  channel_in_app_enabled,
  channel_email_enabled,
  channel_push_enabled,
  group_journal_reminder_enabled,
  group_emotion_check_in_enabled,
  group_streak_milestone_enabled,
  group_screening_reassessment_enabled,
  group_appointment_message_enabled,
  group_resource_system_enabled,
  quiet_hours_enabled,
  quiet_hours_start,
  quiet_hours_end,
  time_zone,
  updated_at
)
SELECT
  user_id,
  coalesce(bool_or(enabled) FILTER (WHERE channel = 'IN_APP'), true),
  coalesce(bool_or(enabled) FILTER (WHERE channel = 'EMAIL'), false),
  coalesce(bool_or(enabled) FILTER (WHERE channel = 'PUSH'), false),
  coalesce(bool_or(enabled) FILTER (WHERE category = 'FOLLOW_UP'), true),
  coalesce(bool_or(enabled) FILTER (WHERE category = 'FOLLOW_UP'), true),
  coalesce(bool_or(enabled) FILTER (WHERE category = 'FOLLOW_UP'), true),
  coalesce(bool_or(enabled) FILTER (WHERE category = 'ASSESSMENT'), true),
  coalesce(bool_or(enabled) FILTER (WHERE category IN ('APPOINTMENT', 'CHAT')), true),
  coalesce(bool_or(enabled) FILTER (WHERE category = 'SYSTEM'), true),
  coalesce(
    bool_or(
      CASE
        WHEN jsonb_typeof(quiet_hours) = 'object'
          AND quiet_hours->>'enabled' = 'true'
          AND quiet_hours->>'start' ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'
          AND quiet_hours->>'end' ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'
          AND quiet_hours->>'start' <> quiet_hours->>'end'
          THEN true
        WHEN jsonb_typeof(quiet_hours) = 'object'
          AND quiet_hours->>'enabled' = 'false'
          THEN false
        ELSE NULL
      END
    ),
    false
  ),
  coalesce(
    max(quiet_hours->>'start') FILTER (
      WHERE jsonb_typeof(quiet_hours) = 'object'
        AND quiet_hours->>'start' ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'
    ),
    '22:00'
  )::time,
  coalesce(
    max(quiet_hours->>'end') FILTER (
      WHERE jsonb_typeof(quiet_hours) = 'object'
        AND quiet_hours->>'end' ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'
    ),
    '07:00'
  )::time,
  coalesce(
    max(quiet_hours->>'timeZone') FILTER (
      WHERE jsonb_typeof(quiet_hours) = 'object'
        AND length(btrim(quiet_hours->>'timeZone')) BETWEEN 1 AND 64
    ),
    'Asia/Ho_Chi_Minh'
  ),
  max(updated_at)
FROM notification_preference_legacy
GROUP BY user_id;

DROP TABLE notification_preference_legacy;
