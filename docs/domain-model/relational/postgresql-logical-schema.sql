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
 * The identity, care, consultation, and content qualifiers below are visual
 * owner namespaces only. Runtime PostgreSQL uses a separate owner database and
 * its default public schema. Cross-owner identifiers are logical/external
 * references and are deliberately not physical foreign keys.
 *
 * Reconciled from owner migrations on 2026-09-24. It includes the Care
 * SupportPlan and immutable Reassessment Summary migrations. Technical indexes and migration bookkeeping are
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
    action varchar(96) NOT NULL,
    outcome varchar(32) NOT NULL,
    reason_code varchar(64),
    correlation_id uuid NOT NULL,
    subject_reference_hash char(64),
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL
);

/* ========================================================================== */
/* ACTIVE — care-service / mentalbridge_care                                  */
/* Evidence: Care migration files 001-012, ending with changeset care-013.    */
/* ========================================================================== */

CREATE TABLE care.user_profile (
    account_id uuid PRIMARY KEY, -- external -> identity.account.id
    display_name varchar(120) NOT NULL,
    date_of_birth date,
    gender varchar(32),
    locale varchar(16) NOT NULL,
    timezone varchar(64) NOT NULL,
    reminder_enabled boolean NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL
);

CREATE TABLE care.consent_decision (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES care.user_profile(account_id),
    consent_type varchar(40) NOT NULL,
    policy_version varchar(64) NOT NULL,
    granted boolean NOT NULL,
    evidence jsonb NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_hash varchar(64) NOT NULL,
    decided_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (user_id, consent_type, idempotency_key)
);

