--liquibase formatted sql

--changeset mentalbridge:care-001-pgcrypto runInTransaction:true
CREATE EXTENSION IF NOT EXISTS pgcrypto;

--changeset mentalbridge:care-002-profile-consent runInTransaction:true
CREATE TABLE user_profile (
    account_id uuid PRIMARY KEY,
    display_name varchar(120) NOT NULL,
    date_of_birth date,
    gender varchar(32),
    locale varchar(16) NOT NULL DEFAULT 'vi-VN',
    timezone varchar(64) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
    reminder_enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_user_profile_display_name CHECK (length(btrim(display_name)) > 0),
    CONSTRAINT ck_user_profile_locale CHECK (locale ~ '^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$'),
    CONSTRAINT ck_user_profile_timezone CHECK (length(btrim(timezone)) > 0),
    CONSTRAINT ck_user_profile_version CHECK (version >= 0),
    CONSTRAINT ck_user_profile_timestamps CHECK (updated_at >= created_at)
);

CREATE TABLE consent_decision (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES user_profile(account_id) ON DELETE RESTRICT,
    consent_type varchar(40) NOT NULL,
    policy_version varchar(64) NOT NULL,
    granted boolean NOT NULL,
    evidence jsonb NOT NULL DEFAULT '{}'::jsonb,
    idempotency_key varchar(128) NOT NULL,
    request_hash varchar(64) NOT NULL,
    decided_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_consent_decision_idempotency UNIQUE (user_id, consent_type, idempotency_key),
    CONSTRAINT ck_consent_decision_type CHECK (consent_type IN (
        'PRIVACY_POLICY', 'AI_PROCESSING', 'RESEARCH_DATA', 'MARKETING_NOTIFICATION'
    )),
    CONSTRAINT ck_consent_decision_policy_version CHECK (length(btrim(policy_version)) > 0),
    CONSTRAINT ck_consent_decision_evidence CHECK (jsonb_typeof(evidence) = 'object'),
    CONSTRAINT ck_consent_decision_idempotency_key CHECK (length(idempotency_key) BETWEEN 16 AND 128),
    CONSTRAINT ck_consent_decision_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_consent_decision_latest
    ON consent_decision (user_id, consent_type, decided_at DESC, id DESC);
