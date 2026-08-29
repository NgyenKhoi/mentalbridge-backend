BEGIN;

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE SCHEMA IF NOT EXISTS identity;
CREATE SCHEMA IF NOT EXISTS care;
CREATE SCHEMA IF NOT EXISTS consultation;
CREATE SCHEMA IF NOT EXISTS content;
CREATE SCHEMA IF NOT EXISTS ai;
CREATE SCHEMA IF NOT EXISTS platform;

CREATE TABLE identity.role (
    code varchar(32) PRIMARY KEY CHECK (code IN ('USER', 'SPECIALIST', 'ADMIN')),
    description varchar(255) NOT NULL
);

CREATE TABLE identity.account (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email citext NOT NULL UNIQUE,
    password_hash varchar(255) NOT NULL,
    role_code varchar(32) NOT NULL REFERENCES identity.role(code),
    status varchar(24) NOT NULL DEFAULT 'PENDING_VERIFICATION'
        CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'DISABLED', 'LOCKED', 'DELETION_PENDING', 'DELETED')),
    email_verified_at timestamptz,
    failed_login_count integer NOT NULL DEFAULT 0 CHECK (failed_login_count >= 0),
    locked_until timestamptz,
    last_login_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    version bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX ux_identity_single_admin
    ON identity.account (role_code)
    WHERE role_code = 'ADMIN';

CREATE TABLE identity.refresh_session (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id uuid NOT NULL REFERENCES identity.account(id) ON DELETE CASCADE,
    token_hash varchar(255) NOT NULL UNIQUE,
    device_label varchar(120),
    ip_hash varchar(255),
    user_agent_hash varchar(255),
    expires_at timestamptz NOT NULL,
    rotated_from_id uuid REFERENCES identity.refresh_session(id),
    revoked_at timestamptz,
    revoke_reason varchar(120),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (expires_at > created_at)
);
CREATE INDEX ix_refresh_session_account_active
    ON identity.refresh_session (account_id, expires_at DESC) WHERE revoked_at IS NULL;

CREATE TABLE identity.one_time_token (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id uuid NOT NULL REFERENCES identity.account(id) ON DELETE CASCADE,
    purpose varchar(32) NOT NULL CHECK (purpose IN ('VERIFY_EMAIL', 'RESET_PASSWORD')),
    token_hash varchar(255) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (expires_at > created_at)
);
CREATE INDEX ix_one_time_token_account_purpose
    ON identity.one_time_token (account_id, purpose, expires_at DESC) WHERE consumed_at IS NULL;

CREATE TABLE care.user_profile (
    account_id uuid PRIMARY KEY REFERENCES identity.account(id),
    display_name varchar(120) NOT NULL,
    date_of_birth date,
    gender varchar(32),
    locale varchar(16) NOT NULL DEFAULT 'vi-VN',
    timezone varchar(64) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
    reminder_enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CHECK (length(btrim(display_name)) > 0),
    CHECK (version >= 0),
    CHECK (updated_at >= created_at)
);

CREATE TABLE care.consent_decision (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES care.user_profile(account_id),
    consent_type varchar(40) NOT NULL
        CHECK (consent_type IN ('PRIVACY_POLICY', 'AI_PROCESSING', 'RESEARCH_DATA', 'MARKETING_NOTIFICATION')),
    policy_version varchar(64) NOT NULL,
    granted boolean NOT NULL,
    evidence jsonb NOT NULL DEFAULT '{}'::jsonb,
    idempotency_key varchar(128) NOT NULL,
    request_hash varchar(64) NOT NULL,
    decided_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, consent_type, idempotency_key),
    CHECK (jsonb_typeof(evidence) = 'object'),
    CHECK (length(idempotency_key) BETWEEN 16 AND 128),
    CHECK (request_hash ~ '^[0-9a-f]{64}$')
);
CREATE INDEX ix_consent_decision_latest
    ON care.consent_decision (user_id, consent_type, decided_at DESC, id DESC);

CREATE TABLE care.anonymous_assessment_session (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash varchar(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    closed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CHECK (expires_at > created_at),
    CHECK (closed_at IS NULL OR closed_at >= created_at)
);
CREATE INDEX ix_anonymous_assessment_session_expiry
    ON care.anonymous_assessment_session (expires_at, id) WHERE closed_at IS NULL;

CREATE TABLE care.questionnaire_definition (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    instrument varchar(16) NOT NULL CHECK (instrument IN ('PHQ9', 'GAD7')),
    version varchar(32) NOT NULL,
    locale varchar(16) NOT NULL DEFAULT 'vi-VN',
    title varchar(255) NOT NULL,
    reference_period_days smallint NOT NULL CHECK (reference_period_days > 0),
    expected_question_count smallint NOT NULL,
    scoring_version varchar(32) NOT NULL,
    response_options jsonb NOT NULL CHECK (
        jsonb_typeof(response_options) = 'array' AND jsonb_array_length(response_options) = 4
    ),
    source_reference varchar(512) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (instrument, version, locale),
    CHECK (
        (instrument = 'PHQ9' AND expected_question_count = 9) OR
        (instrument = 'GAD7' AND expected_question_count = 7)
    ),
    CHECK (
        (status = 'DRAFT' AND published_at IS NULL) OR
        (status IN ('PUBLISHED', 'RETIRED') AND published_at IS NOT NULL)
    )
);
CREATE UNIQUE INDEX ux_questionnaire_definition_current
    ON care.questionnaire_definition (instrument, locale) WHERE status = 'PUBLISHED';

CREATE TABLE care.questionnaire_question (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    definition_id uuid NOT NULL REFERENCES care.questionnaire_definition(id),
    item_number smallint NOT NULL CHECK (item_number BETWEEN 1 AND 32),
    prompt text NOT NULL,
    safety_item boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (definition_id, item_number),
    UNIQUE (definition_id, id),
    CHECK (length(btrim(prompt)) > 0)
);

CREATE TABLE care.questionnaire_score_band (
    definition_id uuid NOT NULL REFERENCES care.questionnaire_definition(id),
    code varchar(24) NOT NULL CHECK (code IN ('MINIMAL', 'MILD', 'MODERATE', 'MODERATELY_SEVERE', 'SEVERE')),
    minimum_score smallint NOT NULL,
    maximum_score smallint NOT NULL,
    ordinal smallint NOT NULL CHECK (ordinal > 0),
    PRIMARY KEY (definition_id, code),
    UNIQUE (definition_id, ordinal),
    UNIQUE (definition_id, minimum_score),
    CHECK (minimum_score >= 0 AND maximum_score <= 27 AND minimum_score <= maximum_score)
);

CREATE TABLE care.assessment_submission (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid REFERENCES care.user_profile(account_id),
    anonymous_session_id uuid REFERENCES care.anonymous_assessment_session(id),
    definition_id uuid NOT NULL REFERENCES care.questionnaire_definition(id),
    idempotency_key varchar(128) NOT NULL,
    request_hash varchar(64) NOT NULL,
    submitted_at timestamptz NOT NULL,
    retention_expires_at timestamptz,
    voided_at timestamptz,
    void_reason_code varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, definition_id),
    CHECK (
        (user_id IS NOT NULL AND anonymous_session_id IS NULL AND retention_expires_at IS NULL) OR
        (user_id IS NULL AND anonymous_session_id IS NOT NULL AND retention_expires_at > submitted_at)
    ),
    CHECK (length(idempotency_key) BETWEEN 16 AND 128),
    CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CHECK (
        (voided_at IS NULL AND void_reason_code IS NULL) OR
        (voided_at IS NOT NULL AND voided_at >= submitted_at AND void_reason_code IS NOT NULL)
    )
);
CREATE UNIQUE INDEX ux_assessment_user_idempotency
    ON care.assessment_submission (user_id, idempotency_key)
    WHERE user_id IS NOT NULL;