CREATE TABLE care.anonymous_assessment_session (
    id uuid PRIMARY KEY,
    token_hash varchar(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    closed_at timestamptz,
    created_at timestamptz NOT NULL
);

CREATE TABLE care.questionnaire_definition (
    id uuid PRIMARY KEY,
    instrument varchar(16) NOT NULL,
    version varchar(32) NOT NULL,
    locale varchar(16) NOT NULL,
    title varchar(255) NOT NULL,
    reference_period_days smallint NOT NULL,
    expected_question_count smallint NOT NULL,
    scoring_version varchar(32) NOT NULL,
    response_options jsonb NOT NULL,
    source_reference varchar(512) NOT NULL,
    status varchar(16) NOT NULL,
    published_at timestamptz,
    created_at timestamptz NOT NULL,
    UNIQUE (instrument, version, locale)
);

CREATE TABLE care.questionnaire_question (
    id uuid PRIMARY KEY,
    definition_id uuid NOT NULL REFERENCES care.questionnaire_definition(id),
    item_number smallint NOT NULL,
    prompt text NOT NULL,
    safety_item boolean NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (definition_id, item_number),
    UNIQUE (definition_id, id)
);

CREATE TABLE care.questionnaire_score_band (
    definition_id uuid NOT NULL REFERENCES care.questionnaire_definition(id),
    code varchar(24) NOT NULL,
    minimum_score smallint NOT NULL,
    maximum_score smallint NOT NULL,
    ordinal smallint NOT NULL,
    PRIMARY KEY (definition_id, code)
);

CREATE TABLE care.assessment_submission (
    id uuid PRIMARY KEY,
    user_id uuid REFERENCES care.user_profile(account_id),
    anonymous_session_id uuid REFERENCES care.anonymous_assessment_session(id),
    definition_id uuid NOT NULL REFERENCES care.questionnaire_definition(id),
    privacy_policy_version varchar(64) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_hash varchar(64) NOT NULL,
    submitted_at timestamptz NOT NULL,
    retention_expires_at timestamptz,
    voided_at timestamptz,
    void_reason_code varchar(64),
    created_at timestamptz NOT NULL,
    UNIQUE (id, definition_id),
    UNIQUE (id, user_id)
);

CREATE TABLE care.assessment_answer (
    submission_id uuid NOT NULL,
    definition_id uuid NOT NULL,
    question_id uuid NOT NULL,
    answer_value smallint NOT NULL,
    PRIMARY KEY (submission_id, question_id),
    FOREIGN KEY (submission_id, definition_id)
        REFERENCES care.assessment_submission(id, definition_id),
    FOREIGN KEY (definition_id, question_id)
        REFERENCES care.questionnaire_question(definition_id, id)
);

CREATE TABLE care.assessment_result (
    submission_id uuid PRIMARY KEY REFERENCES care.assessment_submission(id),
    total_score smallint NOT NULL,
    screening_level varchar(24) NOT NULL,
    scoring_version varchar(32) NOT NULL,
    safety_item_positive boolean,
    safety_status varchar(32),
    safety_policy_version varchar(64),
    disclaimer_code varchar(64) NOT NULL,
    calculated_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE TABLE care.outbox_event (
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

CREATE TABLE care.support_policy_definition (
    version varchar(64) PRIMARY KEY,
    locale varchar(16) NOT NULL,
    status varchar(16) NOT NULL,
    reviewed_by varchar(160) NOT NULL,
    approved_at timestamptz NOT NULL,
    source_reference varchar(512) NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE TABLE care.support_policy_eligible_definition (
    policy_version varchar(64) NOT NULL REFERENCES care.support_policy_definition(version),
    definition_id uuid NOT NULL REFERENCES care.questionnaire_definition(id),
    instrument varchar(16) NOT NULL,
    questionnaire_version varchar(32) NOT NULL,
    scoring_version varchar(32) NOT NULL,
    PRIMARY KEY (policy_version, definition_id)
);

CREATE TABLE care.screening_band_meaning (
    policy_version varchar(64) NOT NULL REFERENCES care.support_policy_definition(version),
    instrument varchar(16) NOT NULL,
    screening_level varchar(24) NOT NULL,
    meaning_code varchar(64) NOT NULL,
    content_version varchar(64) NOT NULL,
    reference_period_days smallint NOT NULL,
    meaning_text varchar(1000) NOT NULL,
    limitation_text varchar(1000) NOT NULL,
    PRIMARY KEY (policy_version, instrument, screening_level)
);

CREATE TABLE care.support_tier_guidance (
    policy_version varchar(64) NOT NULL REFERENCES care.support_policy_definition(version),
    support_tier varchar(48) NOT NULL,
    next_step_code varchar(64) NOT NULL,
    content_version varchar(64) NOT NULL,
    next_step_text varchar(1000) NOT NULL,
    boundary_text varchar(1000) NOT NULL,
    safety_guidance_text varchar(1000),
    PRIMARY KEY (policy_version, support_tier)
);

CREATE TABLE care.support_evaluation (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES care.user_profile(account_id),
    phq9_assessment_id uuid NOT NULL REFERENCES care.assessment_submission(id),
    gad7_assessment_id uuid NOT NULL REFERENCES care.assessment_submission(id),
    policy_version varchar(64) NOT NULL REFERENCES care.support_policy_definition(version),
    support_tier varchar(48) NOT NULL,
    primary_reason_code varchar(64) NOT NULL,
    secondary_reason_code varchar(64),
    evaluated_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (id, user_id)
);

CREATE TABLE care.support_evaluation_request (
    user_id uuid NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_hash varchar(64) NOT NULL,
    support_evaluation_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, idempotency_key),
    FOREIGN KEY (support_evaluation_id, user_id)
        REFERENCES care.support_evaluation(id, user_id)
);

CREATE TABLE care.support_evaluation_v2_policy_definition (
    version varchar(64) PRIMARY KEY,
    locale varchar(16) NOT NULL,
    status varchar(16) NOT NULL,
    reviewed_by varchar(160) NOT NULL,
    approved_at timestamptz NOT NULL,
    source_reference varchar(512) NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE TABLE care.support_evaluation_v2_eligible_definition (
    policy_version varchar(64) NOT NULL
        REFERENCES care.support_evaluation_v2_policy_definition(version),
    definition_id uuid NOT NULL REFERENCES care.questionnaire_definition(id),
    instrument varchar(16) NOT NULL,
    questionnaire_version varchar(32) NOT NULL,
    scoring_version varchar(32) NOT NULL,
    domain varchar(48) NOT NULL,
    PRIMARY KEY (policy_version, definition_id)
);

CREATE TABLE care.support_evaluation_v2 (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES care.user_profile(account_id),
    phq9_assessment_id uuid NOT NULL,
    gad7_assessment_id uuid NOT NULL,
    policy_version varchar(64) NOT NULL
        REFERENCES care.support_evaluation_v2_policy_definition(version),
    evaluated_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (id, user_id),
    UNIQUE (id, phq9_assessment_id),
    FOREIGN KEY (phq9_assessment_id, user_id)
        REFERENCES care.assessment_submission(id, user_id),
    FOREIGN KEY (gad7_assessment_id, user_id)
        REFERENCES care.assessment_submission(id, user_id)
);

CREATE TABLE care.support_evaluation_v2_domain (
    id uuid PRIMARY KEY,
    support_evaluation_id uuid NOT NULL REFERENCES care.support_evaluation_v2(id),
    ordinal smallint NOT NULL,
    assessment_id uuid NOT NULL,
    definition_id uuid NOT NULL,
    instrument varchar(16) NOT NULL,
    domain varchar(48) NOT NULL,
    questionnaire_version varchar(32) NOT NULL,
    scoring_version varchar(32) NOT NULL,
    screening_level varchar(24) NOT NULL,
    support_pathway varchar(48) NOT NULL,
    reason_code varchar(64) NOT NULL,
    FOREIGN KEY (assessment_id, definition_id)
        REFERENCES care.assessment_submission(id, definition_id)
);

CREATE TABLE care.support_evaluation_v2_safety (
    support_evaluation_id uuid PRIMARY KEY,
    source_assessment_id uuid NOT NULL,
    instrument varchar(16) NOT NULL,
    trigger_code varchar(32) NOT NULL,
    safety_status varchar(32) NOT NULL,
    safety_policy_version varchar(64) NOT NULL,
    reason_code varchar(64) NOT NULL,
    FOREIGN KEY (support_evaluation_id, source_assessment_id)
        REFERENCES care.support_evaluation_v2(id, phq9_assessment_id)
);

CREATE TABLE care.support_evaluation_v2_request (
    user_id uuid NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_hash varchar(64) NOT NULL,
    support_evaluation_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, idempotency_key),
    FOREIGN KEY (support_evaluation_id, user_id)
        REFERENCES care.support_evaluation_v2(id, user_id)
);

CREATE TABLE care.support_guide (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES care.user_profile(account_id),
    support_evaluation_id uuid NOT NULL,
    guide_policy_version varchar(64) NOT NULL,
    generated_at timestamptz NOT NULL,
    explanation_code varchar(64) NOT NULL,
    explanation_text varchar(2048) NOT NULL,
    safety_status varchar(32) NOT NULL,
    safety_reason_code varchar(64) NOT NULL,
    safety_policy_version varchar(64) NOT NULL,
    safety_guidance_code varchar(64) NOT NULL,
    safety_guidance varchar(2048) NOT NULL,
    resource_status varchar(16) NOT NULL,
    resource_policy_version varchar(64) NOT NULL,
    resources_resolved_at timestamptz NOT NULL,
    phrasing_status varchar(32) NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (id, user_id),
    FOREIGN KEY (support_evaluation_id, user_id)
        REFERENCES care.support_evaluation_v2(id, user_id)
);

CREATE TABLE care.support_guide_resource (
    id uuid PRIMARY KEY,
    support_guide_id uuid NOT NULL REFERENCES care.support_guide(id),
    ordinal smallint NOT NULL,
    resource_id uuid NOT NULL,       -- external -> content.resource.id
    content_version bigint NOT NULL,
    publication_id uuid NOT NULL,    -- external -> content.resource_eligibility_publication.id
    domain varchar(48) NOT NULL,
    eligibility_role varchar(16) NOT NULL,
    category varchar(32) NOT NULL,
    title varchar(255) NOT NULL,
    summary text NOT NULL,
    external_url varchar(2048)
);

CREATE TABLE care.support_guide_request (
    user_id uuid NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_hash varchar(64) NOT NULL,
    support_guide_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, idempotency_key),
    FOREIGN KEY (support_guide_id, user_id)
        REFERENCES care.support_guide(id, user_id)
);

CREATE TABLE care.support_plan (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES care.user_profile(account_id),
    support_evaluation_id uuid NOT NULL,
    status varchar(16) NOT NULL,
    version bigint NOT NULL,
    evaluation_policy_version varchar(64) NOT NULL,
    evaluated_at timestamptz NOT NULL,
    selection_policy_version varchar(64) NOT NULL,
    resource_policy_version varchar(64) NOT NULL,
    resources_resolved_at timestamptz NOT NULL,
    entitlement_package varchar(16) NOT NULL,
    entitlement_source varchar(16) NOT NULL,
    entitlement_policy_version varchar(64) NOT NULL,
    entitlement_version bigint NOT NULL,
    entitlement_decided_at timestamptz NOT NULL,
    rationale_code varchar(64) NOT NULL,
    rationale_text varchar(2048) NOT NULL,
    safety_status varchar(32) NOT NULL,
    safety_reason_code varchar(64) NOT NULL,
    safety_policy_version varchar(64) NOT NULL,
    safety_guidance_code varchar(64) NOT NULL,
    safety_guidance varchar(2048) NOT NULL,
    selected_resource_count smallint NOT NULL,
    activated_at timestamptz,
    completed_at timestamptz,
    completion_reason varchar(32),
    superseded_at timestamptz,
    discarded_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (id, user_id),
    FOREIGN KEY (support_evaluation_id, user_id)
        REFERENCES care.support_evaluation_v2(id, user_id)
);

CREATE TABLE care.support_plan_template_family (
    id uuid PRIMARY KEY,
    support_plan_id uuid NOT NULL REFERENCES care.support_plan(id),
    ordinal smallint NOT NULL,
    family varchar(64) NOT NULL,
    template_version integer NOT NULL,
    target_domain varchar(48) NOT NULL
);

CREATE TABLE care.support_plan_slot (
    id uuid PRIMARY KEY,
    support_plan_id uuid NOT NULL REFERENCES care.support_plan(id),
    ordinal smallint NOT NULL,
    slot_key varchar(64) NOT NULL,
    slot_kind varchar(16) NOT NULL,
    target_domain varchar(48) NOT NULL,
    purpose_code varchar(64) NOT NULL,
    selected_resource_id uuid,    -- external -> content.resource.id; nullable only for removed OPTIONAL slot
    selected_content_version bigint,
    selected_publication_id uuid, -- external -> content.resource_eligibility_publication.id
    selected_role varchar(16),
    selected_category varchar(32),
    selected_title varchar(255),
    selected_summary text,
    selected_external_url varchar(2048)
);

CREATE TABLE care.support_plan_slot_alternative (
    id uuid PRIMARY KEY,
    support_plan_slot_id uuid NOT NULL REFERENCES care.support_plan_slot(id),
    ordinal smallint NOT NULL,
    resource_id uuid NOT NULL,    -- external -> content.resource.id
    content_version bigint NOT NULL,
    publication_id uuid NOT NULL, -- external -> content.resource_eligibility_publication.id
    eligibility_role varchar(16) NOT NULL,
    category varchar(32) NOT NULL,
    title varchar(255) NOT NULL,
    summary text NOT NULL,
    external_url varchar(2048)
);

CREATE TABLE care.support_plan_request (
    user_id uuid NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_hash varchar(64) NOT NULL,
    support_plan_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, idempotency_key),
    FOREIGN KEY (support_plan_id, user_id)
        REFERENCES care.support_plan(id, user_id)
);

CREATE TABLE care.support_plan_command (
    user_id uuid NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    command_type varchar(16) NOT NULL CHECK (command_type = 'ACTIVATE'),
    request_hash varchar(64) NOT NULL,
    support_plan_id uuid NOT NULL,
    expected_version bigint NOT NULL,
    resulting_version bigint NOT NULL,
    resulting_status varchar(16) NOT NULL,
    resulting_updated_at timestamptz NOT NULL,
    evaluation_policy_version varchar(64) NOT NULL,
    entitlement_package varchar(16) NOT NULL,
    entitlement_source varchar(16) NOT NULL,
    entitlement_policy_version varchar(64) NOT NULL,
    entitlement_version bigint NOT NULL,
    entitlement_decided_at timestamptz NOT NULL,
    resource_policy_version varchar(64) NOT NULL,
    resources_resolved_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, idempotency_key),
    FOREIGN KEY (support_plan_id, user_id)
        REFERENCES care.support_plan(id, user_id)
);

