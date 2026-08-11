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

CREATE TABLE identity.account (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email citext NOT NULL UNIQUE,
    password_hash varchar(255) NOT NULL,
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

CREATE TABLE identity.role (
    code varchar(32) PRIMARY KEY CHECK (code IN ('USER', 'SPECIALIST', 'ADMIN', 'RESEARCH_ADMIN')),
    description varchar(255) NOT NULL
);

CREATE TABLE identity.account_role (
    account_id uuid NOT NULL REFERENCES identity.account(id) ON DELETE CASCADE,
    role_code varchar(32) NOT NULL REFERENCES identity.role(code),
    granted_by uuid REFERENCES identity.account(id),
    granted_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, role_code)
);

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
    avatar_object_key varchar(512),
    reminder_enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0
);

CREATE TABLE care.consent_decision (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES care.user_profile(account_id),
    consent_type varchar(40) NOT NULL
        CHECK (consent_type IN ('PRIVACY_POLICY', 'AI_PROCESSING', 'RESEARCH_DATA', 'MARKETING_NOTIFICATION')),
    policy_version varchar(40) NOT NULL,
    granted boolean NOT NULL,
    decided_at timestamptz NOT NULL DEFAULT now(),
    evidence jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_consent_decision_latest
    ON care.consent_decision (user_id, consent_type, decided_at DESC);

CREATE TABLE care.questionnaire_definition (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    instrument varchar(16) NOT NULL CHECK (instrument IN ('PHQ9', 'GAD7')),
    version varchar(32) NOT NULL,
    locale varchar(16) NOT NULL DEFAULT 'vi-VN',
    title varchar(255) NOT NULL,
    scoring_version varchar(32) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (instrument, version, locale)
);

CREATE TABLE care.questionnaire_question (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    definition_id uuid NOT NULL REFERENCES care.questionnaire_definition(id) ON DELETE CASCADE,
    item_number smallint NOT NULL CHECK (item_number > 0),
    prompt text NOT NULL,
    safety_flag boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (definition_id, item_number)
);

CREATE TABLE care.assessment_submission (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid REFERENCES care.user_profile(account_id),
    anonymous_session_id uuid,
    definition_id uuid NOT NULL REFERENCES care.questionnaire_definition(id),
    total_score smallint NOT NULL CHECK (total_score BETWEEN 0 AND 27),
    screening_level varchar(24) NOT NULL
        CHECK (screening_level IN ('MINIMAL', 'MILD', 'MODERATE', 'MODERATELY_SEVERE', 'SEVERE')),
    scoring_version varchar(32) NOT NULL,
    safety_flag boolean NOT NULL DEFAULT false,
    idempotency_key varchar(128),
    submitted_at timestamptz NOT NULL DEFAULT now(),
    voided_at timestamptz,
    void_reason varchar(255),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((user_id IS NOT NULL) <> (anonymous_session_id IS NOT NULL))
);
CREATE UNIQUE INDEX ux_assessment_user_idempotency
    ON care.assessment_submission (user_id, idempotency_key)
    WHERE user_id IS NOT NULL AND idempotency_key IS NOT NULL;
CREATE UNIQUE INDEX ux_assessment_anonymous_idempotency
    ON care.assessment_submission (anonymous_session_id, idempotency_key)
    WHERE anonymous_session_id IS NOT NULL AND idempotency_key IS NOT NULL;
CREATE INDEX ix_assessment_user_history
    ON care.assessment_submission (user_id, submitted_at DESC) WHERE voided_at IS NULL;
CREATE INDEX ix_assessment_anonymous_expiry
    ON care.assessment_submission (submitted_at) WHERE anonymous_session_id IS NOT NULL;

CREATE TABLE care.assessment_answer (
    submission_id uuid NOT NULL REFERENCES care.assessment_submission(id) ON DELETE CASCADE,
    question_id uuid NOT NULL REFERENCES care.questionnaire_question(id),
    answer_value smallint NOT NULL CHECK (answer_value BETWEEN 0 AND 3),
    PRIMARY KEY (submission_id, question_id)
);

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
    consultation_methods jsonb NOT NULL DEFAULT '[]'::jsonb,
    timezone varchar(64) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
    verification_status varchar(24) NOT NULL DEFAULT 'NOT_SUBMITTED'
        CHECK (verification_status IN ('NOT_SUBMITTED', 'PENDING', 'APPROVED', 'REJECTED', 'SUSPENDED')),
    approved_at timestamptz,
    approved_by uuid REFERENCES identity.account(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0
);
CREATE INDEX ix_specialist_approved ON consultation.specialist_profile (display_name)
    WHERE verification_status = 'APPROVED';

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

CREATE TABLE consultation.verification_document (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    specialist_id uuid NOT NULL REFERENCES consultation.specialist_profile(account_id),
    document_type varchar(40) NOT NULL,
    object_key varchar(512) NOT NULL UNIQUE,
    content_type varchar(100) NOT NULL,
    checksum_sha256 char(64) NOT NULL,
    review_status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (review_status IN ('PENDING', 'ACCEPTED', 'REJECTED')),
    reviewed_by uuid REFERENCES identity.account(id),
    reviewed_at timestamptz,
    rejection_reason varchar(500),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_verification_document_pending
    ON consultation.verification_document (created_at) WHERE review_status = 'PENDING';

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
    method varchar(24) NOT NULL CHECK (method IN ('CHAT', 'VIDEO_EXTERNAL', 'PHONE', 'IN_PERSON')),
    status varchar(16) NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'HELD', 'BOOKED', 'CANCELLED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
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
    slot_id uuid NOT NULL REFERENCES consultation.availability_slot(id),
    user_id uuid NOT NULL REFERENCES care.user_profile(account_id),
    specialist_id uuid NOT NULL REFERENCES consultation.specialist_profile(account_id),
    status varchar(24) NOT NULL DEFAULT 'REQUESTED'
        CHECK (status IN ('REQUESTED', 'CONFIRMED', 'REJECTED', 'CANCELLED', 'RESCHEDULE_REQUESTED', 'COMPLETED', 'NO_SHOW')),
    consultation_method varchar(24) NOT NULL CHECK (consultation_method IN ('CHAT', 'VIDEO_EXTERNAL', 'PHONE', 'IN_PERSON')),
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
    UNIQUE (user_id, idempotency_key)
);
CREATE UNIQUE INDEX ux_appointment_active_slot ON consultation.appointment (slot_id)
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
    ('ADMIN', 'Platform administrator'),
    ('RESEARCH_ADMIN', 'AI dataset and benchmark administrator')
ON CONFLICT (code) DO NOTHING;

COMMIT;
