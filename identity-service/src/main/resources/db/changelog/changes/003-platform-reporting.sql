--liquibase formatted sql

--changeset mentalbridge:identity-008-platform-reporting runInTransaction:true
CREATE TABLE platform_report_job (
    id uuid PRIMARY KEY,
    report_type varchar(64) NOT NULL,
    scope_version varchar(64) NOT NULL,
    period_start date NOT NULL,
    period_end date NOT NULL,
    requested_by uuid NOT NULL REFERENCES account(id),
    requested_at timestamptz NOT NULL,
    status varchar(24) NOT NULL,
    source_versions jsonb NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_hash char(64) NOT NULL,
    retry_of uuid REFERENCES platform_report_job(id),
    started_at timestamptz,
    completed_at timestamptz,
    failed_at timestamptz,
    failure_code varchar(64),
    CONSTRAINT ux_platform_report_request UNIQUE (requested_by, idempotency_key),
    CONSTRAINT ck_platform_report_type CHECK (report_type IN ('ACCOUNT_ACTIVITY')),
    CONSTRAINT ck_platform_report_status CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'STALE')),
    CONSTRAINT ck_platform_report_period CHECK (period_end >= period_start AND period_end <= period_start + 365),
    CONSTRAINT ck_platform_report_source_versions CHECK (jsonb_typeof(source_versions) = 'object'),
    CONSTRAINT ck_platform_report_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_platform_report_terminal CHECK (
        (status = 'COMPLETED' AND completed_at IS NOT NULL AND failed_at IS NULL AND failure_code IS NULL) OR
        (status IN ('FAILED', 'STALE') AND completed_at IS NULL AND failed_at IS NOT NULL AND failure_code IS NOT NULL) OR
        (status IN ('QUEUED', 'RUNNING') AND completed_at IS NULL AND failed_at IS NULL AND failure_code IS NULL)
    )
);

CREATE INDEX ix_platform_report_history
    ON platform_report_job (requested_at DESC, id DESC);
CREATE INDEX ix_platform_report_queue
    ON platform_report_job (requested_at, id)
    WHERE status = 'QUEUED';

CREATE TABLE platform_report_artifact (
    report_job_id uuid PRIMARY KEY REFERENCES platform_report_job(id),
    media_type varchar(96) NOT NULL,
    file_name varchar(160) NOT NULL,
    content bytea NOT NULL,
    content_sha256 char(64) NOT NULL,
    content_length bigint NOT NULL,
    generated_at timestamptz NOT NULL,
    retained_until timestamptz NOT NULL,
    CONSTRAINT ck_platform_report_artifact_hash CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_platform_report_artifact_length CHECK (content_length > 0 AND content_length <= 1048576),
    CONSTRAINT ck_platform_report_artifact_retention CHECK (retained_until > generated_at)
);

CREATE INDEX ix_platform_report_artifact_retention
    ON platform_report_artifact (retained_until);