CREATE TABLE care.support_plan_command_selection (
    user_id uuid NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    ordinal smallint NOT NULL,
    slot_key varchar(64) NOT NULL,
    resource_id uuid NOT NULL, -- external -> content.resource.id
    content_version bigint NOT NULL,
    PRIMARY KEY (user_id, idempotency_key, ordinal),
    FOREIGN KEY (user_id, idempotency_key)
        REFERENCES care.support_plan_command(user_id, idempotency_key)
);

CREATE TABLE care.support_plan_activity_schedule (
    id uuid PRIMARY KEY,
    support_plan_id uuid NOT NULL REFERENCES care.support_plan(id),
    user_id uuid NOT NULL,
    ordinal smallint NOT NULL,
    schedule_version integer NOT NULL,
    source_plan_version bigint NOT NULL,
    source_slot_key varchar(64) NOT NULL,
    source_resource_id uuid NOT NULL, -- external -> content.resource.id
    source_content_version bigint NOT NULL,
    source_title varchar(255) NOT NULL,
    recurrence_type varchar(16) NOT NULL,
    recurrence_day_of_week smallint,
    local_time time NOT NULL,
    timezone varchar(64) NOT NULL,
    effective_from date NOT NULL,
    effective_until date,
    status varchar(16) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (id, support_plan_id, user_id),
    FOREIGN KEY (support_plan_id, user_id)
        REFERENCES care.support_plan(id, user_id)
);

