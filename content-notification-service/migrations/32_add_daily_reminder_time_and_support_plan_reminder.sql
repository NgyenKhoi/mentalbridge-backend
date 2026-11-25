-- Up Migration
-- Migration: 32_add_daily_reminder_time_and_support_plan_reminder
-- Service: content-notification-service
-- Story: MB-855 - per-user daily reminder time and SupportPlan daily reminder

ALTER TABLE notification_preference
  ADD COLUMN daily_reminder_time time NOT NULL DEFAULT '20:00',
  ADD COLUMN group_support_plan_reminder_enabled boolean NOT NULL DEFAULT true;

UPDATE notification_preference
SET group_support_plan_reminder_enabled = false
WHERE owner_type = 'SPECIALIST';

ALTER TABLE notification DROP CONSTRAINT ck_notification_kind;
ALTER TABLE notification ADD CONSTRAINT ck_notification_kind CHECK (category IN (
  'REMINDER', 'MESSAGE', 'APPOINTMENT', 'SYSTEM_RESOURCE',
  'ASSESSMENT_REASSESSMENT', 'STREAK_MILESTONE', 'JOURNAL_REMINDER',
  'EMOTION_CHECKIN_REMINDER', 'JOURNAL_STREAK_MILESTONE', 'EMOTION_STREAK_MILESTONE',
  'COMMUNITY_COMMENT', 'COMMUNITY_REPLY', 'COMMUNITY_REACTION',
  'COMMUNITY_MESSAGE', 'COMMUNITY_REQUEST', 'COMMUNITY_INVITATION',
  'ASSESSMENT_RESULT_AVAILABLE', 'SUPPORT_GUIDE_AVAILABLE', 'ACCOUNT_STATUS',
  'SUPPORT_PLAN_DAILY_REMINDER',
  'ASSESSMENT', 'CHAT', 'FOLLOW_UP', 'SAFETY', 'SYSTEM'
));

ALTER TABLE notification DROP CONSTRAINT ck_notification_action;
ALTER TABLE notification ADD CONSTRAINT ck_notification_action CHECK (
  (action_type IS NULL AND action_target_id IS NULL)
  OR (action_type IN ('OPEN_JOURNAL', 'OPEN_MESSAGES', 'OPEN_APPOINTMENTS', 'OPEN_RESOURCES',
    'OPEN_ASSESSMENTS', 'OPEN_SPECIALIST_APPOINTMENTS', 'OPEN_SUPPORT_PLAN')
    AND action_target_id IS NULL)
  OR (action_type IN ('OPEN_RESOURCE', 'OPEN_COMMUNITY_POST', 'OPEN_SUPPORT_GUIDE',
    'OPEN_COMMUNITY_CONVERSATION', 'OPEN_COMMUNITY_REQUEST', 'OPEN_COMMUNITY_INVITATION',
    'OPEN_APPOINTMENT') AND action_target_id IS NOT NULL)
);
