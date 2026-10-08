-- Up Migration
-- Migration: 19_add_content_admin_audit_outbox
-- Service:   content-notification-service
-- Database:  mentalbridge_content_notification
-- Schema:    public (service-owned database, default schema)
-- Append-only — never edit after merge
-- Story:     MB-587 - Durable administration audit outbox for Content service

CREATE TABLE IF NOT EXISTS content_admin_audit_outbox (
  id uuid PRIMARY KEY,
  deduplication_key varchar(200) NOT NULL UNIQUE,
  event_type varchar(120) NOT NULL,
  correlation_id uuid NOT NULL,
  payload jsonb NOT NULL,
  occurred_at timestamptz NOT NULL,
  published_at timestamptz,
  attempt_count integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_content_admin_audit_outbox_due
  ON content_admin_audit_outbox (COALESCE(next_attempt_at, occurred_at), occurred_at, id)
  WHERE published_at IS NULL;