CREATE UNIQUE INDEX ux_assessment_anonymous_idempotency
    ON care.assessment_submission (anonymous_session_id, idempotency_key)
    WHERE anonymous_session_id IS NOT NULL;
CREATE INDEX ix_assessment_user_history
    ON care.assessment_submission (user_id, submitted_at DESC) WHERE voided_at IS NULL;
CREATE INDEX ix_assessment_anonymous_expiry
    ON care.assessment_submission (retention_expires_at, id) WHERE anonymous_session_id IS NOT NULL;

CREATE TABLE care.assessment_answer (
    submission_id uuid NOT NULL,
    definition_id uuid NOT NULL,
    question_id uuid NOT NULL,
    answer_value smallint NOT NULL CHECK (answer_value BETWEEN 0 AND 3),
    PRIMARY KEY (submission_id, question_id),
    FOREIGN KEY (submission_id, definition_id)
        REFERENCES care.assessment_submission(id, definition_id) ON DELETE CASCADE,
    FOREIGN KEY (definition_id, question_id)
        REFERENCES care.questionnaire_question(definition_id, id)
);

CREATE TABLE care.assessment_result (
    submission_id uuid PRIMARY KEY REFERENCES care.assessment_submission(id) ON DELETE CASCADE,
    total_score smallint NOT NULL CHECK (total_score BETWEEN 0 AND 27),
    screening_level varchar(24) NOT NULL
        CHECK (screening_level IN ('MINIMAL', 'MILD', 'MODERATE', 'MODERATELY_SEVERE', 'SEVERE')),
    scoring_version varchar(32) NOT NULL,
    safety_item_positive boolean NOT NULL,
    disclaimer_code varchar(64) NOT NULL DEFAULT 'SCREENING_NOT_DIAGNOSIS'
        CHECK (disclaimer_code = 'SCREENING_NOT_DIAGNOSIS'),
    calculated_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE care.outbox_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    message_type varchar(120) NOT NULL,
    schema_version varchar(24) NOT NULL,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id uuid NOT NULL,
    aggregate_version bigint NOT NULL CHECK (aggregate_version >= 0),
    correlation_id uuid NOT NULL,
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    occurred_at timestamptz NOT NULL,
    published_at timestamptz,
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (aggregate_type, aggregate_id, aggregate_version, message_type)
);
CREATE INDEX ix_care_outbox_pending
    ON care.outbox_event (COALESCE(next_attempt_at, occurred_at), occurred_at, id) WHERE published_at IS NULL;

CREATE TABLE care.risk_classification (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES care.user_profile(account_id),
    level varchar(24) NOT NULL CHECK (level IN ('MINIMAL', 'MILD', 'MODERATE', 'SEVERE', 'INSUFFICIENT_DATA')),
    policy_version varchar(32) NOT NULL,
    reason_codes jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(reason_codes) = 'array'),
    source_assessment_ids jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(source_assessment_ids) = 'array'),
    source_analysis_ids jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(source_analysis_ids) = 'array'),
    safety_flag boolean NOT NULL DEFAULT false,
    calculated_at timestamptz NOT NULL DEFAULT now(),
    superseded_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_risk_user_current
    ON care.risk_classification (user_id, calculated_at DESC) WHERE superseded_at IS NULL;

CREATE TABLE care.intervention_plan (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES care.user_profile(account_id),
    risk_classification_id uuid NOT NULL REFERENCES care.risk_classification(id),
    template_code varchar(64) NOT NULL,
    template_version varchar(32) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'COMPLETED', 'SUPERSEDED', 'CANCELLED')),
    actions jsonb NOT NULL CHECK (jsonb_typeof(actions) = 'array'),
    generated_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0
);
CREATE INDEX ix_intervention_user_status ON care.intervention_plan (user_id, status, generated_at DESC);

CREATE TABLE consultation.specialist_profile (
    account_id uuid PRIMARY KEY REFERENCES identity.account(id),
    display_name varchar(120) NOT NULL,
    biography text,
    years_experience smallint CHECK (years_experience >= 0),
    timezone varchar(64) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
    approval_status varchar(24) NOT NULL DEFAULT 'NOT_SUBMITTED'
        CHECK (approval_status IN ('NOT_SUBMITTED', 'PENDING', 'APPROVED', 'REJECTED', 'SUSPENDED')),
    submitted_at timestamptz,
    reviewed_at timestamptz,
    reviewed_by uuid REFERENCES identity.account(id),
    decision_reason_code varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CHECK (approval_status = 'NOT_SUBMITTED' OR submitted_at IS NOT NULL),
    CHECK (approval_status NOT IN ('APPROVED', 'REJECTED', 'SUSPENDED') OR (reviewed_at IS NOT NULL AND reviewed_by IS NOT NULL)),
    CHECK (approval_status NOT IN ('REJECTED', 'SUSPENDED') OR decision_reason_code IS NOT NULL)
);
CREATE INDEX ix_specialist_approved ON consultation.specialist_profile (display_name)
    WHERE approval_status = 'APPROVED';

