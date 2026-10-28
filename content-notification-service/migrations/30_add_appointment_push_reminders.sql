-- Up Migration
-- Migration: 30_add_appointment_push_reminders
-- Service: content-notification-service
-- Story: MB-677 - bounded native appointment reminder routing

ALTER TABLE notification DROP CONSTRAINT ck_notification_action;
ALTER TABLE notification ADD CONSTRAINT ck_notification_action CHECK (
  (action_type IS NULL AND action_target_id IS NULL)
  OR (action_type IN ('OPEN_JOURNAL', 'OPEN_MESSAGES', 'OPEN_APPOINTMENTS', 'OPEN_RESOURCES',
    'OPEN_ASSESSMENTS', 'OPEN_SPECIALIST_APPOINTMENTS') AND action_target_id IS NULL)
  OR (action_type IN ('OPEN_RESOURCE', 'OPEN_COMMUNITY_POST', 'OPEN_SUPPORT_GUIDE',
    'OPEN_COMMUNITY_CONVERSATION', 'OPEN_COMMUNITY_REQUEST', 'OPEN_COMMUNITY_INVITATION',
    'OPEN_APPOINTMENT') AND action_target_id IS NOT NULL)
);

CREATE TABLE appointment_push_reminder (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  recipient_id uuid NOT NULL,
  appointment_id uuid NOT NULL,
  appointment_version bigint NOT NULL,
  modality varchar(24) NOT NULL,
  scheduled_start_at timestamptz NOT NULL,
  target_at timestamptz NOT NULL,
  due_at timestamptz NOT NULL,
  state varchar(20) NOT NULL DEFAULT 'PENDING',
  attempt_count smallint NOT NULL DEFAULT 0,
  first_attempt_at timestamptz,
  last_attempt_at timestamptz,
  next_attempt_at timestamptz,
  notification_id uuid REFERENCES notification(id),
  failure_code varchar(64),
  invalidated_at timestamptz,
  terminal_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_appointment_push_reminder_identity
    UNIQUE (recipient_id, appointment_id, appointment_version),
  CONSTRAINT ck_appointment_push_reminder_version CHECK (appointment_version >= 0),
  CONSTRAINT ck_appointment_push_reminder_modality
    CHECK (modality IN ('IN_APP_CHAT', 'IN_APP_VIDEO')),
  CONSTRAINT ck_appointment_push_reminder_state
    CHECK (state IN ('PENDING', 'PROCESSING', 'MATERIALIZED', 'INVALIDATED', 'SUPPRESSED', 'FAILED', 'EXPIRED')),
  CONSTRAINT ck_appointment_push_reminder_attempts CHECK (attempt_count BETWEEN 0 AND 3),
  CONSTRAINT ck_appointment_push_reminder_time
    CHECK (target_at = scheduled_start_at - interval '60 minutes' AND due_at <= scheduled_start_at),
  CONSTRAINT ck_appointment_push_reminder_notification
    CHECK (state <> 'MATERIALIZED' OR notification_id IS NOT NULL),
  CONSTRAINT ck_appointment_push_reminder_terminal
    CHECK ((state IN ('MATERIALIZED', 'INVALIDATED', 'SUPPRESSED', 'FAILED', 'EXPIRED')) = (terminal_at IS NOT NULL))
);

CREATE INDEX ix_appointment_push_reminder_due
  ON appointment_push_reminder (coalesce(next_attempt_at, due_at), id)
  WHERE state IN ('PENDING', 'PROCESSING');
