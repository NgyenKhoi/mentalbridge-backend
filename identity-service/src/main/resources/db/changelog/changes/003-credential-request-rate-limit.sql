--liquibase formatted sql

--changeset mentalbridge:identity-008-credential-request-rate-limit runInTransaction:true
CREATE TABLE credential_request_rate_limit (
    subject_key_hash char(64) NOT NULL,
    purpose varchar(32) NOT NULL,
    window_started_at timestamptz NOT NULL,
    request_count integer NOT NULL,
    last_requested_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (subject_key_hash, purpose),
    CONSTRAINT ck_credential_request_rate_limit_hash CHECK (subject_key_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_credential_request_rate_limit_purpose CHECK (purpose IN ('VERIFY_EMAIL', 'RESET_PASSWORD')),
    CONSTRAINT ck_credential_request_rate_limit_count CHECK (request_count BETWEEN 1 AND 3),
    CONSTRAINT ck_credential_request_rate_limit_time_order CHECK (
        created_at <= updated_at AND window_started_at <= last_requested_at AND last_requested_at <= updated_at
    )
);

CREATE INDEX ix_credential_request_rate_limit_updated
    ON credential_request_rate_limit (updated_at);
