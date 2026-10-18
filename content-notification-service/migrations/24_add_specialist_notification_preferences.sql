-- Up Migration
-- Migration: 24_add_specialist_notification_preferences
-- Service: content-notification-service
-- Story: MB-594 - Deliver specialist notification inbox and preferences

ALTER TABLE notification_preference
  ADD COLUMN owner_type varchar(16) NOT NULL DEFAULT 'USER',
  ADD CONSTRAINT ck_notification_preference_owner_type
    CHECK (owner_type IN ('USER', 'SPECIALIST')),
  ADD CONSTRAINT ck_specialist_notification_preference_scope
    CHECK (
      owner_type <> 'SPECIALIST'
      OR (
        group_journal_reminder_enabled = false
        AND group_emotion_check_in_enabled = false
        AND group_streak_milestone_enabled = false
        AND group_screening_reassessment_enabled = false
        AND group_community_interaction_enabled = false
        AND email_wellbeing_digest_enabled = false
        AND email_resource_reminders_enabled = false
      )
    );

ALTER TABLE notification DROP CONSTRAINT ck_notification_action;
ALTER TABLE notification
  ADD CONSTRAINT ck_notification_action
    CHECK (
      (action_type IS NULL AND action_target_id IS NULL)
      OR (action_type IN (
        'OPEN_JOURNAL', 'OPEN_MESSAGES', 'OPEN_APPOINTMENTS', 'OPEN_RESOURCES',
        'OPEN_ASSESSMENTS', 'OPEN_SPECIALIST_APPOINTMENTS'
      ) AND action_target_id IS NULL)
      OR (action_type IN ('OPEN_RESOURCE', 'OPEN_COMMUNITY_POST') AND action_target_id IS NOT NULL)
    );