CREATE TABLE consultation.specialty (
    code varchar(64) PRIMARY KEY,
    display_name varchar(120) NOT NULL,
    active boolean NOT NULL DEFAULT true
);

CREATE TABLE consultation.specialist_specialty (
    specialist_id uuid NOT NULL REFERENCES consultation.specialist_profile(account_id) ON DELETE CASCADE,
    specialty_code varchar(64) NOT NULL REFERENCES consultation.specialty(code),
    PRIMARY KEY (specialist_id, specialty_code)
);

CREATE TABLE consultation.subscription_plan (
    code varchar(32) PRIMARY KEY CHECK (code IN ('FREE', 'PREMIUM_CARE', 'PREMIUM_PLUS')),
    display_name varchar(80) NOT NULL,
    tier_rank smallint NOT NULL UNIQUE CHECK (tier_rank BETWEEN 0 AND 2),
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE consultation.subscription_plan_version (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_code varchar(32) NOT NULL REFERENCES consultation.subscription_plan(code),
    version integer NOT NULL CHECK (version > 0),
    currency char(3) NOT NULL,
    price_minor bigint NOT NULL CHECK (price_minor >= 0),
    billing_period_months smallint NOT NULL CHECK (billing_period_months = 1),
    consultation_credits_per_period smallint NOT NULL CHECK (consultation_credits_per_period >= 0),
    non_consultation_value_minor bigint NOT NULL CHECK (non_consultation_value_minor >= 0),
    credit_value_minor bigint NOT NULL CHECK (credit_value_minor >= 0),
    specialist_share_bps smallint NOT NULL CHECK (specialist_share_bps BETWEEN 0 AND 10000),
    cancellation_cutoff_hours smallint CHECK (cancellation_cutoff_hours >= 0),
    effective_from timestamptz NOT NULL,
    retired_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (plan_code, version),
    CHECK (retired_at IS NULL OR retired_at > effective_from),
    CHECK (price_minor = non_consultation_value_minor + consultation_credits_per_period * credit_value_minor),
    CHECK ((plan_code = 'FREE' AND price_minor = 0 AND consultation_credits_per_period = 0 AND specialist_share_bps = 0)
        OR (plan_code <> 'FREE' AND price_minor > 0 AND consultation_credits_per_period > 0))
);

CREATE TABLE consultation.subscription_plan_entitlement (
    plan_version_id uuid NOT NULL REFERENCES consultation.subscription_plan_version(id) ON DELETE CASCADE,
    entitlement_code varchar(64) NOT NULL,
    PRIMARY KEY (plan_version_id, entitlement_code)
);

CREATE TABLE consultation.user_subscription (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES care.user_profile(account_id),
    plan_version_id uuid NOT NULL REFERENCES consultation.subscription_plan_version(id),
    status varchar(24) NOT NULL DEFAULT 'PENDING_PAYMENT'
        CHECK (status IN ('PENDING_PAYMENT', 'ACTIVE', 'CANCEL_PENDING_SESSION_END', 'PAST_DUE', 'CANCELLED', 'EXPIRED')),
    current_period_start timestamptz,
    current_period_end timestamptz,
    cancellation_requested_at timestamptz,
    ended_at timestamptz,
    idempotency_key varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (user_id, idempotency_key),
    UNIQUE (id, user_id),
    CHECK ((current_period_start IS NULL AND current_period_end IS NULL)
        OR (current_period_start IS NOT NULL AND current_period_end > current_period_start))
);
CREATE UNIQUE INDEX ux_user_subscription_current
    ON consultation.user_subscription (user_id)
    WHERE status IN ('PENDING_PAYMENT', 'ACTIVE', 'CANCEL_PENDING_SESSION_END', 'PAST_DUE');

CREATE TABLE consultation.subscription_status_history (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id uuid NOT NULL REFERENCES consultation.user_subscription(id),
    from_status varchar(24),
    to_status varchar(24) NOT NULL,
    reason_code varchar(64),
    changed_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_subscription_status_history
    ON consultation.subscription_status_history (subscription_id, changed_at);

CREATE TABLE consultation.payment_transaction (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id uuid NOT NULL REFERENCES consultation.user_subscription(id),
    payment_provider varchar(32) NOT NULL CHECK (payment_provider IN ('MOMO', 'FAKE')),
    momo_order_id varchar(200) NOT NULL,
    momo_request_id varchar(200) NOT NULL,
    momo_trans_id bigint,
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    currency char(3) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'CHARGEBACK')),
    purpose varchar(24) NOT NULL
        CHECK (purpose IN ('INITIAL_PURCHASE', 'RENEWAL', 'UPGRADE')),
    momo_result_code integer,
    momo_pay_type varchar(32),
    idempotency_key varchar(128) NOT NULL,
    momo_response_time_epoch_ms bigint,
    provider_occurred_at timestamptz,
    paid_at timestamptz,
    failed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (payment_provider, momo_order_id),
    UNIQUE (payment_provider, momo_request_id),
    UNIQUE (subscription_id, idempotency_key)
);
CREATE INDEX ix_payment_subscription_history
    ON consultation.payment_transaction (subscription_id, created_at DESC);
CREATE UNIQUE INDEX ux_payment_momo_trans_id
    ON consultation.payment_transaction (momo_trans_id)
    WHERE payment_provider = 'MOMO' AND momo_trans_id > 0;

CREATE TABLE consultation.momo_payment_ipn (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id uuid REFERENCES consultation.payment_transaction(id),
    contract_version varchar(32) NOT NULL,
    deduplication_key char(64) NOT NULL UNIQUE,
    partner_code varchar(64) NOT NULL,
    order_id varchar(200) NOT NULL,
    request_id varchar(200) NOT NULL,
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    currency char(3) NOT NULL DEFAULT 'VND' CHECK (currency = 'VND'),
    order_info_sha256 char(64) NOT NULL,
    order_type varchar(32) NOT NULL,
    trans_id bigint NOT NULL,
    result_code integer NOT NULL,
    result_message text NOT NULL,
    pay_type varchar(32) NOT NULL,
    response_time_epoch_ms bigint NOT NULL CHECK (response_time_epoch_ms > 0),
    extra_data_sha256 char(64) NOT NULL,
    provider_occurred_at timestamptz NOT NULL,
    safe_optional_details jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(safe_optional_details) = 'object'),
    payload_sha256 char(64) NOT NULL,
    signature_key_version varchar(64) NOT NULL,
    signature_verified_at timestamptz NOT NULL,
    processing_status varchar(16) NOT NULL DEFAULT 'RECEIVED'
        CHECK (processing_status IN ('RECEIVED', 'PROCESSED', 'UNMATCHED', 'FAILED')),
    failure_code varchar(64),
    received_at timestamptz NOT NULL DEFAULT now(),
    processed_at timestamptz,
    acknowledged_at timestamptz,
    CHECK (processing_status <> 'PROCESSED' OR payment_id IS NOT NULL)
);
CREATE INDEX ix_momo_payment_ipn_payment
    ON consultation.momo_payment_ipn (payment_id, received_at DESC);