CREATE TABLE care.support_plan_activity_occurrence (
    id uuid PRIMARY KEY,
    activity_schedule_id uuid NOT NULL,
    support_plan_id uuid NOT NULL,
    user_id uuid NOT NULL,
    schedule_version integer NOT NULL,
    local_date date NOT NULL,
    local_time time NOT NULL,
    timezone varchar(64) NOT NULL,
    scheduled_at timestamptz NOT NULL,
    state varchar(16) NOT NULL,
    state_reason varchar(32),
    source_plan_version bigint NOT NULL,
    source_slot_key varchar(64) NOT NULL,
    source_resource_id uuid NOT NULL, -- external -> content.resource.id
    source_content_version bigint NOT NULL,
    source_title varchar(255) NOT NULL,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    completed_at timestamptz,
    skipped_at timestamptz,
    cancelled_at timestamptz,
    hidden boolean NOT NULL DEFAULT false,
    helpfulness varchar(24),
    barrier_code varchar(32),
    reflection varchar(500),
    summary_reuse_approved boolean NOT NULL DEFAULT false,
    engagement_updated_at timestamptz,
    CHECK (helpfulness IS NULL OR helpfulness IN
        ('NOT_HELPFUL', 'A_LITTLE_HELPFUL', 'HELPFUL', 'VERY_HELPFUL')),
    CHECK (barrier_code IS NULL OR barrier_code IN
        ('LOW_ENERGY', 'NOT_ENOUGH_TIME', 'DIFFICULT_TO_START', 'NOT_A_GOOD_FIT', 'OTHER')),
    CHECK (reflection IS NULL OR (char_length(btrim(reflection)) BETWEEN 1 AND 500)),
    CHECK (
        (state IN ('SCHEDULED', 'CANCELLED')
            AND helpfulness IS NULL AND barrier_code IS NULL AND reflection IS NULL
            AND summary_reuse_approved = false)
        OR (state = 'COMPLETED' AND barrier_code IS NULL)
        OR (state = 'SKIPPED' AND helpfulness IS NULL)
    ),
    UNIQUE (activity_schedule_id, schedule_version, local_date),
    FOREIGN KEY (activity_schedule_id, support_plan_id, user_id)
        REFERENCES care.support_plan_activity_schedule(id, support_plan_id, user_id),
    FOREIGN KEY (support_plan_id, user_id)
        REFERENCES care.support_plan(id, user_id)
);

