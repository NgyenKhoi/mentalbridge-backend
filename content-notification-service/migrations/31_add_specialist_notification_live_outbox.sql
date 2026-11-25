-- Up Migration
-- Migration: 31_add_specialist_notification_live_outbox
-- Service: content-notification-service
-- Story: MB-800

CREATE TABLE specialist_notification_live_outbox (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  notification_id uuid NOT NULL UNIQUE REFERENCES notification(id) ON DELETE CASCADE,
  recipient_account_id uuid NOT NULL,
  recipient_role varchar(16) NOT NULL DEFAULT 'SPECIALIST',
  aggregate_version bigint NOT NULL,
  correlation_id uuid NOT NULL,
  causation_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  state varchar(16) NOT NULL DEFAULT 'PENDING',
  attempt_count smallint NOT NULL DEFAULT 0,
  next_attempt_at timestamptz DEFAULT now(),
  claim_token uuid,
  claim_until timestamptz,
  published_at timestamptz,
  last_error_code varchar(32),
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_specialist_live_role CHECK (recipient_role = 'SPECIALIST'),
  CONSTRAINT ck_specialist_live_version CHECK (aggregate_version >= 0),
  CONSTRAINT ck_specialist_live_expiry CHECK (expires_at > occurred_at),
  CONSTRAINT ck_specialist_live_state CHECK (state IN ('PENDING', 'PUBLISHED', 'SUPPRESSED', 'FAILED')),
  CONSTRAINT ck_specialist_live_attempts CHECK (attempt_count BETWEEN 0 AND 5),
  CONSTRAINT ck_specialist_live_claim CHECK ((claim_token IS NULL) = (claim_until IS NULL)),
  CONSTRAINT ck_specialist_live_ack CHECK ((state = 'PUBLISHED') = (published_at IS NOT NULL)),
  CONSTRAINT ck_specialist_live_schedule CHECK ((state = 'PENDING') = (next_attempt_at IS NOT NULL)),
  CONSTRAINT ck_specialist_live_error CHECK (last_error_code IN ('NOT_ELIGIBLE', 'PUBLISH_UNAVAILABLE', 'CLAIM_EXHAUSTED'))
);

CREATE INDEX ix_specialist_live_pending
  ON specialist_notification_live_outbox(next_attempt_at, id) WHERE state = 'PENDING';

CREATE FUNCTION specialist_notification_live_identity_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF (NEW.id, NEW.notification_id, NEW.recipient_account_id, NEW.recipient_role,
      NEW.aggregate_version, NEW.correlation_id, NEW.causation_id,
      NEW.occurred_at, NEW.expires_at, NEW.created_at)
    IS DISTINCT FROM
     (OLD.id, OLD.notification_id, OLD.recipient_account_id, OLD.recipient_role,
      OLD.aggregate_version, OLD.correlation_id, OLD.causation_id,
      OLD.occurred_at, OLD.expires_at, OLD.created_at) THEN
    RAISE EXCEPTION 'Specialist live event identity is immutable';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER specialist_notification_live_identity_immutable
  BEFORE UPDATE ON specialist_notification_live_outbox
  FOR EACH ROW EXECUTE FUNCTION specialist_notification_live_identity_immutable();
