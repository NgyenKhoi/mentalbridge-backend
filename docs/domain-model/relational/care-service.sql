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

CREATE TABLE care.screening_episode (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES care.user_profile(account_id),
    purpose varchar(24) NOT NULL,       -- INITIAL_CHECK | REASSESSMENT
    status varchar(16) NOT NULL,        -- IN_PROGRESS | READY | COMPLETED
    phq9_assessment_id uuid,
    gad7_assessment_id uuid,
    support_evaluation_id uuid,
    presentation_evaluation_id uuid,   -- compatibility v1 presentation result
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    completed_at timestamptz,
    version bigint NOT NULL,
    UNIQUE (id, user_id),
    FOREIGN KEY (phq9_assessment_id, user_id)
        REFERENCES care.assessment_submission(id, user_id),
    FOREIGN KEY (gad7_assessment_id, user_id)
        REFERENCES care.assessment_submission(id, user_id),
    FOREIGN KEY (support_evaluation_id, user_id)
        REFERENCES care.support_evaluation_v2(id, user_id),
    FOREIGN KEY (presentation_evaluation_id, user_id)
        REFERENCES care.support_evaluation(id, user_id)
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
    category varchar(32) NOT NULL CHECK (category IN (
        'REMINDER',
        'MESSAGE',
        'APPOINTMENT',
        'SYSTEM_RESOURCE',
        'ASSESSMENT_REASSESSMENT',
        'STREAK_MILESTONE',
        'JOURNAL_REMINDER',
        'EMOTION_CHECKIN_REMINDER',
        'JOURNAL_STREAK_MILESTONE',
        'EMOTION_STREAK_MILESTONE',
        'ASSESSMENT',
        'CHAT',
        'FOLLOW_UP',
        'SAFETY',
        'SYSTEM'
    )),
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

CREATE TABLE care.support_plan_personalization (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES care.user_profile(account_id),
    revision bigint NOT NULL,
    schema_version varchar(64) NOT NULL,
    status varchar(16) NOT NULL,
    preferred_focus varchar(32),
    desired_pace varchar(16),
    daily_time_budget_minutes smallint,
    preferred_daypart varchar(16),
    submitted_at timestamptz NOT NULL,
    UNIQUE (user_id, revision),
    UNIQUE (id, user_id, revision)
);

CREATE TABLE care.support_plan_personalization_activity_mode (
    id uuid PRIMARY KEY,
    personalization_id uuid NOT NULL REFERENCES care.support_plan_personalization(id),
    ordinal smallint NOT NULL,
    activity_mode varchar(32) NOT NULL,
    UNIQUE (personalization_id, ordinal),
    UNIQUE (personalization_id, activity_mode)
);

CREATE TABLE care.support_plan_personalization_barrier (
    id uuid PRIMARY KEY,
    personalization_id uuid NOT NULL REFERENCES care.support_plan_personalization(id),
    ordinal smallint NOT NULL,
    barrier varchar(32) NOT NULL,
    UNIQUE (personalization_id, ordinal),
    UNIQUE (personalization_id, barrier)
);

CREATE TABLE care.support_plan (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES care.user_profile(account_id),
    support_evaluation_id uuid NOT NULL,
    status varchar(16) NOT NULL,
    version bigint NOT NULL,
    model_version integer NOT NULL,
    plan_policy_version varchar(128),
    action_count smallint NOT NULL,
    evaluation_policy_version varchar(64) NOT NULL,
    evaluated_at timestamptz NOT NULL,
    selection_policy_version varchar(64),
    resource_policy_version varchar(64),
    resources_resolved_at timestamptz,
    personalization_id uuid,
    personalization_revision bigint,
    personalization_schema_version varchar(64),
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
    selected_resource_count smallint,
    activated_at timestamptz,
    completed_at timestamptz,
    completion_reason varchar(32),
    superseded_at timestamptz,
    discarded_at timestamptz,
    cycle_timezone varchar(64),
    cycle_number integer,
    cycle_started_on date,
    cycle_paused_days integer,
    cycle_paused_on date,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (id, user_id),
    FOREIGN KEY (support_evaluation_id, user_id)
        REFERENCES care.support_evaluation_v2(id, user_id),
    FOREIGN KEY (personalization_id, user_id, personalization_revision)
        REFERENCES care.support_plan_personalization(id, user_id, revision)
);

CREATE TABLE care.support_plan_focus (
    id uuid PRIMARY KEY,
    support_plan_id uuid NOT NULL,
    support_plan_model_version integer NOT NULL,
    user_id uuid NOT NULL,
    ordinal smallint NOT NULL,
    title varchar(255) NOT NULL,
    rationale varchar(2048),
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (id, support_plan_id, user_id),
    UNIQUE (support_plan_id, ordinal),
    FOREIGN KEY (support_plan_id, user_id)
        REFERENCES care.support_plan(id, user_id),
    FOREIGN KEY (support_plan_id, support_plan_model_version)
        REFERENCES care.support_plan(id, model_version)
);

CREATE TABLE care.support_plan_action (
    id uuid PRIMARY KEY,
    support_plan_id uuid NOT NULL,
    plan_focus_id uuid NOT NULL,
    user_id uuid NOT NULL,
    ordinal smallint NOT NULL,
    action_type varchar(32) NOT NULL,
    purpose_code varchar(64) NOT NULL,
    rationale varchar(2048),
    source_owner varchar(32),
    source_kind varchar(48),
    source_id varchar(255),
    source_revision varchar(128),
    source_policy_version varchar(128),
    source_publication_id uuid,
    source_eligibility_policy_version varchar(128),
    source_verified_at timestamptz,
    provenance_type varchar(32) NOT NULL,
    provenance_reference_id varchar(255),
    provenance_reference_version varchar(128),
    provenance_accepted_at timestamptz NOT NULL,
    presentation_title varchar(255) NOT NULL,
    presentation_summary varchar(4096),
    presentation_instructions varchar(2048),
    presentation_external_url varchar(2048),
    status varchar(16) NOT NULL,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    activated_at timestamptz,
    ended_at timestamptz,
    UNIQUE (id, support_plan_id, user_id),
    UNIQUE (id, support_plan_id, user_id, action_type),
    UNIQUE (plan_focus_id, ordinal),
    FOREIGN KEY (support_plan_id, user_id)
        REFERENCES care.support_plan(id, user_id),
    FOREIGN KEY (plan_focus_id, support_plan_id, user_id)
        REFERENCES care.support_plan_focus(id, support_plan_id, user_id)
);

CREATE TABLE care.support_plan_template_family (
    id uuid PRIMARY KEY,
    support_plan_id uuid NOT NULL REFERENCES care.support_plan(id),
    ordinal smallint NOT NULL,
    family varchar(64) NOT NULL,
    template_version integer NOT NULL,
    target_domain varchar(48) NOT NULL,
    focus_title varchar(255),
    focus_rationale varchar(2048),
    focus_reason_code varchar(64)
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

CREATE TABLE care.plan_change_request (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES care.user_profile(account_id),
    specialist_id uuid NOT NULL, -- external -> identity.account.id
    source_proposal_id uuid NOT NULL UNIQUE, -- external -> consultation.agreed_next_step.id
    source_proposal_version bigint NOT NULL,
    source_appointment_id uuid NOT NULL, -- external -> consultation.appointment.id
    source_summary_id uuid NOT NULL, -- external -> consultation.session_summary.id
    source_summary_version bigint NOT NULL,
    source_completion_fact_id uuid NOT NULL, -- external -> consultation appointment completion fact
    proposal_reason_code varchar(48) NOT NULL,
    resource_id uuid NOT NULL, -- external -> content.resource.id
    resource_version bigint NOT NULL,
    proposal_title varchar(160) NOT NULL,
    proposal_details varchar(500),
    current_support_plan_id uuid NOT NULL,
    current_support_plan_version bigint NOT NULL,
    replacement_support_plan_id uuid,
    replacement_support_plan_version bigint,
    target_slot_id varchar(64) NOT NULL,
    current_resource_id uuid,
    current_resource_version bigint,
    current_resource_title varchar(255),
    status varchar(32) NOT NULL CHECK (status IN ('READY_FOR_REVIEW','ACCEPTED','REJECTED')),
    outcome_code varchar(64) NOT NULL CHECK (outcome_code IN
        ('PROPOSAL_ADMISSIBLE','PROPOSAL_APPLIED','USER_REJECTED')),
    idempotency_key varchar(128) NOT NULL,
    request_hash varchar(64) NOT NULL,
    decision_idempotency_key varchar(128),
    decision_hash varchar(64),
    version bigint NOT NULL,
    reviewed_at timestamptz NOT NULL,
    decided_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (user_id, idempotency_key),
    UNIQUE (user_id, decision_idempotency_key),
    FOREIGN KEY (current_support_plan_id, user_id)
        REFERENCES care.support_plan(id, user_id),
    FOREIGN KEY (replacement_support_plan_id, user_id)
        REFERENCES care.support_plan(id, user_id)
);

CREATE TABLE care.support_plan_command (
    user_id uuid NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    command_type varchar(16) NOT NULL CHECK (command_type IN ('ACTIVATE','REPLACE','CYCLE_CONTINUE')),
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
    source_support_plan_id uuid,
    source_support_plan_version bigint,
    reassessment_summary_id uuid,
    plan_change_request_id uuid REFERENCES care.plan_change_request(id),
    replacement_review_outcome varchar(64),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, idempotency_key),
    FOREIGN KEY (support_plan_id, user_id)
        REFERENCES care.support_plan(id, user_id),
    FOREIGN KEY (source_support_plan_id, user_id)
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
    source_slot_key varchar(64),
    source_resource_id uuid, -- external -> content.resource.id; v2 branch only
    source_content_version bigint,
    source_action_id uuid, -- v3 -> care.support_plan_action.id
    source_action_version bigint,
    source_action_type varchar(32),
    source_title varchar(255) NOT NULL,
    schedule_policy_version varchar(128),
    reminder_enabled boolean NOT NULL DEFAULT false,
    recurrence_type varchar(16) NOT NULL,
    recurrence_day_of_week smallint,
    recurrence_days_mask smallint,
    preferred_daypart varchar(16),
    estimated_duration_minutes smallint,
    local_time time NOT NULL,
    timezone varchar(64) NOT NULL,
    effective_from date NOT NULL,
    effective_until date,
    occurrence_generation_until date,
    status varchar(16) NOT NULL,
    activated_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (id, support_plan_id, user_id),
    UNIQUE (id, support_plan_id, user_id, schedule_version, source_plan_version,
        source_action_id, source_action_version, source_action_type),
    FOREIGN KEY (support_plan_id, user_id)
        REFERENCES care.support_plan(id, user_id),
    FOREIGN KEY (source_action_id, support_plan_id, user_id, source_action_type)
        REFERENCES care.support_plan_action(id, support_plan_id, user_id, action_type)
);

CREATE UNIQUE INDEX ux_support_plan_activity_schedule_current_slot
    ON care.support_plan_activity_schedule (support_plan_id, source_slot_key)
    WHERE source_slot_key IS NOT NULL AND status IN ('DRAFT', 'ACTIVE', 'PAUSED');

CREATE UNIQUE INDEX ux_support_plan_activity_schedule_current_action
    ON care.support_plan_activity_schedule (support_plan_id, source_action_id)
    WHERE source_action_id IS NOT NULL AND status IN ('DRAFT', 'ACTIVE', 'PAUSED');

CREATE TABLE care.support_plan_schedule_command (
    user_id uuid NOT NULL REFERENCES care.user_profile(account_id),
    idempotency_key varchar(128) NOT NULL,
    request_hash char(64) NOT NULL,
    support_plan_id uuid NOT NULL,
    action_id uuid NOT NULL,
    expected_plan_version bigint NOT NULL,
    resulting_plan_version bigint NOT NULL,
    resulting_schedule_id uuid,
    resulting_schedule_version integer,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, idempotency_key),
    FOREIGN KEY (support_plan_id, user_id)
        REFERENCES care.support_plan(id, user_id)
);