CREATE TABLE care.reassessment_self_report (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES care.user_profile(account_id),
    idempotency_key varchar(128) NOT NULL,
    request_hash char(64) NOT NULL,
    source_version varchar(64) NOT NULL,
    current_period_start timestamptz NOT NULL,
    current_period_end timestamptz NOT NULL,
    current_experience varchar(32),
    helpful_context varchar(500),
    difficult_context varchar(500),
    version bigint NOT NULL,
    authored_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    deleted_at timestamptz,
    UNIQUE (user_id, idempotency_key),
    CHECK (source_version = 'reassessment-self-report-v1'),
    CHECK (current_experience IS NULL OR current_experience IN
        ('BETTER', 'ABOUT_THE_SAME', 'MORE_DIFFICULT', 'UNSURE')),
    CHECK (
        (deleted_at IS NULL AND current_experience IS NOT NULL)
        OR (deleted_at IS NOT NULL AND current_experience IS NULL
            AND helpful_context IS NULL AND difficult_context IS NULL)
    )
);

CREATE TABLE care.reassessment_summary (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES care.user_profile(account_id),
    idempotency_key varchar(128) NOT NULL,
    request_hash char(64) NOT NULL,
    summary_version varchar(64) NOT NULL,
    journal_job_id uuid, -- canonical v2 external -> journal-ai longitudinal job
    journal_analysis_id uuid, -- v1 reference or resolved v2 analysis when available
    previous_period_start timestamptz NOT NULL,
    previous_period_end timestamptz NOT NULL,
    current_period_start timestamptz NOT NULL,
    current_period_end timestamptz NOT NULL,
    snapshot jsonb NOT NULL,
    composed_at timestamptz NOT NULL,
    UNIQUE (user_id, idempotency_key),
    CHECK (summary_version IN ('reassessment-summary-v1', 'reassessment-summary-v2')),
    CHECK (
        (summary_version = 'reassessment-summary-v1'
            AND journal_analysis_id IS NOT NULL AND journal_job_id IS NULL)
        OR (summary_version = 'reassessment-summary-v2' AND journal_job_id IS NOT NULL)
    ),
    CHECK (
        previous_period_start < previous_period_end
        AND previous_period_end <= current_period_start
        AND current_period_start < current_period_end
        AND previous_period_end - previous_period_start = current_period_end - current_period_start
        AND previous_period_end - previous_period_start BETWEEN interval '7 days' AND interval '31 days'
    )
);

