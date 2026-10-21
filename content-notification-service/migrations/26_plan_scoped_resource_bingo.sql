-- Up Migration
-- Migration: 26_plan_scoped_resource_bingo
-- Service: content-notification-service
-- Story: MB-670

ALTER TABLE resource_weekly_bingo
  DROP CONSTRAINT resource_weekly_bingo_owner_id_week_start_key,
  ADD CONSTRAINT uq_resource_weekly_bingo_owner_week_plan
    UNIQUE (owner_id, week_start, support_plan_id),
  ADD COLUMN last_synced_at timestamptz NOT NULL DEFAULT now();

ALTER TABLE resource_weekly_bingo_item
  ADD COLUMN support_plan_action_id varchar(128),
  ADD COLUMN support_plan_action_version bigint,
  ADD COLUMN retired_at timestamptz,
  ADD CONSTRAINT ck_resource_weekly_bingo_item_action
    CHECK ((support_plan_action_id IS NULL) = (support_plan_action_version IS NULL)),
  ADD CONSTRAINT ck_resource_weekly_bingo_item_action_version
    CHECK (support_plan_action_version IS NULL OR support_plan_action_version >= 0);
