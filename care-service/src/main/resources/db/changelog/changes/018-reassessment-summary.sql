--liquibase formatted sql

--changeset mentalbridge:care-019-reassessment-summary runInTransaction:true
CREATE TABLE reassessment_summary (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES user_profile(account_id) ON DELETE RESTRICT,
    idempotency_key varchar(128) NOT NULL,
    request_hash char(64) NOT NULL,
    summary_version varchar(64) NOT NULL,
    journal_analysis_id uuid NOT NULL,
    previous_period_start timestamptz NOT NULL,
    previous_period_end timestamptz NOT NULL,
    current_period_start timestamptz NOT NULL,
    current_period_end timestamptz NOT NULL,
    snapshot jsonb NOT NULL,
    composed_at timestamptz NOT NULL,
    CONSTRAINT uq_reassessment_summary_user_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT ck_reassessment_summary_idempotency_key CHECK (length(idempotency_key) BETWEEN 16 AND 128),
    CONSTRAINT ck_reassessment_summary_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_reassessment_summary_version CHECK (summary_version = 'reassessment-summary-v1'),
    CONSTRAINT ck_reassessment_summary_periods CHECK (
        previous_period_start < previous_period_end
        AND previous_period_end <= current_period_start
        AND current_period_start < current_period_end
        AND previous_period_end - previous_period_start = current_period_end - current_period_start
        AND previous_period_end - previous_period_start BETWEEN interval '7 days' AND interval '31 days'
    ),
    CONSTRAINT ck_reassessment_summary_snapshot_object CHECK (jsonb_typeof(snapshot) = 'object')
);

CREATE INDEX ix_reassessment_summary_owner_history
    ON reassessment_summary (user_id, composed_at DESC, id DESC);
