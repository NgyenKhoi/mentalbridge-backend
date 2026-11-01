-- Up Migration
-- Migration: 25_add_general_user_notification_producers
-- Service: content-notification-service
-- Story: MB-565 - Wire assessment, resource, and system notification producers

ALTER TABLE notification DROP CONSTRAINT ck_notification_kind;
ALTER TABLE notification
  ADD CONSTRAINT ck_notification_kind
    CHECK (category IN (
      'REMINDER', 'MESSAGE', 'APPOINTMENT', 'SYSTEM_RESOURCE',
      'ASSESSMENT_REASSESSMENT', 'STREAK_MILESTONE', 'JOURNAL_REMINDER',
      'EMOTION_CHECKIN_REMINDER', 'JOURNAL_STREAK_MILESTONE',
      'EMOTION_STREAK_MILESTONE', 'COMMUNITY_COMMENT', 'COMMUNITY_REPLY',
      'COMMUNITY_REACTION', 'ASSESSMENT_RESULT_AVAILABLE',
      'SUPPORT_GUIDE_AVAILABLE', 'ACCOUNT_STATUS',
      'ASSESSMENT', 'CHAT', 'FOLLOW_UP', 'SAFETY', 'SYSTEM'
    ));

ALTER TABLE notification DROP CONSTRAINT ck_notification_action;
ALTER TABLE notification
  ADD CONSTRAINT ck_notification_action
    CHECK (
      (action_type IS NULL AND action_target_id IS NULL)
      OR (action_type IN (
        'OPEN_JOURNAL', 'OPEN_MESSAGES', 'OPEN_APPOINTMENTS', 'OPEN_RESOURCES',
        'OPEN_ASSESSMENTS', 'OPEN_SPECIALIST_APPOINTMENTS'
      ) AND action_target_id IS NULL)
      OR (action_type IN (
        'OPEN_RESOURCE', 'OPEN_COMMUNITY_POST', 'OPEN_SUPPORT_GUIDE'
      ) AND action_target_id IS NOT NULL)
    );
