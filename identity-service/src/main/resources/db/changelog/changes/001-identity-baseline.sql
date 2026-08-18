--liquibase formatted sql

--changeset mentalbridge:identity-001-extensions runInTransaction:true
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;

--changeset mentalbridge:identity-003-account-and-roles runInTransaction:true
CREATE TABLE account (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email citext NOT NULL,
    password_hash varchar(255) NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'PENDING_EMAIL_VERIFICATION',
    email_verified_at timestamptz,
    failed_login_count integer NOT NULL DEFAULT 0,
    locked_until timestamptz,
    last_login_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ux_account_email UNIQUE (email),
    CONSTRAINT ck_account_status CHECK (status IN (
        'PENDING_EMAIL_VERIFICATION', 'ACTIVE', 'DISABLED', 'DELETION_PENDING', 'DELETED'
    )),
    CONSTRAINT ck_account_failed_login_count CHECK (failed_login_count >= 0),
    CONSTRAINT ck_account_version CHECK (version >= 0),
    CONSTRAINT ck_account_verified_state CHECK (
        status <> 'ACTIVE' OR email_verified_at IS NOT NULL
    ),
    CONSTRAINT ck_account_deleted_at CHECK (
        (status = 'DELETED' AND deleted_at IS NOT NULL) OR
        (status <> 'DELETED' AND deleted_at IS NULL)
    )
);

CREATE TABLE role (
    code varchar(32) PRIMARY KEY,
    description varchar(255) NOT NULL,
    CONSTRAINT ck_role_code CHECK (code IN ('USER', 'SPECIALIST', 'ADMIN'))
);

CREATE TABLE account_role (
    account_id uuid NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    role_code varchar(32) NOT NULL REFERENCES role(code),
    granted_by uuid REFERENCES account(id),
    granted_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, role_code),
    CONSTRAINT ck_account_role_grant CHECK (granted_by IS NULL OR granted_by <> account_id)
);

INSERT INTO role (code, description) VALUES
    ('USER', 'End user account'),
    ('SPECIALIST', 'Specialist actor; professional eligibility remains owned by Consultation Service'),
    ('ADMIN', 'Bounded platform administrator');

CREATE INDEX ix_account_created_page ON account (created_at DESC, id DESC);
CREATE INDEX ix_account_status_created_page ON account (status, created_at DESC, id DESC);
CREATE INDEX ix_account_role_role_account ON account_role (role_code, account_id);

--changeset mentalbridge:identity-004-refresh-sessions runInTransaction:true
CREATE TABLE refresh_session (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id uuid NOT NULL,
    account_id uuid NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    token_hash char(64) NOT NULL,
    device_label varchar(120),
    ip_hash char(64),
    user_agent_hash char(64),
    expires_at timestamptz NOT NULL,
    rotated_from_id uuid REFERENCES refresh_session(id),
    revoked_at timestamptz,
    revoke_reason varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_refresh_session_token_hash UNIQUE (token_hash),
    CONSTRAINT ux_refresh_session_rotated_from UNIQUE (rotated_from_id),
    CONSTRAINT ck_refresh_session_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_refresh_session_revocation CHECK (
        (revoked_at IS NULL AND revoke_reason IS NULL) OR
        (revoked_at IS NOT NULL AND revoke_reason IS NOT NULL)
    ),
    CONSTRAINT ck_refresh_session_ip_hash CHECK (ip_hash IS NULL OR ip_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_refresh_session_user_agent_hash CHECK (user_agent_hash IS NULL OR user_agent_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_refresh_session_token_hash CHECK (token_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_refresh_session_account_active
    ON refresh_session (account_id, expires_at DESC)
    WHERE revoked_at IS NULL;
CREATE INDEX ix_refresh_session_family_active
    ON refresh_session (family_id, created_at)
    WHERE revoked_at IS NULL;

--changeset mentalbridge:identity-005-challenges-idempotency runInTransaction:true
CREATE TABLE one_time_token (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id uuid NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    purpose varchar(32) NOT NULL,
    token_hash char(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    invalidated_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_one_time_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_one_time_token_purpose CHECK (purpose IN ('VERIFY_EMAIL', 'RESET_PASSWORD')),
    CONSTRAINT ck_one_time_token_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_one_time_token_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_one_time_token_terminal CHECK (consumed_at IS NULL OR invalidated_at IS NULL)
);

CREATE UNIQUE INDEX ux_one_time_token_active_purpose
    ON one_time_token (account_id, purpose)
    WHERE consumed_at IS NULL AND invalidated_at IS NULL;
CREATE INDEX ix_one_time_token_expiry
    ON one_time_token (expires_at)
    WHERE consumed_at IS NULL AND invalidated_at IS NULL;

CREATE TABLE idempotency_record (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    operation varchar(64) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_hash char(64) NOT NULL,
    account_id uuid REFERENCES account(id) ON DELETE CASCADE,
    response_status smallint,
    response_ciphertext bytea,
    encryption_key_version varchar(64),
    completed_at timestamptz,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_idempotency_operation_key UNIQUE (operation, idempotency_key),
    CONSTRAINT ck_idempotency_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_idempotency_status CHECK (response_status IS NULL OR response_status BETWEEN 200 AND 599),
    CONSTRAINT ck_idempotency_completion CHECK (
        (completed_at IS NULL AND response_status IS NULL AND response_ciphertext IS NULL AND encryption_key_version IS NULL) OR
        (completed_at IS NOT NULL AND response_status IS NOT NULL AND response_ciphertext IS NOT NULL AND encryption_key_version IS NOT NULL)
    ),
    CONSTRAINT ck_idempotency_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_idempotency_expiry ON idempotency_record (expires_at);

--changeset mentalbridge:identity-006-outbox-and-audit runInTransaction:true
CREATE TABLE outbox_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    message_type varchar(120) NOT NULL,
    schema_version varchar(24) NOT NULL,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id uuid NOT NULL,
    aggregate_version bigint NOT NULL,
    correlation_id uuid NOT NULL,
    payload jsonb NOT NULL,
    occurred_at timestamptz NOT NULL,
    published_at timestamptz,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_outbox_payload_object CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_outbox_aggregate_version CHECK (aggregate_version >= 0),
    CONSTRAINT ck_outbox_attempt_count CHECK (attempt_count >= 0)
);

CREATE INDEX ix_outbox_pending
    ON outbox_event (COALESCE(next_attempt_at, occurred_at), occurred_at, id)
    WHERE published_at IS NULL;
CREATE UNIQUE INDEX ux_outbox_aggregate_message_version
    ON outbox_event (aggregate_type, aggregate_id, aggregate_version, message_type);

CREATE TABLE security_audit_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id uuid REFERENCES account(id) ON DELETE SET NULL,
    actor_id uuid REFERENCES account(id) ON DELETE SET NULL,
    action varchar(96) NOT NULL,
    outcome varchar(32) NOT NULL,
    reason_code varchar(64),
    correlation_id uuid NOT NULL,
    subject_reference_hash char(64),
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_security_audit_outcome CHECK (outcome IN ('SUCCEEDED', 'DENIED', 'FAILED')),
    CONSTRAINT ck_security_audit_subject_hash CHECK (
        subject_reference_hash IS NULL OR subject_reference_hash ~ '^[0-9a-f]{64}$'
    )
);

CREATE INDEX ix_security_audit_account_history
    ON security_audit_event (account_id, occurred_at DESC, id DESC);
CREATE INDEX ix_security_audit_occurred
    ON security_audit_event (occurred_at DESC, id DESC);
