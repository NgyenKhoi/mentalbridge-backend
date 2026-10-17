-- Up Migration
-- Migration: 14_add_resource_experience_model
-- Service:   content-notification-service
-- Database:  mentalbridge_content_notification
-- Story:     MB-603 - plan-driven resource journeys

ALTER TABLE resource
  ADD COLUMN resource_kind varchar(24),
  ADD COLUMN interaction_type varchar(48),
  ADD COLUMN repeatability varchar(16),
  ADD COLUMN completion_mode varchar(24),
  ADD COLUMN streak_eligible boolean,
  ADD COLUMN expected_duration_minutes smallint,
  ADD COLUMN cooldown_days smallint,
  ADD COLUMN recommended_frequency_per_week smallint,
  ADD COLUMN plan_tags text[],
  ADD COLUMN structured_content jsonb,
  ADD COLUMN interaction_config jsonb,
  ADD COLUMN safety_notes text[],
  ADD COLUMN source_retrieved_at timestamptz,
  ADD COLUMN source_content_hash char(64),
  ADD COLUMN content_version_label varchar(64),
  ADD COLUMN source_review_status varchar(24);

UPDATE resource
SET resource_kind = CASE
      WHEN category IN ('ARTICLE', 'VIDEO', 'COMMUNITY') THEN 'LEARNING'
      WHEN category = 'JOURNALING' THEN 'REFLECTION'
      ELSE 'PRACTICE'
    END,
    interaction_type = CASE
      WHEN category = 'BREATHING' THEN 'BREATHING_PACER'
      WHEN category = 'MEDITATION' THEN 'GROUNDING_GUIDE'
      WHEN category = 'VIDEO' THEN 'VIDEO_TRANSCRIPT'
      WHEN category = 'JOURNALING' THEN 'REFLECTION'
      ELSE 'STRUCTURED_READER'
    END,
    repeatability = CASE
      WHEN category IN ('ARTICLE', 'VIDEO', 'COMMUNITY') THEN 'ONE_TIME'
      ELSE 'REPEATABLE'
    END,
    completion_mode = CASE
      WHEN category IN ('ARTICLE', 'COMMUNITY') THEN 'EXPLICIT'
      WHEN category = 'VIDEO' THEN 'VIDEO_CONFIRMATION'
      WHEN category = 'BREATHING' THEN 'TIMED'
      ELSE 'STEPS'
    END,
    streak_eligible = category IN ('BREATHING', 'MEDITATION', 'JOURNALING'),
    expected_duration_minutes = CASE
      WHEN category = 'BREATHING' THEN 5
      WHEN category = 'VIDEO' THEN 7
      WHEN category = 'ARTICLE' THEN 6
      ELSE 8
    END,
    cooldown_days = CASE WHEN category IN ('ARTICLE', 'VIDEO', 'COMMUNITY') THEN 0 ELSE 1 END,
    recommended_frequency_per_week = CASE
      WHEN category IN ('ARTICLE', 'VIDEO', 'COMMUNITY') THEN 1
      ELSE 4
    END,
    plan_tags = ARRAY['DEPRESSIVE_SYMPTOMS', 'ANXIETY_SYMPTOMS']::text[],
    structured_content = jsonb_build_object(
      'overview', summary,
      'sections', jsonb_build_array(jsonb_build_object('id', 'content', 'title', 'Nội dung', 'body', COALESCE(content_body, summary))),
      'nextStep', 'Chọn một bước nhỏ, phù hợp với nhịp độ của bạn.'
    ),
    interaction_config = '{}'::jsonb,
    safety_notes = ARRAY['Nội dung hỗ trợ tự chăm sóc, không thay thế đánh giá hoặc điều trị chuyên môn.']::text[],
    source_retrieved_at = COALESCE(reviewed_at, updated_at),
    source_content_hash = NULL,
    content_version_label = 'mb-603-v1',
    source_review_status = CASE
      WHEN source_url IS NULL THEN 'NEEDS_SOURCE_REVIEW'
      ELSE 'REVIEW_REQUIRED'
    END;

ALTER TABLE resource
  ALTER COLUMN resource_kind SET DEFAULT 'LEARNING',
  ALTER COLUMN interaction_type SET DEFAULT 'STRUCTURED_READER',
  ALTER COLUMN repeatability SET DEFAULT 'ONE_TIME',
  ALTER COLUMN completion_mode SET DEFAULT 'EXPLICIT',
  ALTER COLUMN streak_eligible SET DEFAULT false,
  ALTER COLUMN expected_duration_minutes SET DEFAULT 5,
  ALTER COLUMN cooldown_days SET DEFAULT 0,
  ALTER COLUMN recommended_frequency_per_week SET DEFAULT 1,
  ALTER COLUMN content_version_label SET DEFAULT 'draft-v1',
  ALTER COLUMN source_review_status SET DEFAULT 'NEEDS_SOURCE_REVIEW',
  ALTER COLUMN resource_kind SET NOT NULL,
  ALTER COLUMN interaction_type SET NOT NULL,
  ALTER COLUMN repeatability SET NOT NULL,
  ALTER COLUMN completion_mode SET NOT NULL,
  ALTER COLUMN streak_eligible SET NOT NULL,
  ALTER COLUMN expected_duration_minutes SET NOT NULL,
  ALTER COLUMN cooldown_days SET NOT NULL,
  ALTER COLUMN recommended_frequency_per_week SET NOT NULL,
  ALTER COLUMN plan_tags SET NOT NULL,
  ALTER COLUMN structured_content SET NOT NULL,
  ALTER COLUMN interaction_config SET NOT NULL,
  ALTER COLUMN safety_notes SET NOT NULL,
  ALTER COLUMN content_version_label SET NOT NULL,
  ALTER COLUMN source_review_status SET NOT NULL,
  ALTER COLUMN plan_tags SET DEFAULT '{}',
  ALTER COLUMN structured_content SET DEFAULT '{}'::jsonb,
  ALTER COLUMN interaction_config SET DEFAULT '{}'::jsonb,
  ALTER COLUMN safety_notes SET DEFAULT '{}';