/* ========================================================================== */
/* ACTIVE — consultation-service / mentalbridge_consultation                  */
/* Evidence: Consultation Liquibase changesets 001-003.                       */
/* ========================================================================== */

CREATE TABLE consultation.specialist_profile (
    account_id uuid PRIMARY KEY, -- external -> identity.account.id
    display_name varchar(120) NOT NULL,
    biography varchar(2000) NOT NULL,
    years_experience smallint NOT NULL,
    timezone varchar(64) NOT NULL,
    approval_status varchar(16) NOT NULL,
    submitted_at timestamptz,
    reviewed_at timestamptz,
    reviewed_by uuid, -- external -> identity.account.id
    decision_reason_code varchar(64),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL
);

CREATE TABLE consultation.specialist_profile_support_area (
    specialist_account_id uuid NOT NULL REFERENCES consultation.specialist_profile(account_id),
    support_area varchar(40) NOT NULL,
    PRIMARY KEY (specialist_account_id, support_area)
);

CREATE TABLE consultation.specialist_profile_language (
    specialist_account_id uuid NOT NULL REFERENCES consultation.specialist_profile(account_id),
    language_tag varchar(16) NOT NULL,
    PRIMARY KEY (specialist_account_id, language_tag)
);

CREATE TABLE consultation.specialist_profile_status_history (
    id uuid PRIMARY KEY,
    specialist_account_id uuid NOT NULL REFERENCES consultation.specialist_profile(account_id),
    approval_status varchar(16) NOT NULL,
    actor_account_id uuid NOT NULL, -- external -> identity.account.id
    actor_role varchar(16) NOT NULL,
    reason_code varchar(64),
    occurred_at timestamptz NOT NULL
);

CREATE TABLE consultation.current_service_entitlement (
    account_id uuid PRIMARY KEY, -- external -> identity.account.id
    package_code varchar(16) NOT NULL,
    source varchar(16) NOT NULL,
    source_reference varchar(128) NOT NULL,
    established_by uuid, -- external -> identity.account.id
    effective_from timestamptz NOT NULL,
    effective_until timestamptz NOT NULL,
    policy_version varchar(64) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL
);

CREATE TABLE consultation.service_credit_period (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL, -- external -> identity.account.id
    plan_version varchar(64) NOT NULL,
    package_code varchar(16) NOT NULL,
    source varchar(16) NOT NULL,
    source_reference varchar(128) NOT NULL,
    period_start timestamptz NOT NULL,
    period_end timestamptz NOT NULL,
    allocated_count integer NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL,
    UNIQUE (account_id, plan_version, period_start, period_end)
);

CREATE TABLE consultation.service_credit (
    id uuid PRIMARY KEY,
    period_id uuid NOT NULL REFERENCES consultation.service_credit_period(id),
    ordinal integer NOT NULL,
    state varchar(16) NOT NULL,
    appointment_id uuid,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL,
    UNIQUE (period_id, ordinal)
);

