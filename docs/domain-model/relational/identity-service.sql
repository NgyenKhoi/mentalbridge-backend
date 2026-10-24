/*
 * MENTALBRIDGE CANONICAL LOGICAL DATA MODEL
 *
 * Purpose:
 * - Canonical relational reference for architecture documentation.
 * - ERD and domain modelling.
 * - Cross-service entity and relationship understanding.
 *
 * THIS FILE IS READ-ONLY AND NON-EXECUTABLE.
 *
 * Runtime database source of truth:
 * - Each service's owner-specific migration history.
 *
 * This file must never be used to provision, initialize,
 * migrate, or modify a runtime database.
 *
 * Any persisted model change introduced by a service migration
 * must update this logical model in the same pull request.
 *
 * The identity, care, consultation, community, and content qualifiers below are visual
 * owner namespaces only. Runtime PostgreSQL uses a separate owner database and
 * its default public schema. Cross-owner identifiers are logical/external
 * references and are deliberately not physical foreign keys.
 *
 * Reconciled from owner migrations through 2026-09-29. It includes the Care
 * SupportPlan, immutable Reassessment Summary, and Community feed migrations. Technical indexes and migration bookkeeping are
 * intentionally omitted; owner migrations remain authoritative for exact DDL.
 */

/* ========================================================================== */
/* ACTIVE — identity-service / mentalbridge_identity                          */
/* Evidence: identity Liquibase changesets 001-002.                           */
/* ========================================================================== */

CREATE TABLE identity.role (
    code varchar(32) PRIMARY KEY,
    description varchar(255) NOT NULL
);

CREATE TABLE identity.account (
    id uuid PRIMARY KEY,
    email citext NOT NULL UNIQUE,
    password_hash varchar(255) NOT NULL,
    role_code varchar(32) NOT NULL REFERENCES identity.role(code),
    status varchar(32) NOT NULL,
    email_verified_at timestamptz,
    failed_login_count integer NOT NULL,
    locked_until timestamptz,
    last_login_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    deleted_at timestamptz,
    version bigint NOT NULL
);

CREATE TABLE identity.refresh_session (
    id uuid PRIMARY KEY,
    family_id uuid NOT NULL,
    account_id uuid NOT NULL REFERENCES identity.account(id),
    token_hash char(64) NOT NULL UNIQUE,
    device_label varchar(120),
    ip_hash char(64),
    user_agent_hash char(64),
    expires_at timestamptz NOT NULL,
    rotated_from_id uuid UNIQUE REFERENCES identity.refresh_session(id),
    revoked_at timestamptz,
    revoke_reason varchar(64),
    created_at timestamptz NOT NULL
);

CREATE TABLE identity.one_time_token (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES identity.account(id),
    purpose varchar(32) NOT NULL,
    token_hash char(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    invalidated_at timestamptz,
    created_at timestamptz NOT NULL
);

CREATE TABLE identity.idempotency_record (
    id uuid PRIMARY KEY,
    operation varchar(64) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_hash char(64) NOT NULL,
    account_id uuid REFERENCES identity.account(id),
    response_status smallint,
    response_ciphertext bytea,
    encryption_key_version varchar(64),
    completed_at timestamptz,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (operation, idempotency_key)
);

CREATE TABLE identity.outbox_event (
    id uuid PRIMARY KEY,
    message_type varchar(120) NOT NULL,
    schema_version varchar(24) NOT NULL,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id uuid NOT NULL,
    aggregate_version bigint NOT NULL,
    correlation_id uuid NOT NULL,
    payload jsonb NOT NULL,
    occurred_at timestamptz NOT NULL,
    published_at timestamptz,
    attempt_count integer NOT NULL,
    next_attempt_at timestamptz,
    created_at timestamptz NOT NULL
);

CREATE TABLE identity.security_audit_event (
    id uuid PRIMARY KEY,
    account_id uuid REFERENCES identity.account(id),
    actor_id uuid REFERENCES identity.account(id),
    actor_type varchar(16) NOT NULL DEFAULT 'ADMIN',
    actor_reference_hash char(64), -- retained safe actor tombstone after account deletion
    action varchar(96) NOT NULL,
    outcome varchar(32) NOT NULL,
    reason_code varchar(64),
    correlation_id uuid NOT NULL,
    subject_reference_hash char(64), -- retained safe target tombstone after account deletion
    source_service varchar(32) NOT NULL DEFAULT 'IDENTITY',
    domain varchar(64) NOT NULL DEFAULT 'ACCOUNT_ADMINISTRATION',
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE TABLE identity.platform_report_schedule (
    id uuid PRIMARY KEY,
    report_type varchar(64) NOT NULL CHECK (report_type = 'ACCOUNT_ACTIVITY'),
    cadence varchar(16) NOT NULL CHECK (cadence IN ('DAILY', 'WEEKLY', 'MONTHLY')),
    timezone varchar(64) NOT NULL,
    local_time time NOT NULL CHECK (extract(second FROM local_time) = 0),
    period_days integer NOT NULL CHECK (period_days BETWEEN 1 AND 366),
    recipient_group varchar(16) NOT NULL CHECK (recipient_group = 'ADMIN'),
    delivery_target varchar(24) NOT NULL CHECK (delivery_target = 'ADMIN_REPORT_HISTORY'),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'PAUSED', 'DELETED')),
    created_by uuid NOT NULL REFERENCES identity.account(id),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    next_run_at timestamptz NOT NULL,
    last_failure_code varchar(64),
    version bigint NOT NULL DEFAULT 0
);

CREATE TABLE identity.platform_report_job (
    id uuid PRIMARY KEY,
    report_type varchar(64) NOT NULL,
    scope_version varchar(64) NOT NULL,
    period_start date NOT NULL,
    period_end date NOT NULL,
    requested_by uuid NOT NULL REFERENCES identity.account(id),
    requested_at timestamptz NOT NULL,
    status varchar(24) NOT NULL,
    source_versions jsonb NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_hash char(64) NOT NULL,
    retry_of uuid REFERENCES identity.platform_report_job(id),
    schedule_id uuid REFERENCES identity.platform_report_schedule(id),
    scheduled_for timestamptz,
    UNIQUE (schedule_id, scheduled_for),
    CHECK ((schedule_id IS NULL) = (scheduled_for IS NULL)),
    started_at timestamptz,
    completed_at timestamptz,
    failed_at timestamptz,
    failure_code varchar(64),
    UNIQUE (requested_by, idempotency_key)
);

CREATE TABLE identity.platform_report_artifact (
    report_job_id uuid PRIMARY KEY REFERENCES identity.platform_report_job(id),
    media_type varchar(96) NOT NULL,
    file_name varchar(160) NOT NULL,
    content bytea NOT NULL,
    content_sha256 char(64) NOT NULL,
    content_length bigint NOT NULL,
    generated_at timestamptz NOT NULL,
    retained_until timestamptz NOT NULL
);
