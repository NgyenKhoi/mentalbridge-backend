--liquibase formatted sql

--changeset mentalbridge:care-023-consultation-brief runInTransaction:true
CREATE TABLE consultation_brief (
    id uuid PRIMARY KEY,
    appointment_id uuid NOT NULL UNIQUE,
    user_id uuid NOT NULL REFERENCES user_profile(account_id) ON DELETE RESTRICT,
    specialist_id uuid NOT NULL,
    appointment_start_at timestamptz NOT NULL,
    appointment_end_at timestamptz NOT NULL,
    appointment_version bigint NOT NULL,
    status varchar(16) NOT NULL,
    current_situation varchar(1000),
    support_evaluation_id uuid REFERENCES support_evaluation_v2(id) ON DELETE RESTRICT,
    user_goals jsonb,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    deleted_at timestamptz,
    CONSTRAINT ck_consultation_brief_status CHECK (status IN ('DRAFT', 'APPROVED', 'DELETED')),
    CONSTRAINT ck_consultation_brief_version CHECK (version >= 0),
    CONSTRAINT ck_consultation_brief_window CHECK (appointment_end_at > appointment_start_at),
    CONSTRAINT ck_consultation_brief_content CHECK (
        (status = 'DELETED' AND current_situation IS NULL AND support_evaluation_id IS NULL AND user_goals IS NULL) OR
        (status <> 'DELETED' AND length(btrim(current_situation)) BETWEEN 1 AND 1000 AND
         support_evaluation_id IS NOT NULL AND jsonb_typeof(user_goals) = 'array')
    )
);

CREATE INDEX ix_consultation_brief_owner ON consultation_brief (user_id, updated_at DESC);

CREATE TABLE consultation_brief_snapshot (
    id uuid PRIMARY KEY,
    brief_id uuid NOT NULL REFERENCES consultation_brief(id) ON DELETE RESTRICT,
    snapshot_version bigint NOT NULL,
    appointment_id uuid NOT NULL,
    user_id uuid NOT NULL,
    specialist_id uuid NOT NULL,
    current_situation varchar(1000),
    support_evaluation_id uuid REFERENCES support_evaluation_v2(id) ON DELETE RESTRICT,
    screening_context jsonb,
    user_goals jsonb,
    created_at timestamptz NOT NULL,
    deleted_at timestamptz,
    CONSTRAINT ux_consultation_brief_snapshot_version UNIQUE (brief_id, snapshot_version),
    CONSTRAINT ck_consultation_brief_snapshot_version CHECK (snapshot_version > 0),
    CONSTRAINT ck_consultation_brief_snapshot_content CHECK (
        (deleted_at IS NULL AND length(btrim(current_situation)) BETWEEN 1 AND 1000 AND
         support_evaluation_id IS NOT NULL AND jsonb_typeof(screening_context) = 'array' AND
         jsonb_typeof(user_goals) = 'array') OR
        (deleted_at IS NOT NULL AND current_situation IS NULL AND support_evaluation_id IS NULL AND
         screening_context IS NULL AND user_goals IS NULL)
    )
);

CREATE TABLE consultation_brief_grant (
    id uuid PRIMARY KEY,
    brief_id uuid NOT NULL REFERENCES consultation_brief(id) ON DELETE RESTRICT,
    snapshot_id uuid NOT NULL REFERENCES consultation_brief_snapshot(id) ON DELETE RESTRICT,
    appointment_id uuid NOT NULL,
    user_id uuid NOT NULL,
    specialist_id uuid NOT NULL,
    purpose varchar(32) NOT NULL,
    status varchar(16) NOT NULL,
    access_start_at timestamptz NOT NULL,
    access_end_at timestamptz NOT NULL,
    approved_at timestamptz NOT NULL,
    revoked_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_consultation_brief_grant_purpose CHECK (purpose = 'APPOINTMENT_PREPARATION'),
    CONSTRAINT ck_consultation_brief_grant_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_consultation_brief_grant_window CHECK (access_end_at > access_start_at),
    CONSTRAINT ck_consultation_brief_grant_revoke CHECK (
        (status = 'ACTIVE' AND revoked_at IS NULL) OR (status = 'REVOKED' AND revoked_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX ux_consultation_brief_active_grant
    ON consultation_brief_grant (brief_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_consultation_brief_specialist_grant
    ON consultation_brief_grant (specialist_id, appointment_id, status);

CREATE TABLE consultation_brief_audit (
    id uuid PRIMARY KEY,
    appointment_id uuid NOT NULL,
    grant_id uuid,
    actor_id uuid NOT NULL,
    actor_type varchar(16) NOT NULL,
    action varchar(32) NOT NULL,
    outcome varchar(16) NOT NULL,
    reason_code varchar(64) NOT NULL,
    correlation_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    CONSTRAINT ck_consultation_brief_audit_actor CHECK (actor_type IN ('USER', 'SPECIALIST')),
    CONSTRAINT ck_consultation_brief_audit_action CHECK (action IN ('DRAFT_SAVED', 'APPROVED', 'REVOKED', 'DELETED', 'READ')),
    CONSTRAINT ck_consultation_brief_audit_outcome CHECK (outcome IN ('ALLOWED', 'DENIED'))
);

CREATE INDEX ix_consultation_brief_audit_appointment
    ON consultation_brief_audit (appointment_id, occurred_at DESC);