ALTER TABLE resource
  ADD CONSTRAINT ck_resource_kind
    CHECK (resource_kind IN ('LEARNING', 'PRACTICE', 'HABIT', 'ACTION', 'REFLECTION')),
  ADD CONSTRAINT ck_resource_interaction_type
    CHECK (interaction_type IN (
      'STRUCTURED_READER', 'VIDEO_TRANSCRIPT', 'BREATHING_PACER',
      'GROUNDING_GUIDE', 'PROGRESSIVE_RELAXATION', 'WALK_TIMER',
      'STRETCH_SEQUENCE', 'PROBLEM_SOLVING_WORKSHEET',
      'BEHAVIORAL_ACTIVATION_PLANNER', 'SELF_COMPASSION_PROMPTS',
      'UNHOOKING_PROMPTS', 'PREPARE_FOR_SPECIALIST_CHECKLIST', 'REFLECTION'
    )),
  ADD CONSTRAINT ck_resource_repeatability
    CHECK (repeatability IN ('ONE_TIME', 'REPEATABLE')),
  ADD CONSTRAINT ck_resource_completion_mode
    CHECK (completion_mode IN ('EXPLICIT', 'VIDEO_CONFIRMATION', 'TIMED', 'STEPS')),
  ADD CONSTRAINT ck_resource_duration
    CHECK (expected_duration_minutes BETWEEN 1 AND 120),
  ADD CONSTRAINT ck_resource_cooldown
    CHECK (cooldown_days BETWEEN 0 AND 30),
  ADD CONSTRAINT ck_resource_frequency
    CHECK (recommended_frequency_per_week BETWEEN 1 AND 7),
  ADD CONSTRAINT ck_resource_source_hash
    CHECK (source_content_hash IS NULL OR source_content_hash ~ '^[0-9a-f]{64}$'),
  ADD CONSTRAINT ck_resource_review_status
    CHECK (source_review_status IN ('REVIEWED', 'REVIEW_REQUIRED', 'NEEDS_SOURCE_REVIEW'));

CREATE INDEX ix_resource_plan_catalogue
  ON resource USING gin (plan_tags)
  WHERE status = 'PUBLISHED' AND catalogue_visibility = 'LISTED';

CREATE TABLE resource_learning_completion (
  owner_id          uuid        NOT NULL,
  resource_id       uuid        NOT NULL REFERENCES resource(id),
  resource_version  bigint      NOT NULL,
  support_plan_id   uuid,
  local_date        date        NOT NULL,
  completed_at      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (owner_id, resource_id)
);

CREATE TABLE resource_practice_session (
  id                uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id          uuid        NOT NULL,
  resource_id       uuid        NOT NULL REFERENCES resource(id),
  resource_version  bigint      NOT NULL,
  support_plan_id   uuid,
  local_date        date        NOT NULL,
  completed_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE (owner_id, resource_id, local_date)
);

CREATE INDEX ix_resource_practice_session_streak
  ON resource_practice_session (owner_id, local_date DESC)
  INCLUDE (resource_id);

CREATE TABLE resource_daily_assignment (
  id                    uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id              uuid        NOT NULL,
  local_date            date        NOT NULL,
  time_zone             varchar(64) NOT NULL,
  support_plan_id       uuid        NOT NULL,
  support_plan_version  bigint      NOT NULL,
  plan_tags             text[]      NOT NULL,
  created_at            timestamptz NOT NULL DEFAULT now(),
  UNIQUE (owner_id, local_date)
);

CREATE TABLE resource_daily_assignment_item (
  assignment_id uuid     NOT NULL REFERENCES resource_daily_assignment(id) ON DELETE CASCADE,
  ordinal       smallint NOT NULL CHECK (ordinal BETWEEN 0 AND 7),
  resource_id   uuid     NOT NULL REFERENCES resource(id),
  selection_reason varchar(24) NOT NULL CHECK (
    selection_reason IN ('PLAN_SELECTED', 'PLAN_DOMAIN', 'CONTINUITY', 'BALANCE')
  ),
  PRIMARY KEY (assignment_id, ordinal),
  UNIQUE (assignment_id, resource_id)
);

CREATE TABLE resource_weekly_bingo (
  id                    uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id              uuid        NOT NULL,
  week_start            date        NOT NULL,
  time_zone             varchar(64) NOT NULL,
  support_plan_id       uuid        NOT NULL,
  support_plan_version  bigint      NOT NULL,
  plan_tags             text[]      NOT NULL,
  created_at            timestamptz NOT NULL DEFAULT now(),
  UNIQUE (owner_id, week_start)
);

CREATE TABLE resource_weekly_bingo_item (
  board_id     uuid     NOT NULL REFERENCES resource_weekly_bingo(id) ON DELETE CASCADE,
  ordinal      smallint NOT NULL CHECK (ordinal BETWEEN 0 AND 8),
  resource_id  uuid     NOT NULL REFERENCES resource(id),
  PRIMARY KEY (board_id, ordinal),
  UNIQUE (board_id, resource_id)
);