CREATE TABLE consultation.service_credit_ledger (
    id uuid PRIMARY KEY,
    credit_id uuid NOT NULL REFERENCES consultation.service_credit(id),
    account_id uuid NOT NULL, -- external -> identity.account.id
    event_type varchar(16) NOT NULL,
    appointment_id uuid,
    idempotency_key varchar(128) NOT NULL,
    occurred_at timestamptz NOT NULL,
    UNIQUE (account_id, idempotency_key)
);

CREATE TABLE consultation.availability_slot (
    id uuid PRIMARY KEY,
    specialist_account_id uuid NOT NULL REFERENCES consultation.specialist_profile(account_id),
    start_at timestamptz NOT NULL,
    end_at timestamptz NOT NULL,
    timezone varchar(64) NOT NULL,
    modality varchar(24) NOT NULL,
    status varchar(16) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    withdrawn_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL,
    UNIQUE (specialist_account_id, idempotency_key)
);

CREATE TABLE consultation.appointment (
    id uuid PRIMARY KEY,
    user_account_id uuid NOT NULL, -- external -> identity.account.id
    specialist_account_id uuid NOT NULL REFERENCES consultation.specialist_profile(account_id),
    availability_slot_id uuid NOT NULL REFERENCES consultation.availability_slot(id),
    service_credit_id uuid NOT NULL REFERENCES consultation.service_credit(id),
    status varchar(24) NOT NULL,
    modality varchar(24) NOT NULL,
    scheduled_start_at timestamptz NOT NULL,
    scheduled_end_at timestamptz NOT NULL,
    display_timezone varchar(64) NOT NULL,
    requested_at timestamptz NOT NULL,
    decision_deadline_at timestamptz NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL,
    UNIQUE (user_account_id, idempotency_key)
);

/* ========================================================================== */
/* ACTIVE — content-notification-service / mentalbridge_content_notification  */
/* Evidence: node-pg-migrate-compatible SQL migrations 1-9.                   */
/* ========================================================================== */

CREATE TABLE content.resource (
    id uuid PRIMARY KEY,
    category varchar(32) NOT NULL,
    locale varchar(16) NOT NULL,
    title varchar(255) NOT NULL,
    summary text NOT NULL,
    content_body text,
    external_url varchar(2048),
    source_organization varchar(200),
    source_title varchar(500),
    source_url varchar(2048),
    source_review_note text,
    catalogue_visibility varchar(16) NOT NULL DEFAULT 'LISTED',
    status varchar(16) NOT NULL,
    reviewed_by uuid, -- external -> identity.account.id
    reviewed_at timestamptz,
    effective_at timestamptz,
    expires_at timestamptz,
    idempotency_key varchar(128),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL
);

CREATE TABLE content.notification_preference (
    user_id uuid NOT NULL, -- external -> identity.account.id
    channel varchar(16) NOT NULL,
    category varchar(32) NOT NULL,
    enabled boolean NOT NULL,
    quiet_hours jsonb,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, channel, category)
);

CREATE TABLE content.notification (
    id uuid PRIMARY KEY,
    recipient_id uuid NOT NULL, -- external -> identity.account.id
    category varchar(32) NOT NULL,
    title varchar(255) NOT NULL,
    body varchar(1000) NOT NULL,
    action_type varchar(40),
    action_target_id uuid,
    priority varchar(16) NOT NULL,
    read_at timestamptz,
    expires_at timestamptz,
    created_at timestamptz NOT NULL,
    deleted_at timestamptz
);

CREATE TABLE content.resource_idempotency_record (
    actor_id uuid NOT NULL, -- external -> identity.account.id
    operation varchar(40) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_fingerprint char(64) NOT NULL,
    resource_id uuid UNIQUE REFERENCES content.resource(id),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (actor_id, operation, idempotency_key)
);

CREATE TABLE content.resource_audit_event (
    id uuid PRIMARY KEY,
    occurred_at timestamptz NOT NULL,
    actor_id uuid NOT NULL, -- external -> identity.account.id
    action varchar(32) NOT NULL,
    resource_id uuid NOT NULL, -- logical local reference; retained without FK for audit history
    resource_version bigint,
    correlation_id uuid NOT NULL
);

