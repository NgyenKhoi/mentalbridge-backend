--liquibase formatted sql

--changeset mentalbridge:identity-006-retention-and-deletion-governance runInTransaction:true
CREATE TABLE deletion_request (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id uuid NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    subject_reference_hash char(64) NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'PENDING',
    hold_type varchar(32) NOT NULL DEFAULT 'NONE',
    hold_reason varchar(64),
    retry_count integer NOT NULL DEFAULT 0,
    max_retries integer NOT NULL DEFAULT 3,
    escalation_state varchar(32) NOT NULL DEFAULT 'NONE',
    failure_code varchar(64),
    requested_at timestamptz NOT NULL DEFAULT now(),
    scheduled_execution_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    last_attempt_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_deletion_request_status CHECK (status IN ('PENDING', 'IN_PROGRESS', 'HELD', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_deletion_request_hold_type CHECK (hold_type IN ('NONE', 'LEGAL_HOLD', 'OPERATIONAL_HOLD', 'DISPUTE_HOLD')),
    CONSTRAINT ck_deletion_request_escalation CHECK (escalation_state IN ('NONE', 'RETRY_EXHAUSTED', 'MANUAL_ESCALATION_REQUIRED')),
    CONSTRAINT ck_deletion_request_hash CHECK (subject_reference_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_deletion_request_retries CHECK (retry_count >= 0 AND max_retries > 0)
);

CREATE INDEX ix_deletion_request_status ON deletion_request (status, scheduled_execution_at);
CREATE INDEX ix_deletion_request_subject ON deletion_request (subject_reference_hash);
CREATE INDEX ix_deletion_request_account ON deletion_request (account_id);

CREATE TABLE deletion_owner_fact (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    deletion_request_id uuid NOT NULL REFERENCES deletion_request(id) ON DELETE CASCADE,
    owner_service varchar(32) NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'PENDING',
    completed_at timestamptz,
    evidence_reference varchar(128),
    failure_reason varchar(64),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_owner_fact_service CHECK (owner_service IN ('IDENTITY', 'CARE', 'CONSULTATION', 'JOURNAL_AI', 'COMMUNITY', 'CONTENT')),
    CONSTRAINT ck_owner_fact_status CHECK (status IN ('PENDING', 'COMPLETED', 'HELD', 'FAILED', 'UNAVAILABLE'))
);

CREATE UNIQUE INDEX ux_deletion_owner_fact ON deletion_owner_fact (deletion_request_id, owner_service);

CREATE TABLE retention_execution_record (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_id varchar(64) NOT NULL,
    owner_service varchar(32) NOT NULL,
    job_name varchar(64) NOT NULL,
    job_status varchar(32) NOT NULL,
    started_at timestamptz NOT NULL,
    completed_at timestamptz,
    items_evaluated integer NOT NULL DEFAULT 0,
    records_purged integer NOT NULL DEFAULT 0,
    tombstones_created integer NOT NULL DEFAULT 0,
    holds_honored integer NOT NULL DEFAULT 0,
    failure_code varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_retention_exec_status CHECK (job_status IN ('SUCCEEDED', 'FAILED', 'RUNNING', 'UNAVAILABLE'))
);

CREATE INDEX ix_retention_exec_policy ON retention_execution_record (policy_id, started_at DESC);

