-- Up Migration
-- Migration: 29_harden_native_push_delivery
-- Service: content-notification-service
-- Story: MB-675 - lifecycle, receipt and rate-limit review fixes

WITH ranked AS (
  SELECT id,
         row_number() OVER (
           PARTITION BY installation_id
           ORDER BY last_registered_at DESC, created_at DESC, id DESC
         ) AS rank
  FROM push_device_registration
  WHERE status IN ('ACTIVE', 'DISABLED')
)
UPDATE push_device_registration d
SET status = 'REVOKED', revoked_at = now(), updated_at = now(), version = version + 1
FROM ranked r
WHERE d.id = r.id AND r.rank > 1;

CREATE UNIQUE INDEX uq_push_device_active_installation
  ON push_device_registration (installation_id)
  WHERE status IN ('ACTIVE', 'DISABLED');

ALTER TABLE push_device_registration
  ADD COLUMN delivery_eligible_from_at timestamptz;

UPDATE push_device_registration
SET delivery_eligible_from_at = last_registered_at;

ALTER TABLE push_device_registration
  ALTER COLUMN delivery_eligible_from_at SET NOT NULL,
  ALTER COLUMN delivery_eligible_from_at SET DEFAULT now();

ALTER TABLE push_delivery_attempt
  ADD COLUMN receipt_attempt_count smallint NOT NULL DEFAULT 0,
  ADD COLUMN receipt_checked_at timestamptz;

ALTER TABLE push_delivery_attempt
  DROP CONSTRAINT ck_push_delivery_state,
  DROP CONSTRAINT ck_push_delivery_acceptance;

ALTER TABLE push_delivery_attempt
  ADD CONSTRAINT ck_push_delivery_state
    CHECK (state IN ('PENDING', 'PROCESSING', 'ACCEPTED', 'CONFIRMED', 'FAILED', 'SUPPRESSED', 'UNKNOWN')),
  ADD CONSTRAINT ck_push_delivery_receipt_attempts
    CHECK (receipt_attempt_count BETWEEN 0 AND 3),
  ADD CONSTRAINT ck_push_delivery_acceptance
    CHECK (
      (accepted_at IS NULL) = (provider_ticket_id IS NULL)
      AND (state NOT IN ('ACCEPTED', 'CONFIRMED') OR accepted_at IS NOT NULL)
    );

CREATE INDEX ix_push_delivery_receipt_due
  ON push_delivery_attempt (available_at, id)
  WHERE state = 'ACCEPTED';

CREATE TABLE push_device_command_rate_limit (
  owner_id uuid PRIMARY KEY,
  window_started_at timestamptz NOT NULL,
  request_count smallint NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT ck_push_device_command_rate_limit_count CHECK (request_count > 0)
);