CREATE TABLE content.resource_eligibility_publication (
    id uuid PRIMARY KEY,
    resource_id uuid NOT NULL REFERENCES content.resource(id),
    content_version bigint NOT NULL,
    policy_version varchar(64) NOT NULL,
    locale varchar(16) NOT NULL,
    effective_at timestamptz NOT NULL,
    expires_at timestamptz,
    published_by uuid NOT NULL, -- external -> identity.account.id
    published_at timestamptz NOT NULL,
    UNIQUE (resource_id, content_version, policy_version)
);

CREATE TABLE content.resource_eligibility_declaration (
    publication_id uuid NOT NULL REFERENCES content.resource_eligibility_publication(id),
    target_domain varchar(40) NOT NULL,
    eligibility_role varchar(16) NOT NULL,
    instrument varchar(16) NOT NULL,
    screening_levels text[] NOT NULL,
    support_tiers text[] NOT NULL,
    PRIMARY KEY (publication_id, target_domain, instrument)
);

CREATE TABLE content.resource_eligibility_withdrawal (
    publication_id uuid PRIMARY KEY REFERENCES content.resource_eligibility_publication(id),
    reason_code varchar(32) NOT NULL,
    withdrawn_by uuid NOT NULL, -- external -> identity.account.id
    withdrawn_at timestamptz NOT NULL
);

CREATE TABLE content.resource_eligibility_command_record (
    actor_id uuid NOT NULL, -- external -> identity.account.id
    operation varchar(40) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_fingerprint char(64) NOT NULL,
    publication_id uuid NOT NULL REFERENCES content.resource_eligibility_publication(id),
    response_snapshot jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (actor_id, operation, idempotency_key)
);

CREATE TABLE content.safety_directory_entry (
    id uuid PRIMARY KEY,
    name varchar(200) NOT NULL,
    entry_type varchar(16) NOT NULL,
    phone varchar(64) NOT NULL,
    address varchar(500),
    active boolean NOT NULL,
    source_name varchar(200) NOT NULL,
    source_reference varchar(2000) NOT NULL,
    source_retrieved_at timestamptz NOT NULL,
    source_checksum char(64),
    reviewed_by uuid, -- external -> identity.account.id
    reviewed_at timestamptz,
    verified_by uuid, -- external -> identity.account.id
    verified_at timestamptz,
    seed_key varchar(128) UNIQUE,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    record_version bigint NOT NULL
);

CREATE TABLE content.safety_directory_coverage (
    entry_id uuid NOT NULL REFERENCES content.safety_directory_entry(id),
    ordinal smallint NOT NULL,
    coverage_level varchar(16) NOT NULL,
    province_code varchar(32),
    province_name varchar(120),
    district_code varchar(32),
    district_name varchar(120),
    PRIMARY KEY (entry_id, ordinal)
);

CREATE TABLE content.safety_directory_review_history (
    id uuid PRIMARY KEY,
    entry_id uuid NOT NULL REFERENCES content.safety_directory_entry(id),
    record_version bigint NOT NULL,
    action varchar(16) NOT NULL,
    actor_id uuid NOT NULL, -- external -> identity.account.id
    source_reference varchar(2000) NOT NULL,
    occurred_at timestamptz NOT NULL
);

CREATE TABLE content.safety_directory_command_record (
    actor_id uuid NOT NULL, -- external -> identity.account.id
    operation varchar(32) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_fingerprint char(64) NOT NULL,
    entry_id uuid NOT NULL REFERENCES content.safety_directory_entry(id),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (actor_id, operation, idempotency_key)
);

CREATE TABLE content.safety_directory_area_alias (
    id uuid PRIMARY KEY,
    alias_text varchar(120) NOT NULL,
    province_code varchar(32) NOT NULL,
    district_code varchar(32),
    canonical boolean NOT NULL,
    seed_key varchar(128) UNIQUE,
    created_at timestamptz NOT NULL
);

/*
 * PROPOSED AND HISTORICAL RELATIONAL ENTITIES
 *
 * They are intentionally not expressed as CREATE TABLE statements here.
 * See ../canonical-entities.md for status and evidence. This prevents an
 * approved concept, backlog item, or removed table from appearing to be active
 * runtime persistence. MongoDB-owned aggregates are documented separately in
 * ../document/mongodb-logical-model.md and are never represented as SQL tables.
 */