CREATE TABLE consultation.consultation_credit (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES care.user_profile(account_id),
    subscription_id uuid NOT NULL REFERENCES consultation.user_subscription(id),
    source_payment_id uuid NOT NULL REFERENCES consultation.payment_transaction(id),
    plan_version_id uuid NOT NULL REFERENCES consultation.subscription_plan_version(id),
    period_start timestamptz NOT NULL,
    period_end timestamptz NOT NULL,
    ordinal smallint NOT NULL CHECK (ordinal > 0),
    currency char(3) NOT NULL,
    allocated_value_minor bigint NOT NULL CHECK (allocated_value_minor > 0),
    specialist_share_bps smallint NOT NULL CHECK (specialist_share_bps BETWEEN 0 AND 10000),
    specialist_earning_minor bigint NOT NULL CHECK (specialist_earning_minor >= 0),
    status varchar(16) NOT NULL DEFAULT 'AVAILABLE'
        CHECK (status IN ('AVAILABLE', 'RESERVED', 'UPGRADE_HELD', 'CONSUMED', 'EXPIRED', 'FORFEITED', 'REVOKED')),
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (subscription_id, period_start, ordinal),
    UNIQUE (id, user_id),
    FOREIGN KEY (subscription_id, user_id) REFERENCES consultation.user_subscription(id, user_id),
    CHECK (period_end > period_start),
    CHECK (expires_at = period_end),
    CHECK (specialist_earning_minor <= allocated_value_minor)
);
CREATE INDEX ix_credit_user_available
    ON consultation.consultation_credit (user_id, expires_at, created_at)
    WHERE status = 'AVAILABLE';

CREATE TABLE consultation.subscription_upgrade (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id uuid NOT NULL REFERENCES consultation.user_subscription(id),
    from_plan_version_id uuid NOT NULL REFERENCES consultation.subscription_plan_version(id),
    to_plan_version_id uuid NOT NULL REFERENCES consultation.subscription_plan_version(id),
    payment_id uuid UNIQUE REFERENCES consultation.payment_transaction(id),
    old_period_start timestamptz NOT NULL,
    old_period_end timestamptz NOT NULL,
    total_period_seconds bigint NOT NULL CHECK (total_period_seconds > 0),
    remaining_period_seconds bigint NOT NULL CHECK (remaining_period_seconds > 0),
    remaining_feature_value_minor bigint NOT NULL CHECK (remaining_feature_value_minor >= 0),
    available_credit_value_minor bigint NOT NULL CHECK (available_credit_value_minor >= 0),
    offset_minor bigint NOT NULL CHECK (offset_minor >= 0),
    amount_due_minor bigint NOT NULL CHECK (amount_due_minor > 0),
    currency char(3) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PENDING_PAYMENT'
        CHECK (status IN ('PENDING_PAYMENT', 'APPLIED', 'FAILED', 'EXPIRED')),
    idempotency_key varchar(128) NOT NULL,
    quoted_at timestamptz NOT NULL DEFAULT now(),
    quote_expires_at timestamptz NOT NULL,
    applied_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (subscription_id, idempotency_key),
    CHECK (from_plan_version_id <> to_plan_version_id),
    CHECK (old_period_end > old_period_start),
    CHECK (remaining_period_seconds <= total_period_seconds),
    CHECK (offset_minor = remaining_feature_value_minor + available_credit_value_minor),
    CHECK (quote_expires_at > quoted_at)
);
CREATE UNIQUE INDEX ux_subscription_upgrade_pending
    ON consultation.subscription_upgrade (subscription_id)
    WHERE status = 'PENDING_PAYMENT';

CREATE TABLE consultation.subscription_upgrade_credit (
    upgrade_id uuid NOT NULL REFERENCES consultation.subscription_upgrade(id) ON DELETE CASCADE,
    credit_id uuid NOT NULL UNIQUE REFERENCES consultation.consultation_credit(id),
    allocated_value_minor bigint NOT NULL CHECK (allocated_value_minor > 0),
    PRIMARY KEY (upgrade_id, credit_id)
);

CREATE TABLE care.specialist_access_grant (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES care.user_profile(account_id),
    specialist_id uuid NOT NULL REFERENCES consultation.specialist_profile(account_id),
    purpose varchar(255) NOT NULL,
    starts_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz,
    granted_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz,
    revoke_reason varchar(255),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (expires_at IS NULL OR expires_at > starts_at)
);
CREATE INDEX ix_access_grant_authorize
    ON care.specialist_access_grant (user_id, specialist_id, starts_at, expires_at)
    WHERE revoked_at IS NULL;

CREATE TABLE care.specialist_access_scope (
    grant_id uuid NOT NULL REFERENCES care.specialist_access_grant(id) ON DELETE CASCADE,
    scope varchar(32) NOT NULL CHECK (scope IN ('ASSESSMENTS', 'RISK_SUMMARY', 'EMOTION_TRENDS', 'JOURNAL_ENTRIES')),
    PRIMARY KEY (grant_id, scope)
);

CREATE TABLE care.specialist_journal_access (
    grant_id uuid NOT NULL REFERENCES care.specialist_access_grant(id) ON DELETE CASCADE,
    journal_entry_id uuid NOT NULL,
    PRIMARY KEY (grant_id, journal_entry_id)
);

