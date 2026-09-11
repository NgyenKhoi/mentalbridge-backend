-- Up Migration
-- Migration: 5_add_resource_command_records
-- Service:   content-notification-service
-- Database:  mentalbridge_content_notification
-- Schema:    public (service-owned database, default schema)
-- Append-only — never edit after merge
-- Story:     MB-252 - Resource command retry and audit evidence

CREATE TABLE resource_idempotency_record (
  actor_id           uuid         NOT NULL,
  operation          varchar(40)  NOT NULL,
  idempotency_key    varchar(128) NOT NULL,
  request_fingerprint char(64)    NOT NULL,
  resource_id        uuid         REFERENCES resource(id) ON DELETE SET NULL,
  created_at         timestamptz  NOT NULL DEFAULT now(),
  PRIMARY KEY (actor_id, operation, idempotency_key),
  UNIQUE (resource_id),
  CONSTRAINT ck_resource_idempotency_operation
    CHECK (operation IN ('CREATE_RESOURCE')),
  CONSTRAINT ck_resource_idempotency_fingerprint
    CHECK (request_fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE TABLE resource_audit_event (
  id               uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
  occurred_at      timestamptz  NOT NULL DEFAULT now(),
  actor_id         uuid         NOT NULL,
  action           varchar(32)  NOT NULL,
  resource_id      uuid         NOT NULL,
  resource_version bigint,
  correlation_id   uuid         NOT NULL,
  CONSTRAINT ck_resource_audit_action
    CHECK (action IN ('RESOURCE_CREATED', 'RESOURCE_UPDATED', 'RESOURCE_DELETED', 'RESOURCE_ARCHIVED', 'RESOURCE_PUBLISH_BLOCKED'))
);

CREATE INDEX ix_resource_audit_resource_time
  ON resource_audit_event (resource_id, occurred_at DESC);
