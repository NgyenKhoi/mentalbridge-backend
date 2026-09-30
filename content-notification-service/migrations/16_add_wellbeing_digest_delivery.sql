-- Up Migration
-- Migration: 16_add_wellbeing_digest_delivery
-- Service: content-notification-service
-- Story: MB-514 - one daily wellbeing reminder digest

ALTER TABLE notification_preference
  ADD COLUMN email_daily_digest_time time NOT NULL DEFAULT '19:00',
  ADD COLUMN email_resource_reminder_time time NOT NULL DEFAULT '18:30';

CREATE TABLE wellbeing_email_delivery (
  id                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id            uuid        NOT NULL,
  local_date          date        NOT NULL,
  delivery_kind       varchar(24) NOT NULL,
  time_zone           varchar(64) NOT NULL,
  state               varchar(16) NOT NULL DEFAULT 'CLAIMED',
  attempt_count       smallint    NOT NULL DEFAULT 1,
  content_counts      jsonb       NOT NULL DEFAULT '{}'::jsonb,
  provider_message_id varchar(160),
  failure_code        varchar(48),
  claimed_at          timestamptz NOT NULL DEFAULT now(),
  attempted_at        timestamptz,
  delivered_at        timestamptz,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_wellbeing_email_delivery_day UNIQUE (owner_id, local_date, delivery_kind),
  CONSTRAINT ck_wellbeing_email_delivery_kind
    CHECK (delivery_kind IN ('DAILY_DIGEST', 'RESOURCE_REMINDER')),
  CONSTRAINT ck_wellbeing_email_delivery_state
    CHECK (state IN ('CLAIMED', 'DELIVERED', 'FAILED', 'CANCELLED')),
  CONSTRAINT ck_wellbeing_email_delivery_attempts CHECK (attempt_count BETWEEN 1 AND 10),
  CONSTRAINT ck_wellbeing_email_delivery_counts CHECK (
    jsonb_typeof(content_counts) = 'object'
    AND NOT (content_counts ?| ARRAY['journalBody', 'assessment', 'chatBody', 'providerPayload'])
  )
);

CREATE INDEX ix_wellbeing_email_delivery_retry
  ON wellbeing_email_delivery (state, claimed_at)
  WHERE state IN ('CLAIMED', 'FAILED');

