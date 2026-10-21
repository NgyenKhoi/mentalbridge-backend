-- Up Migration
-- Migration: 26_add_community_notice_delivery
-- Service: content-notification-service
-- Story: MB-691

ALTER TABLE notification ADD COLUMN grouping_key varchar(160);
CREATE INDEX ix_notification_unread_group
  ON notification (recipient_id, grouping_key, created_at DESC)
  WHERE grouping_key IS NOT NULL AND read_at IS NULL AND deleted_at IS NULL AND delivery_state = 'DELIVERED';

ALTER TABLE notification DROP CONSTRAINT ck_notification_kind;
ALTER TABLE notification ADD CONSTRAINT ck_notification_kind CHECK (category IN (
  'REMINDER', 'MESSAGE', 'APPOINTMENT', 'SYSTEM_RESOURCE',
  'ASSESSMENT_REASSESSMENT', 'STREAK_MILESTONE', 'JOURNAL_REMINDER',
  'EMOTION_CHECKIN_REMINDER', 'JOURNAL_STREAK_MILESTONE', 'EMOTION_STREAK_MILESTONE',
  'COMMUNITY_COMMENT', 'COMMUNITY_REPLY', 'COMMUNITY_REACTION',
  'COMMUNITY_MESSAGE', 'COMMUNITY_REQUEST', 'COMMUNITY_INVITATION',
  'ASSESSMENT_RESULT_AVAILABLE', 'SUPPORT_GUIDE_AVAILABLE', 'ACCOUNT_STATUS',
  'ASSESSMENT', 'CHAT', 'FOLLOW_UP', 'SAFETY', 'SYSTEM'
));
ALTER TABLE notification DROP CONSTRAINT ck_notification_action;
ALTER TABLE notification ADD CONSTRAINT ck_notification_action CHECK (
  (action_type IS NULL AND action_target_id IS NULL)
  OR (action_type IN ('OPEN_JOURNAL', 'OPEN_MESSAGES', 'OPEN_APPOINTMENTS', 'OPEN_RESOURCES',
    'OPEN_ASSESSMENTS', 'OPEN_SPECIALIST_APPOINTMENTS') AND action_target_id IS NULL)
  OR (action_type IN ('OPEN_RESOURCE', 'OPEN_COMMUNITY_POST', 'OPEN_SUPPORT_GUIDE',
    'OPEN_COMMUNITY_CONVERSATION', 'OPEN_COMMUNITY_REQUEST', 'OPEN_COMMUNITY_INVITATION') AND action_target_id IS NOT NULL)
);

CREATE TABLE community_notification_job (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  recipient_id uuid NOT NULL,
  source varchar(64) NOT NULL CHECK (source IN ('COMMUNITY_INTERACTION_V1', 'COMMUNITY_COMMENT_REACTION_V1', 'COMMUNITY_CHAT_V1')),
  source_identity varchar(160) NOT NULL,
  request_fingerprint char(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
  projection jsonb NOT NULL CHECK (jsonb_typeof(projection) = 'object'),
  state varchar(16) NOT NULL CHECK (state IN ('PENDING', 'DELIVERED', 'CANCELLED')),
  available_at timestamptz NOT NULL,
  claim_token uuid,
  claim_until timestamptz,
  attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  notification_id uuid REFERENCES notification(id),
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (recipient_id, source, source_identity),
  CHECK ((claim_token IS NULL) = (claim_until IS NULL)),
  CHECK (state <> 'DELIVERED' OR notification_id IS NOT NULL)
);
CREATE INDEX ix_community_notification_job_due ON community_notification_job (available_at, id) WHERE state = 'PENDING';
