--liquibase formatted sql

--changeset mentalbridge:care-020-reassessment-self-report runInTransaction:true
ALTER TABLE reassessment_summary
    DROP CONSTRAINT ck_reassessment_summary_version;

ALTER TABLE reassessment_summary
    ADD CONSTRAINT ck_reassessment_summary_version
    CHECK (summary_version IN ('reassessment-summary-v1', 'reassessment-summary-v2'));

ALTER TABLE reassessment_summary
    ALTER COLUMN journal_analysis_id DROP NOT NULL,
    ADD COLUMN journal_job_id uuid;

ALTER TABLE reassessment_summary
    ADD CONSTRAINT ck_reassessment_summary_journal_reference CHECK (
        (summary_version = 'reassessment-summary-v1'
            AND journal_analysis_id IS NOT NULL AND journal_job_id IS NULL)
        OR (summary_version = 'reassessment-summary-v2' AND journal_job_id IS NOT NULL)
    );

CREATE TABLE reassessment_self_report (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES user_profile(account_id) ON DELETE RESTRICT,
    idempotency_key varchar(128) NOT NULL,
    request_hash char(64) NOT NULL,
    source_version varchar(64) NOT NULL,
    current_period_start timestamptz NOT NULL,
    current_period_end timestamptz NOT NULL,
    current_experience varchar(32),
    helpful_context varchar(500),
    difficult_context varchar(500),
    version bigint NOT NULL DEFAULT 0,
    authored_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    deleted_at timestamptz,
    CONSTRAINT uq_reassessment_self_report_user_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT ck_reassessment_self_report_idempotency_key CHECK (length(idempotency_key) BETWEEN 16 AND 128),
    CONSTRAINT ck_reassessment_self_report_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_reassessment_self_report_source_version CHECK (source_version = 'reassessment-self-report-v1'),
    CONSTRAINT ck_reassessment_self_report_period CHECK (
        current_period_start < current_period_end
        AND current_period_end - current_period_start BETWEEN interval '7 days' AND interval '31 days'
    ),
    CONSTRAINT ck_reassessment_self_report_experience CHECK (
        current_experience IS NULL OR current_experience IN
            ('BETTER', 'ABOUT_THE_SAME', 'MORE_DIFFICULT', 'UNSURE')
    ),
    CONSTRAINT ck_reassessment_self_report_lifecycle CHECK (
        (deleted_at IS NULL AND current_experience IS NOT NULL)
        OR (deleted_at IS NOT NULL AND current_experience IS NULL
            AND helpful_context IS NULL AND difficult_context IS NULL)
    ),
    CONSTRAINT ck_reassessment_self_report_context CHECK (
        helpful_context IS NULL OR length(btrim(helpful_context)) BETWEEN 1 AND 500
    ),
    CONSTRAINT ck_reassessment_self_report_difficulty CHECK (
        difficult_context IS NULL OR length(btrim(difficult_context)) BETWEEN 1 AND 500
    )
);

CREATE INDEX ix_reassessment_self_report_owner_current
    ON reassessment_self_report (user_id, updated_at DESC, id DESC)
    WHERE deleted_at IS NULL;
