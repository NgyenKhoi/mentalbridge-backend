-- Up Migration
-- Migration: 18_add_community_interaction_notifications
-- Service: content-notification-service
-- Database: mentalbridge_content_notification
-- Story: MB-617 - Consume Community interaction facts into the durable user inbox

ALTER TABLE notification_preference
  ADD COLUMN group_community_interaction_enabled boolean NOT NULL DEFAULT true;

ALTER TABLE notification DROP CONSTRAINT ck_notification_kind;

ALTER TABLE notification
  ADD CONSTRAINT ck_notification_kind
    CHECK (category IN (
      'REMINDER',
      'MESSAGE',
      'APPOINTMENT',
      'SYSTEM_RESOURCE',
      'ASSESSMENT_REASSESSMENT',
      'STREAK_MILESTONE',
      'JOURNAL_REMINDER',
      'EMOTION_CHECKIN_REMINDER',
      'JOURNAL_STREAK_MILESTONE',
      'EMOTION_STREAK_MILESTONE',
      'COMMUNITY_COMMENT',
      'COMMUNITY_REPLY',
      'COMMUNITY_REACTION',
      'ASSESSMENT',
      'CHAT',
      'FOLLOW_UP',
      'SAFETY',
      'SYSTEM'
    ));

ALTER TABLE notification DROP CONSTRAINT ck_notification_action;

ALTER TABLE notification
  ADD CONSTRAINT ck_notification_action
    CHECK (
      (action_type IS NULL AND action_target_id IS NULL)
      OR (action_type IN (
        'OPEN_JOURNAL',
        'OPEN_MESSAGES',
        'OPEN_APPOINTMENTS',
        'OPEN_RESOURCES',
        'OPEN_ASSESSMENTS'
      ) AND action_target_id IS NULL)
      OR (action_type = 'OPEN_RESOURCE' AND action_target_id IS NOT NULL)
      OR (action_type = 'OPEN_COMMUNITY_POST' AND action_target_id IS NOT NULL)
    );