CREATE TABLE consultation.availability_slot (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    specialist_id uuid NOT NULL REFERENCES consultation.specialist_profile(account_id),
    start_at timestamptz NOT NULL,
    end_at timestamptz NOT NULL,
    timezone varchar(64) NOT NULL,
    channel varchar(24) NOT NULL DEFAULT 'IN_APP_CHAT'
        CHECK (channel IN ('IN_APP_CHAT', 'IN_APP_VIDEO')),
    status varchar(16) NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'HELD', 'BOOKED', 'CANCELLED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (id, specialist_id),
    CHECK (end_at > start_at)
);
ALTER TABLE consultation.availability_slot ADD CONSTRAINT ex_specialist_active_slot_overlap
    EXCLUDE USING gist (
        specialist_id WITH =,
        tstzrange(start_at, end_at, '[)') WITH &&
    ) WHERE (status IN ('AVAILABLE', 'HELD', 'BOOKED'));
CREATE INDEX ix_slot_specialist_start ON consultation.availability_slot (specialist_id, start_at)
    WHERE status = 'AVAILABLE';

CREATE TABLE consultation.appointment (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slot_id uuid NOT NULL,
    credit_id uuid NOT NULL,
    user_id uuid NOT NULL REFERENCES care.user_profile(account_id),
    specialist_id uuid NOT NULL REFERENCES consultation.specialist_profile(account_id),
    status varchar(24) NOT NULL DEFAULT 'REQUESTED'
        CHECK (status IN ('REQUESTED', 'CONFIRMED', 'REJECTED', 'CANCELLED', 'RESCHEDULE_REQUESTED', 'COMPLETED', 'USER_NO_SHOW', 'SPECIALIST_NO_SHOW')),
    scheduled_start_at timestamptz NOT NULL,
    scheduled_end_at timestamptz NOT NULL,
    scheduled_timezone varchar(64) NOT NULL,
    channel varchar(24) NOT NULL CHECK (channel IN ('IN_APP_CHAT', 'IN_APP_VIDEO')),
    user_timezone varchar(64) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    cancellation_reason varchar(500),
    requested_at timestamptz NOT NULL DEFAULT now(),
    confirmed_at timestamptz,
    completed_at timestamptz,
    cancelled_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (user_id, idempotency_key),
    FOREIGN KEY (slot_id, specialist_id) REFERENCES consultation.availability_slot(id, specialist_id),
    FOREIGN KEY (credit_id, user_id) REFERENCES consultation.consultation_credit(id, user_id),
    CHECK (scheduled_end_at > scheduled_start_at)
);
CREATE UNIQUE INDEX ux_appointment_active_slot ON consultation.appointment (slot_id)
    WHERE status IN ('REQUESTED', 'CONFIRMED', 'RESCHEDULE_REQUESTED');
CREATE UNIQUE INDEX ux_appointment_active_credit ON consultation.appointment (credit_id)
    WHERE status IN ('REQUESTED', 'CONFIRMED', 'RESCHEDULE_REQUESTED');
CREATE INDEX ix_appointment_user_history ON consultation.appointment (user_id, requested_at DESC);
CREATE INDEX ix_appointment_specialist_queue ON consultation.appointment (specialist_id, status, requested_at DESC);

CREATE TABLE consultation.appointment_status_history (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    appointment_id uuid NOT NULL REFERENCES consultation.appointment(id) ON DELETE CASCADE,
    from_status varchar(24),
    to_status varchar(24) NOT NULL,
    changed_by uuid REFERENCES identity.account(id),
    reason varchar(500),
    changed_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_appointment_history ON consultation.appointment_status_history (appointment_id, changed_at);

CREATE TABLE consultation.consultation_credit_ledger_entry (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    credit_id uuid NOT NULL REFERENCES consultation.consultation_credit(id),
    appointment_id uuid REFERENCES consultation.appointment(id),
    upgrade_id uuid REFERENCES consultation.subscription_upgrade(id),
    entry_type varchar(24) NOT NULL
        CHECK (entry_type IN ('GRANTED', 'RESERVED', 'RELEASED', 'UPGRADE_HELD', 'UPGRADE_RELEASED', 'CONSUMED', 'EXPIRED', 'FORFEITED', 'REVOKED')),
    from_status varchar(16),
    to_status varchar(16) NOT NULL,
    reason_code varchar(64),
    idempotency_key varchar(128) NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (credit_id, idempotency_key),
    CHECK (appointment_id IS NULL OR upgrade_id IS NULL)
);
CREATE INDEX ix_credit_ledger_history
    ON consultation.consultation_credit_ledger_entry (credit_id, occurred_at);

CREATE TABLE consultation.specialist_earning (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    appointment_id uuid NOT NULL UNIQUE REFERENCES consultation.appointment(id),
    credit_id uuid NOT NULL UNIQUE REFERENCES consultation.consultation_credit(id),
    specialist_id uuid NOT NULL REFERENCES consultation.specialist_profile(account_id),
    currency char(3) NOT NULL,
    allocated_value_minor bigint NOT NULL CHECK (allocated_value_minor > 0),
    specialist_share_bps smallint NOT NULL CHECK (specialist_share_bps BETWEEN 0 AND 10000),
    specialist_amount_minor bigint NOT NULL CHECK (specialist_amount_minor >= 0),
    platform_amount_minor bigint NOT NULL CHECK (platform_amount_minor >= 0),
    status varchar(24) NOT NULL DEFAULT 'PENDING_SETTLEMENT'
        CHECK (status IN ('PENDING_SETTLEMENT', 'AVAILABLE', 'IN_PAYOUT', 'PAID', 'REVERSED')),
    earned_at timestamptz NOT NULL,
    settlement_available_at timestamptz NOT NULL,
    reversed_at timestamptz,
    reversal_reason_code varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CHECK (specialist_amount_minor + platform_amount_minor = allocated_value_minor),
    CHECK (settlement_available_at >= earned_at)
);
CREATE INDEX ix_earning_specialist_status
    ON consultation.specialist_earning (specialist_id, status, settlement_available_at);

CREATE TABLE consultation.specialist_payout_destination (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    specialist_id uuid NOT NULL REFERENCES consultation.specialist_profile(account_id),
    payout_provider varchar(32) NOT NULL CHECK (payout_provider IN ('MOMO', 'FAKE')),
    destination_type varchar(24) NOT NULL CHECK (destination_type IN ('MOMO_WALLET', 'BANK_ACCOUNT')),
    destination_ciphertext bytea NOT NULL,
    encryption_key_version varchar(64) NOT NULL,
    destination_fingerprint char(64) NOT NULL,
    display_hint varchar(32) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PENDING_VERIFICATION'
        CHECK (status IN ('PENDING_VERIFICATION', 'VERIFIED', 'DISABLED')),
    verified_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (specialist_id, payout_provider, destination_fingerprint),
    UNIQUE (id, specialist_id, payout_provider),
    CHECK (status <> 'VERIFIED' OR verified_at IS NOT NULL)
);
CREATE INDEX ix_payout_destination_specialist
    ON consultation.specialist_payout_destination (specialist_id, status);

CREATE TABLE consultation.specialist_payout (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    specialist_id uuid NOT NULL REFERENCES consultation.specialist_profile(account_id),
    destination_id uuid NOT NULL,
    currency char(3) NOT NULL,
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    payout_provider varchar(32) NOT NULL CHECK (payout_provider IN ('MOMO', 'FAKE')),
    status varchar(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'UNKNOWN')),
    idempotency_key varchar(128) NOT NULL,
    requested_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    last_failure_code varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (specialist_id, idempotency_key),
    UNIQUE (id, payout_provider),
    FOREIGN KEY (destination_id, specialist_id, payout_provider)
        REFERENCES consultation.specialist_payout_destination(id, specialist_id, payout_provider),
    CHECK (status <> 'SUCCEEDED' OR completed_at IS NOT NULL)
);
CREATE INDEX ix_payout_specialist_history
    ON consultation.specialist_payout (specialist_id, requested_at DESC);

