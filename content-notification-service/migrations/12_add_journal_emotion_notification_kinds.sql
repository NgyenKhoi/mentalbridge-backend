-- Up Migration
-- Migration: 12_add_journal_emotion_notification_kinds
-- Service: content-notification-service
-- Database: mentalbridge_content_notification
-- Story: MB-564 - Generate journal and emotion reminders plus streak milestone notifications

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
      'ASSESSMENT',
      'CHAT',
      'FOLLOW_UP',
      'SAFETY',
      'SYSTEM'
    ));
