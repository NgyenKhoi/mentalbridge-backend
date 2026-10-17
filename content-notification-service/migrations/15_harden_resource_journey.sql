-- Up Migration
-- Migration: 15_harden_resource_journey
-- Service:   content-notification-service
-- Story:     MB-603 - support idempotent, repeatable practice sessions

ALTER TABLE resource_practice_session
  DROP CONSTRAINT resource_practice_session_owner_id_resource_id_local_date_key;

ALTER TABLE resource_practice_session
  ADD COLUMN started_at timestamptz,
  ADD COLUMN duration_seconds integer;

ALTER TABLE resource_practice_session
  ADD CONSTRAINT ck_resource_practice_session_duration
    CHECK (duration_seconds IS NULL OR duration_seconds BETWEEN 1 AND 7200),
  ADD CONSTRAINT ck_resource_practice_session_time_order
    CHECK (started_at IS NULL OR started_at <= completed_at);

CREATE INDEX ix_resource_practice_session_daily_history
  ON resource_practice_session (owner_id, resource_id, local_date, completed_at DESC);