CREATE INDEX ix_support_plan_schedule_command_plan
    ON care.support_plan_schedule_command (support_plan_id, created_at, idempotency_key);

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
    state_reason varchar(32), -- cancellation reason includes SCHEDULE_CHANGED
    source_plan_version bigint NOT NULL,
    source_slot_key varchar(64),
    source_resource_id uuid, -- external -> content.resource.id; v2 branch only
    source_content_version bigint,
    source_action_id uuid, -- v3 -> care.support_plan_action.id
    source_action_version bigint,
    source_action_type varchar(32),
    source_title varchar(255) NOT NULL,
    evidence_owner varchar(32),
    evidence_kind varchar(48),
    evidence_id varchar(255),
    evidence_revision varchar(128),
    evidence_occurred_at timestamptz,
    evidence_verified_at timestamptz,
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
        REFERENCES care.support_plan(id, user_id),
    FOREIGN KEY (source_action_id, support_plan_id, user_id, source_action_type)
        REFERENCES care.support_plan_action(id, support_plan_id, user_id, action_type),
    FOREIGN KEY (activity_schedule_id, support_plan_id, user_id, schedule_version,
        source_plan_version, source_action_id, source_action_version, source_action_type)
        REFERENCES care.support_plan_activity_schedule(id, support_plan_id, user_id, schedule_version,
            source_plan_version, source_action_id, source_action_version, source_action_type),
    CHECK ((
        (source_action_id IS NULL
            AND evidence_owner IS NULL AND evidence_kind IS NULL AND evidence_id IS NULL
            AND evidence_revision IS NULL AND evidence_occurred_at IS NULL
            AND evidence_verified_at IS NULL)
        OR (source_action_id IS NOT NULL AND state <> 'COMPLETED'
            AND evidence_owner IS NULL AND evidence_kind IS NULL AND evidence_id IS NULL
            AND evidence_revision IS NULL AND evidence_occurred_at IS NULL
            AND evidence_verified_at IS NULL)
        OR (source_action_id IS NOT NULL AND state = 'COMPLETED' AND (
            (source_action_type IN ('RESOURCE', 'ROUTINE_SELF_MANAGEMENT')
                AND evidence_owner = 'CARE' AND evidence_kind = 'USER_ACKNOWLEDGEMENT'
                AND evidence_id IS NOT NULL AND length(btrim(evidence_id)) BETWEEN 1 AND 255
                AND (evidence_revision IS NULL OR length(btrim(evidence_revision)) BETWEEN 1 AND 128)
                AND evidence_occurred_at IS NOT NULL AND evidence_verified_at IS NOT NULL)
            OR (source_action_type = 'RESOURCE' AND evidence_owner = 'CONTENT'
                AND evidence_kind IN ('RESOURCE_PRACTICE_SESSION', 'RESOURCE_COMPLETION')
                AND evidence_id IS NOT NULL AND length(btrim(evidence_id)) BETWEEN 1 AND 255
                AND evidence_revision IS NOT NULL AND length(btrim(evidence_revision)) BETWEEN 1 AND 128
                AND evidence_occurred_at IS NOT NULL AND evidence_verified_at IS NOT NULL)
            OR (source_action_type = 'SELF_REPORT_REVIEW'
                AND evidence_owner = 'CARE' AND evidence_kind = 'SELF_REPORT_REVIEW'
                AND evidence_id IS NOT NULL AND length(btrim(evidence_id)) BETWEEN 1 AND 255
                AND (evidence_revision IS NULL OR length(btrim(evidence_revision)) BETWEEN 1 AND 128)
                AND evidence_occurred_at IS NOT NULL AND evidence_verified_at IS NOT NULL)
            OR (source_action_type = 'JOURNAL' AND evidence_owner = 'JOURNAL_AI'
                AND evidence_kind = 'JOURNAL_ENTRY' AND evidence_id IS NOT NULL
                AND length(btrim(evidence_id)) BETWEEN 1 AND 255
                AND (evidence_revision IS NULL OR length(btrim(evidence_revision)) BETWEEN 1 AND 128)
                AND evidence_occurred_at IS NOT NULL AND evidence_verified_at IS NOT NULL)
            OR (source_action_type = 'EMOTION_CHECK_IN' AND evidence_owner = 'JOURNAL_AI'
                AND evidence_kind = 'EMOTION_CHECK_IN' AND evidence_id IS NOT NULL
                AND length(btrim(evidence_id)) BETWEEN 1 AND 255
                AND (evidence_revision IS NULL OR length(btrim(evidence_revision)) BETWEEN 1 AND 128)
                AND evidence_occurred_at IS NOT NULL AND evidence_verified_at IS NOT NULL)
            OR (source_action_type = 'PROFESSIONAL_SUPPORT' AND evidence_owner = 'CONSULTATION'
                AND evidence_kind = 'APPOINTMENT_FULFILMENT' AND evidence_id IS NOT NULL
                AND length(btrim(evidence_id)) BETWEEN 1 AND 255
                AND (evidence_revision IS NULL OR length(btrim(evidence_revision)) BETWEEN 1 AND 128)
                AND evidence_occurred_at IS NOT NULL AND evidence_verified_at IS NOT NULL)
            OR (source_action_type = 'REASSESSMENT_SCREENING' AND evidence_owner = 'CARE'
                AND evidence_kind = 'ASSESSMENT_HISTORY' AND evidence_id IS NOT NULL
                AND length(btrim(evidence_id)) BETWEEN 1 AND 255
                AND (evidence_revision IS NULL OR length(btrim(evidence_revision)) BETWEEN 1 AND 128)
                AND evidence_occurred_at IS NOT NULL AND evidence_verified_at IS NOT NULL)
        ))) IS TRUE
        AND (evidence_verified_at IS NULL OR evidence_verified_at >= evidence_occurred_at)
    )
);

