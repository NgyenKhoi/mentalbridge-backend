--liquibase formatted sql
--changeset mentalbridge:care-025-consultation-brief-ai-draft

CREATE TABLE consultation_brief_ai_draft_job (
    id uuid PRIMARY KEY,
    appointment_id uuid NOT NULL,
    brief_id uuid NOT NULL REFERENCES consultation_brief(id) ON DELETE RESTRICT,
    user_id uuid NOT NULL,
    brief_version bigint NOT NULL,
    support_evaluation_id uuid NOT NULL REFERENCES support_evaluation_v2(id) ON DELETE RESTRICT,
    idempotency_key varchar(128) NOT NULL,
    request_fingerprint varchar(64) NOT NULL,
    source_set_version varchar(64) NOT NULL,
    status varchar(16) NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    terminal_reason varchar(64),
    suggested_current_situation varchar(1000),
    suggested_user_goals jsonb,
    consent_policy_version varchar(64),
    service_plan varchar(16),
    entitlement_source varchar(32),
    entitlement_policy_version varchar(96),
    entitlement_version bigint,
    routing_policy_version varchar(96),
    provider_approval_version varchar(96),
    provider varchar(32),
    model varchar(128),
    prompt_version varchar(96),
    schema_version integer,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    completed_at timestamptz,
    CONSTRAINT ux_consultation_brief_ai_draft_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT ck_consultation_brief_ai_draft_version CHECK (brief_version >= 0),
    CONSTRAINT ck_consultation_brief_ai_draft_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_consultation_brief_ai_draft_attempts CHECK (attempt_count BETWEEN 0 AND 2),
    CONSTRAINT ck_consultation_brief_ai_draft_result CHECK (
      (status = 'SUCCEEDED' AND terminal_reason IS NULL AND suggested_current_situation IS NOT NULL
       AND suggested_user_goals IS NOT NULL AND consent_policy_version IS NOT NULL
       AND provider IS NOT NULL AND model IS NOT NULL AND prompt_version IS NOT NULL AND schema_version = 1)
      OR (status = 'RUNNING' AND terminal_reason IS NULL AND suggested_current_situation IS NULL
       AND suggested_user_goals IS NULL AND completed_at IS NULL)
      OR (status = 'FAILED' AND terminal_reason IS NOT NULL AND suggested_current_situation IS NULL
       AND suggested_user_goals IS NULL AND completed_at IS NOT NULL)
    )
);

CREATE INDEX ix_consultation_brief_ai_draft_owner
    ON consultation_brief_ai_draft_job (user_id, appointment_id, created_at DESC);