CREATE TABLE consultation.specialist_payout_attempt (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payout_id uuid NOT NULL,
    payout_provider varchar(32) NOT NULL CHECK (payout_provider IN ('MOMO', 'FAKE')),
    attempt_number smallint NOT NULL CHECK (attempt_number > 0),
    provider_idempotency_key varchar(128) NOT NULL,
    provider_payout_reference varchar(160),
    status varchar(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'UNKNOWN')),
    requested_at timestamptz NOT NULL DEFAULT now(),
    provider_confirmed_at timestamptz,
    failed_at timestamptz,
    failure_code varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    FOREIGN KEY (payout_id, payout_provider)
        REFERENCES consultation.specialist_payout(id, payout_provider),
    UNIQUE (payout_id, attempt_number),
    UNIQUE (payout_provider, provider_idempotency_key),
    UNIQUE (payout_provider, provider_payout_reference),
    CHECK (status <> 'SUCCEEDED' OR provider_confirmed_at IS NOT NULL),
    CHECK (status <> 'FAILED' OR failed_at IS NOT NULL)
);
CREATE INDEX ix_payout_attempt_reconciliation
    ON consultation.specialist_payout_attempt (status, requested_at)
    WHERE status IN ('PROCESSING', 'UNKNOWN');

CREATE TABLE consultation.specialist_payout_item (
    payout_id uuid NOT NULL REFERENCES consultation.specialist_payout(id),
    earning_id uuid NOT NULL UNIQUE REFERENCES consultation.specialist_earning(id),
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    PRIMARY KEY (payout_id, earning_id)
);

CREATE TABLE consultation.specialist_payout_status_history (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payout_id uuid NOT NULL REFERENCES consultation.specialist_payout(id) ON DELETE CASCADE,
    from_status varchar(16),
    to_status varchar(16) NOT NULL,
    reason_code varchar(64),
    changed_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_payout_status_history
    ON consultation.specialist_payout_status_history (payout_id, changed_at);

CREATE TABLE consultation.payout_provider_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payout_attempt_id uuid REFERENCES consultation.specialist_payout_attempt(id),
    payout_provider varchar(32) NOT NULL CHECK (payout_provider IN ('MOMO', 'FAKE')),
    provider_event_id varchar(160) NOT NULL,
    event_type varchar(80) NOT NULL,
    payload_sha256 char(64) NOT NULL,
    processing_status varchar(16) NOT NULL DEFAULT 'RECEIVED'
        CHECK (processing_status IN ('RECEIVED', 'PROCESSED', 'REJECTED', 'FAILED')),
    failure_code varchar(64),
    received_at timestamptz NOT NULL DEFAULT now(),
    processed_at timestamptz,
    UNIQUE (payout_provider, provider_event_id)
);