CREATE UNIQUE INDEX ux_support_plan_activity_occurrence_evidence_fact
    ON care.support_plan_activity_occurrence (
        user_id, evidence_owner, evidence_kind, evidence_id, coalesce(evidence_revision, '')
    )
    WHERE evidence_owner IS NOT NULL;

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
    UNIQUE (id, user_id),
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

ALTER TABLE care.support_plan_command
    ADD FOREIGN KEY (reassessment_summary_id, user_id)
        REFERENCES care.reassessment_summary(id, user_id);

CREATE TABLE care.consultation_brief (
    id uuid PRIMARY KEY,
    appointment_id uuid NOT NULL UNIQUE, -- external -> consultation.appointment.id
    user_id uuid NOT NULL REFERENCES care.user_profile(account_id),
    specialist_id uuid NOT NULL, -- external -> identity.account.id
    appointment_start_at timestamptz NOT NULL,
    appointment_end_at timestamptz NOT NULL,
    appointment_version bigint NOT NULL,
    status varchar(16) NOT NULL,
    current_situation varchar(1000),
    support_evaluation_id uuid REFERENCES care.support_evaluation_v2(id),
    user_goals jsonb,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    deleted_at timestamptz
);

CREATE TABLE care.consultation_brief_snapshot (
    id uuid PRIMARY KEY,
    brief_id uuid NOT NULL REFERENCES care.consultation_brief(id),
    snapshot_version bigint NOT NULL,
    appointment_id uuid NOT NULL,
    user_id uuid NOT NULL,
    specialist_id uuid NOT NULL,
    current_situation varchar(1000),
    support_evaluation_id uuid REFERENCES care.support_evaluation_v2(id),
    screening_context jsonb,
    user_goals jsonb,
    created_at timestamptz NOT NULL,
    deleted_at timestamptz,
    UNIQUE (brief_id, snapshot_version)
);

