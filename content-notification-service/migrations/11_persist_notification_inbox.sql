-- Up Migration
-- Migration: 11_persist_notification_inbox
-- Service:   content-notification-service
-- Database:  mentalbridge_content_notification
-- Schema:    public (service-owned database, default schema)
-- Append-only — never edit after merge
-- Story:     MB-563 - Deliver persisted in-app notification inbox and lifecycle

ALTER TABLE notification
  ADD COLUMN occurred_at timestamptz,
  ADD COLUMN source varchar(64),
  ADD COLUMN source_identity varchar(160),
  ADD COLUMN request_fingerprint char(64),
  ADD COLUMN delivery_state varchar(16) NOT NULL DEFAULT 'DELIVERED',
  ADD COLUMN version bigint NOT NULL DEFAULT 0;

UPDATE notification
SET occurred_at = created_at,
    source = 'LEGACY',
    source_identity = id::text,
    request_fingerprint = encode(sha256(convert_to(id::text, 'UTF8')), 'hex'),
    expires_at = CASE
      WHEN expires_at IS NULL
        OR expires_at <= created_at
        OR expires_at > created_at + interval '90 days'
      THEN created_at + interval '90 days'
      ELSE expires_at
    END;

UPDATE notification
SET delivery_state = 'CANCELLED'
WHERE category = 'SAFETY';

ALTER TABLE notification
  ALTER COLUMN occurred_at SET NOT NULL,
  ALTER COLUMN source SET NOT NULL,
  ALTER COLUMN source_identity SET NOT NULL,
  ALTER COLUMN request_fingerprint SET NOT NULL,
  ALTER COLUMN expires_at SET NOT NULL;

ALTER TABLE notification
  ADD CONSTRAINT ck_notification_kind
    CHECK (category IN (
      'REMINDER',
      'MESSAGE',
      'APPOINTMENT',
      'SYSTEM_RESOURCE',
      'ASSESSMENT_REASSESSMENT',
      'STREAK_MILESTONE',
      'ASSESSMENT',
      'CHAT',
      'FOLLOW_UP',
      'SAFETY',
      'SYSTEM'
    )),
  ADD CONSTRAINT ck_notification_source
    CHECK (source ~ '^[A-Z][A-Z0-9_]{0,63}$'),
  ADD CONSTRAINT ck_notification_source_identity
    CHECK (source_identity ~ '^[A-Za-z0-9][A-Za-z0-9:._/-]{0,159}$'),
  ADD CONSTRAINT ck_notification_request_fingerprint
    CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
  ADD CONSTRAINT ck_notification_delivery_state
    CHECK (delivery_state IN ('PENDING', 'DELIVERED', 'FAILED', 'CANCELLED')),
  ADD CONSTRAINT ck_notification_retention
    CHECK (expires_at > created_at AND expires_at <= created_at + interval '90 days'),
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
    );

CREATE UNIQUE INDEX uq_notification_recipient_source_identity
  ON notification (recipient_id, source, source_identity);

CREATE INDEX ix_notification_recipient_inbox
  ON notification (recipient_id, created_at DESC, id DESC)
  WHERE deleted_at IS NULL AND delivery_state = 'DELIVERED';

CREATE INDEX ix_notification_retention
  ON notification (expires_at)
  WHERE deleted_at IS NULL;