CREATE TABLE consultation.specialist_review (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    appointment_id uuid NOT NULL UNIQUE REFERENCES consultation.appointment(id),
    user_id uuid NOT NULL REFERENCES care.user_profile(account_id),
    specialist_id uuid NOT NULL REFERENCES consultation.specialist_profile(account_id),
    rating smallint NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment text,
    moderation_status varchar(16) NOT NULL DEFAULT 'VISIBLE' CHECK (moderation_status IN ('VISIBLE', 'HIDDEN', 'DELETED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    version bigint NOT NULL DEFAULT 0
);
CREATE INDEX ix_review_specialist_visible ON consultation.specialist_review (specialist_id, created_at DESC)
    WHERE moderation_status = 'VISIBLE';

CREATE TABLE care.follow_up_plan (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES care.user_profile(account_id),
    intervention_plan_id uuid REFERENCES care.intervention_plan(id),
    appointment_id uuid REFERENCES consultation.appointment(id),
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED')),
    next_due_at timestamptz,
    cadence_days smallint CHECK (cadence_days > 0),
    created_by uuid REFERENCES identity.account(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CHECK (intervention_plan_id IS NOT NULL OR appointment_id IS NOT NULL)
);
CREATE INDEX ix_follow_up_due ON care.follow_up_plan (next_due_at) WHERE status = 'ACTIVE';

CREATE TABLE care.follow_up_check_in (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id uuid NOT NULL REFERENCES care.follow_up_plan(id),
    mood_score smallint CHECK (mood_score BETWEEN 1 AND 10),
    note_ciphertext text,
    assessment_submission_id uuid REFERENCES care.assessment_submission(id),
    submitted_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE content.resource (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    category varchar(32) NOT NULL CHECK (category IN ('BREATHING', 'MEDITATION', 'ARTICLE', 'VIDEO', 'JOURNALING', 'COMMUNITY')),
    locale varchar(16) NOT NULL DEFAULT 'vi-VN',
    title varchar(255) NOT NULL,
    summary text NOT NULL,
    content_body text,
    external_url varchar(2048),
    status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    reviewed_by uuid REFERENCES identity.account(id),
    reviewed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CHECK (content_body IS NOT NULL OR external_url IS NOT NULL)
);
CREATE INDEX ix_resource_browse ON content.resource (locale, category, created_at DESC) WHERE status = 'PUBLISHED';

CREATE TABLE content.hotline (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    country_code char(2) NOT NULL,
    region varchar(120),
    name varchar(255) NOT NULL,
    phone_number varchar(40),
    website_url varchar(2048),
    availability_text varchar(255),
    guidance text NOT NULL,
    locale varchar(16) NOT NULL DEFAULT 'vi-VN',
    active boolean NOT NULL DEFAULT true,
    verified_at timestamptz NOT NULL,
    next_review_at timestamptz NOT NULL,
    verified_by uuid NOT NULL REFERENCES identity.account(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (phone_number IS NOT NULL OR website_url IS NOT NULL),
    CHECK (next_review_at > verified_at)
);
CREATE INDEX ix_hotline_active_region ON content.hotline (country_code, region) WHERE active;

CREATE TABLE content.notification_preference (
    user_id uuid NOT NULL REFERENCES care.user_profile(account_id),
    channel varchar(16) NOT NULL CHECK (channel IN ('PUSH', 'EMAIL', 'IN_APP')),
    category varchar(32) NOT NULL CHECK (category IN ('ASSESSMENT', 'APPOINTMENT', 'CHAT', 'FOLLOW_UP', 'SAFETY', 'SYSTEM')),
    enabled boolean NOT NULL DEFAULT true,
    quiet_hours jsonb,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, channel, category)
);

CREATE TABLE content.notification (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_id uuid NOT NULL REFERENCES identity.account(id),
    category varchar(32) NOT NULL,
    title varchar(255) NOT NULL,
    body varchar(1000) NOT NULL,
    action_type varchar(40),
    action_target_id uuid,
    priority varchar(16) NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('LOW', 'NORMAL', 'HIGH')),
    read_at timestamptz,
    expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);
CREATE INDEX ix_notification_recipient_unread ON content.notification (recipient_id, created_at DESC)
    WHERE read_at IS NULL AND deleted_at IS NULL;

CREATE TABLE ai.analysis_job (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES care.user_profile(account_id),
    journal_entry_id uuid NOT NULL,
    journal_revision integer NOT NULL CHECK (journal_revision > 0),
    provider varchar(32) NOT NULL CHECK (provider IN ('OPENAI', 'GEMINI', 'PHOBERT')),
    prompt_version varchar(32) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'RETRYABLE', 'FAILED', 'CANCELLED')),
    attempt_count smallint NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at timestamptz,
    error_code varchar(64),
    result_document_id varchar(64),
    requested_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (journal_entry_id, journal_revision, provider, prompt_version)
);
CREATE INDEX ix_analysis_job_claim ON ai.analysis_job (status, next_attempt_at, requested_at)
    WHERE status IN ('PENDING', 'RETRYABLE');

CREATE TABLE ai.evaluation_dataset (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name varchar(255) NOT NULL,
    version varchar(40) NOT NULL,
    source_url varchar(2048),
    license_name varchar(255) NOT NULL,
    object_key varchar(512) NOT NULL UNIQUE,
    checksum_sha256 char(64) NOT NULL,
    sample_count integer NOT NULL CHECK (sample_count >= 0),
    label_schema jsonb NOT NULL,
    preprocessing_version varchar(40) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'VALIDATING' CHECK (status IN ('VALIDATING', 'READY', 'INVALID', 'ARCHIVED')),
    imported_by uuid NOT NULL REFERENCES identity.account(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (name, version)
);

CREATE TABLE ai.benchmark_run (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    dataset_id uuid NOT NULL REFERENCES ai.evaluation_dataset(id),
    configuration jsonb NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED')),
    metrics jsonb,
    started_by uuid NOT NULL REFERENCES identity.account(id),
    started_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_benchmark_dataset ON ai.benchmark_run (dataset_id, created_at DESC);

CREATE TABLE platform.outbox_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type varchar(80) NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type varchar(120) NOT NULL,
    schema_version smallint NOT NULL DEFAULT 1 CHECK (schema_version > 0),
    correlation_id uuid,
    payload jsonb NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    attempt_count smallint NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at timestamptz,
    last_error_code varchar(64)
);
CREATE INDEX ix_outbox_unpublished ON platform.outbox_event (COALESCE(next_attempt_at, occurred_at), occurred_at)
    WHERE published_at IS NULL;

CREATE TABLE platform.audit_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    occurred_at timestamptz NOT NULL DEFAULT now(),
    actor_id uuid,
    actor_type varchar(24) NOT NULL CHECK (actor_type IN ('ACCOUNT', 'SERVICE', 'ANONYMOUS')),
    action varchar(120) NOT NULL,
    resource_type varchar(80) NOT NULL,
    resource_id varchar(128),
    subject_user_id uuid,
    consent_grant_id uuid,
    purpose varchar(255),
    outcome varchar(16) NOT NULL CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILED')),
    correlation_id uuid,
    ip_hash varchar(255),
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX ix_audit_occurred ON platform.audit_event (occurred_at DESC);
CREATE INDEX ix_audit_actor ON platform.audit_event (actor_id, occurred_at DESC);
CREATE INDEX ix_audit_subject ON platform.audit_event (subject_user_id, occurred_at DESC);

CREATE TABLE platform.moderation_case (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_id uuid NOT NULL REFERENCES identity.account(id),
    target_type varchar(24) NOT NULL CHECK (target_type IN ('CHAT_MESSAGE', 'SPECIALIST_REVIEW', 'ACCOUNT')),
    target_id varchar(128) NOT NULL,
    reason_code varchar(64) NOT NULL,
    description varchar(1000),
    status varchar(24) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'IN_REVIEW', 'ACTIONED', 'DISMISSED', 'APPEALED')),
    assigned_admin_id uuid REFERENCES identity.account(id),
    resolution_code varchar(64),
    resolution_note varchar(1000),
    created_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_moderation_queue ON platform.moderation_case (status, created_at);

CREATE TABLE platform.retention_policy (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    data_category varchar(64) NOT NULL,
    policy_version varchar(32) NOT NULL,
    retention_days integer NOT NULL CHECK (retention_days > 0),
    legal_basis varchar(255) NOT NULL,
    active_from timestamptz NOT NULL,
    active_until timestamptz,
    approved_by uuid NOT NULL REFERENCES identity.account(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (data_category, policy_version),
    CHECK (active_until IS NULL OR active_until > active_from)
);

CREATE TABLE platform.deletion_request (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES identity.account(id),
    status varchar(24) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'PARTIAL_FAILURE', 'CANCELLED')),
    requested_at timestamptz NOT NULL DEFAULT now(),
    grace_expires_at timestamptz,
    started_at timestamptz,
    completed_at timestamptz,
    requested_by uuid NOT NULL REFERENCES identity.account(id),
    correlation_id uuid NOT NULL UNIQUE,
    last_error_code varchar(64)
);
CREATE INDEX ix_deletion_request_work ON platform.deletion_request (status, requested_at)
    WHERE status IN ('PENDING', 'IN_PROGRESS', 'PARTIAL_FAILURE');