CREATE TABLE care.consultation_brief_grant (
    id uuid PRIMARY KEY,
    brief_id uuid NOT NULL REFERENCES care.consultation_brief(id),
    snapshot_id uuid NOT NULL REFERENCES care.consultation_brief_snapshot(id),
    appointment_id uuid NOT NULL,
    user_id uuid NOT NULL,
    specialist_id uuid NOT NULL,
    purpose varchar(32) NOT NULL,
    status varchar(16) NOT NULL,
    access_start_at timestamptz NOT NULL,
    access_end_at timestamptz NOT NULL,
    approved_at timestamptz NOT NULL,
    revoked_at timestamptz,
    version bigint NOT NULL
);

CREATE TABLE care.consultation_brief_audit (
    id uuid PRIMARY KEY,
    appointment_id uuid NOT NULL,
    grant_id uuid,
    actor_id uuid NOT NULL,
    actor_type varchar(16) NOT NULL,
    action varchar(32) NOT NULL,
    outcome varchar(16) NOT NULL,
    reason_code varchar(64) NOT NULL,
    correlation_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL
);

CREATE TABLE care.consultation_brief_ai_draft_job (
    id uuid PRIMARY KEY,
    appointment_id uuid NOT NULL,
    brief_id uuid NOT NULL REFERENCES care.consultation_brief(id),
    user_id uuid NOT NULL,
    brief_version bigint NOT NULL,
    support_evaluation_id uuid NOT NULL REFERENCES care.support_evaluation_v2(id),
    idempotency_key varchar(128) NOT NULL,
    request_fingerprint varchar(64) NOT NULL,
    source_set_version varchar(64) NOT NULL,
    status varchar(16) NOT NULL,
    attempt_count integer NOT NULL,
    terminal_reason varchar(64),
    suggested_current_situation varchar(1000),
    suggested_user_goals jsonb,
    consent_policy_version varchar(64),
    service_plan varchar(16),
    entitlement_source varchar(32),
    entitlement_policy_version varchar(96),
    entitlement_version bigint,
    routing_policy_version varchar(96),
    provider_approval_version varchar(96),
    provider varchar(32),
    model varchar(128),
    prompt_version varchar(96),
    schema_version integer,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    completed_at timestamptz,
    UNIQUE (user_id, idempotency_key)
);
