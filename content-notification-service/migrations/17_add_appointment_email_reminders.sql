-- Up Migration
-- Migration: 17_add_appointment_email_reminders
-- Service: content-notification-service
-- Story: MB-548 - Persist one appointment email reminder per confirmed appointment version

ALTER TABLE notification_preference
  ADD COLUMN email_appointment_reminders_enabled boolean NOT NULL DEFAULT false;

CREATE TABLE appointment_email_reminder (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  recipient_id uuid NOT NULL,
  appointment_id uuid NOT NULL,
  appointment_version bigint NOT NULL,
  appointment_status varchar(24) NOT NULL,
  modality varchar(24) NOT NULL,
  scheduled_start_at timestamptz NOT NULL,
  target_at timestamptz NOT NULL,
  due_at timestamptz NOT NULL,
  delivery_state varchar(24) NOT NULL DEFAULT 'PENDING',
  attempt_count integer NOT NULL DEFAULT 0,
  first_attempt_at timestamptz,
  last_attempt_at timestamptz,
  next_attempt_at timestamptz,
  provider_idempotency_key uuid NOT NULL,
  provider_message_id varchar(255),
  failure_code varchar(64),
  delivered_at timestamptz,
  invalidated_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_appointment_email_reminder_identity
    UNIQUE (recipient_id, appointment_id, appointment_version),
  CONSTRAINT ck_appointment_email_reminder_version CHECK (appointment_version >= 0),
  CONSTRAINT ck_appointment_email_reminder_modality
    CHECK (modality IN ('IN_APP_CHAT', 'IN_APP_VIDEO')),
  CONSTRAINT ck_appointment_email_reminder_state
    CHECK (delivery_state IN ('PENDING', 'PROCESSING', 'DELIVERED', 'INVALIDATED', 'SUPPRESSED', 'FAILED', 'EXPIRED', 'UNKNOWN')),
  CONSTRAINT ck_appointment_email_reminder_attempts CHECK (attempt_count BETWEEN 0 AND 3),
  CONSTRAINT ck_appointment_email_reminder_time
    CHECK (target_at = scheduled_start_at - interval '60 minutes' AND due_at <= scheduled_start_at)
);

CREATE INDEX ix_appointment_email_reminder_due
  ON appointment_email_reminder (coalesce(next_attempt_at, due_at), id)
  WHERE delivery_state = 'PENDING';

CREATE TABLE appointment_reminder_checkpoint (
  appointment_id uuid PRIMARY KEY,
  latest_version bigint NOT NULL,
  latest_status varchar(24) NOT NULL,
  last_message_id uuid NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT ck_appointment_reminder_checkpoint_version CHECK (latest_version >= 0)
);