CREATE TABLE platform.deletion_task (
    request_id uuid NOT NULL REFERENCES platform.deletion_request(id) ON DELETE CASCADE,
    data_owner varchar(64) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'NOT_APPLICABLE')),
    attempt_count smallint NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    completed_at timestamptz,
    last_error_code varchar(64),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (request_id, data_owner)
);

INSERT INTO identity.role (code, description) VALUES
    ('USER', 'End user'),
    ('SPECIALIST', 'Approved or pending specialist account'),
    ('ADMIN', 'Dedicated platform administrator')
ON CONFLICT (code) DO NOTHING;

INSERT INTO consultation.subscription_plan (code, display_name, tier_rank) VALUES
    ('FREE', 'Free', 0),
    ('PREMIUM_CARE', 'Premium Care', 1),
    ('PREMIUM_PLUS', 'Premium Plus', 2)
ON CONFLICT (code) DO NOTHING;

INSERT INTO consultation.subscription_plan_version (
    id,
    plan_code,
    version,
    currency,
    price_minor,
    billing_period_months,
    consultation_credits_per_period,
    non_consultation_value_minor,
    credit_value_minor,
    specialist_share_bps,
    cancellation_cutoff_hours,
    effective_from
) VALUES
    ('00000000-0000-0000-0000-000000000101', 'FREE', 1, 'USD', 0, 1, 0, 0, 0, 0, NULL, '2026-08-21T00:00:00Z'),
    ('00000000-0000-0000-0000-000000000102', 'PREMIUM_CARE', 1, 'USD', 999, 1, 1, 499, 500, 7000, NULL, '2026-08-21T00:00:00Z'),
    ('00000000-0000-0000-0000-000000000103', 'PREMIUM_PLUS', 1, 'USD', 1999, 1, 3, 499, 500, 7000, NULL, '2026-08-21T00:00:00Z')
ON CONFLICT (plan_code, version) DO NOTHING;

INSERT INTO consultation.subscription_plan_entitlement (plan_version_id, entitlement_code) VALUES
    ('00000000-0000-0000-0000-000000000101', 'ASSESSMENT'),
    ('00000000-0000-0000-0000-000000000101', 'EMOTION_JOURNAL'),
    ('00000000-0000-0000-0000-000000000101', 'AI_EMOTION_ANALYSIS'),
    ('00000000-0000-0000-0000-000000000101', 'BASIC_EMOTIONAL_DASHBOARD'),
    ('00000000-0000-0000-0000-000000000101', 'SELF_HELP_RESOURCES'),
    ('00000000-0000-0000-0000-000000000101', 'SPECIALIST_DISCOVERY'),
    ('00000000-0000-0000-0000-000000000101', 'AI_SPECIALIST_RECOMMENDATION'),
    ('00000000-0000-0000-0000-000000000102', 'ASSESSMENT'),
    ('00000000-0000-0000-0000-000000000102', 'EMOTION_JOURNAL'),
    ('00000000-0000-0000-0000-000000000102', 'AI_EMOTION_ANALYSIS'),
    ('00000000-0000-0000-0000-000000000102', 'BASIC_EMOTIONAL_DASHBOARD'),
    ('00000000-0000-0000-0000-000000000102', 'SELF_HELP_RESOURCES'),
    ('00000000-0000-0000-0000-000000000102', 'SPECIALIST_DISCOVERY'),
    ('00000000-0000-0000-0000-000000000102', 'AI_SPECIALIST_RECOMMENDATION'),
    ('00000000-0000-0000-0000-000000000102', 'SPECIALIST_APPOINTMENT'),
    ('00000000-0000-0000-0000-000000000102', 'SPECIALIST_CHAT'),
    ('00000000-0000-0000-0000-000000000102', 'PERSONALIZED_INTERVENTION_PLAN'),
    ('00000000-0000-0000-0000-000000000102', 'ADVANCED_EMOTIONAL_ANALYTICS'),
    ('00000000-0000-0000-0000-000000000102', 'FOLLOW_UP_MONITORING'),
    ('00000000-0000-0000-0000-000000000102', 'PRIORITY_SPECIALIST_RECOMMENDATION'),
    ('00000000-0000-0000-0000-000000000103', 'ASSESSMENT'),
    ('00000000-0000-0000-0000-000000000103', 'EMOTION_JOURNAL'),
    ('00000000-0000-0000-0000-000000000103', 'AI_EMOTION_ANALYSIS'),
    ('00000000-0000-0000-0000-000000000103', 'BASIC_EMOTIONAL_DASHBOARD'),
    ('00000000-0000-0000-0000-000000000103', 'SELF_HELP_RESOURCES'),
    ('00000000-0000-0000-0000-000000000103', 'SPECIALIST_DISCOVERY'),
    ('00000000-0000-0000-0000-000000000103', 'AI_SPECIALIST_RECOMMENDATION'),
    ('00000000-0000-0000-0000-000000000103', 'SPECIALIST_APPOINTMENT'),
    ('00000000-0000-0000-0000-000000000103', 'SPECIALIST_CHAT'),
    ('00000000-0000-0000-0000-000000000103', 'PERSONALIZED_INTERVENTION_PLAN'),
    ('00000000-0000-0000-0000-000000000103', 'ADVANCED_EMOTIONAL_ANALYTICS'),
    ('00000000-0000-0000-0000-000000000103', 'FOLLOW_UP_MONITORING'),
    ('00000000-0000-0000-0000-000000000103', 'PRIORITY_SPECIALIST_RECOMMENDATION'),
    ('00000000-0000-0000-0000-000000000103', 'PRIORITY_APPOINTMENT_BOOKING'),
    ('00000000-0000-0000-0000-000000000103', 'PRIORITY_SPECIALIST_MATCHING'),
    ('00000000-0000-0000-0000-000000000103', 'ENHANCED_FOLLOW_UP_MONITORING')
ON CONFLICT (plan_version_id, entitlement_code) DO NOTHING;

COMMIT;
