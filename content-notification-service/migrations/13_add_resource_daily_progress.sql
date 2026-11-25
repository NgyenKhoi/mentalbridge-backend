-- Up Migration
-- Migration: 13_add_resource_daily_progress
-- Service:   content-notification-service
-- Database:  mentalbridge_content_notification
-- Schema:    public (service-owned database, default schema)

CREATE TABLE resource_daily_progress (
  owner_id              uuid         NOT NULL,
  resource_id           uuid         NOT NULL REFERENCES resource(id),
  local_date            date         NOT NULL,
  resource_version      bigint       NOT NULL,
  status                varchar(16)  NOT NULL
                        CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
  completed_action_ids  text[]       NOT NULL DEFAULT '{}',
  completed_at          timestamptz,
  created_at            timestamptz  NOT NULL DEFAULT now(),
  updated_at            timestamptz  NOT NULL DEFAULT now(),
  version               bigint       NOT NULL DEFAULT 0,
  PRIMARY KEY (owner_id, resource_id, local_date),
  CONSTRAINT ck_resource_daily_progress_completion
    CHECK (
      (status = 'COMPLETED' AND completed_at IS NOT NULL)
      OR (status = 'IN_PROGRESS' AND completed_at IS NULL)
    ),
  CONSTRAINT ck_resource_daily_progress_actions
    CHECK (cardinality(completed_action_ids) <= 32)
);

CREATE INDEX ix_resource_daily_progress_owner_history
  ON resource_daily_progress (owner_id, local_date DESC, updated_at DESC);
