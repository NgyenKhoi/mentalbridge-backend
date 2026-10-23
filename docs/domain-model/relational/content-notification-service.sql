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
/* ACTIVE — content-notification-service / mentalbridge_content_notification  */
/* Evidence: node-pg-migrate-compatible SQL migrations 1-20.                  */
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
    resource_kind varchar(24) NOT NULL,
    interaction_type varchar(48) NOT NULL,
    repeatability varchar(16) NOT NULL,
    completion_mode varchar(24) NOT NULL,
    streak_eligible boolean NOT NULL,
    expected_duration_minutes smallint NOT NULL,
    cooldown_days smallint NOT NULL,
    recommended_frequency_per_week smallint NOT NULL,
    plan_tags text[] NOT NULL,
    structured_content jsonb NOT NULL,
    interaction_config jsonb NOT NULL,
    safety_notes text[] NOT NULL,
    source_retrieved_at timestamptz,
    source_content_hash char(64),
    content_version_label varchar(64) NOT NULL,
    source_review_status varchar(24) NOT NULL,
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

CREATE TABLE content.resource_review_decision (
    id uuid PRIMARY KEY,
    resource_id uuid NOT NULL REFERENCES content.resource(id),
    content_version bigint NOT NULL,
    decision varchar(16) NOT NULL,
    reason_code varchar(64) NOT NULL,
    authority_version varchar(64) NOT NULL,
    reviewed_by uuid NOT NULL, -- external -> identity.account.id
    reviewed_at timestamptz NOT NULL,
    UNIQUE (resource_id, content_version)
);

CREATE TABLE content.resource_publication_snapshot (
    resource_id uuid NOT NULL REFERENCES content.resource(id),
    content_version bigint NOT NULL,
    review_decision_id uuid REFERENCES content.resource_review_decision(id),
    snapshot jsonb NOT NULL,
    published_by uuid NOT NULL, -- external -> identity.account.id
    published_at timestamptz NOT NULL,
    PRIMARY KEY (resource_id, content_version)
);

CREATE TABLE content.resource_publication_withdrawal (
    resource_id uuid NOT NULL,
    content_version bigint NOT NULL,
    reason_code varchar(32) NOT NULL,
    withdrawn_by uuid NOT NULL, -- external -> identity.account.id
    withdrawn_at timestamptz NOT NULL,
    PRIMARY KEY (resource_id, content_version),
    FOREIGN KEY (resource_id, content_version)
      REFERENCES content.resource_publication_snapshot(resource_id, content_version)
);

CREATE TABLE content.resource_daily_progress (
    owner_id uuid NOT NULL, -- external -> identity.account.id
    resource_id uuid NOT NULL REFERENCES content.resource(id),
    local_date date NOT NULL,
    resource_version bigint NOT NULL,
    status varchar(16) NOT NULL,
    completed_action_ids text[] NOT NULL,
    completed_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL,
    PRIMARY KEY (owner_id, resource_id, local_date)
);

CREATE TABLE content.resource_learning_completion (
    owner_id uuid NOT NULL, -- external -> identity.account.id
    resource_id uuid NOT NULL REFERENCES content.resource(id),
    resource_version bigint NOT NULL,
    support_plan_id uuid, -- external -> care.support_plan.id
    local_date date NOT NULL,
    completed_at timestamptz NOT NULL,
    PRIMARY KEY (owner_id, resource_id)
);

CREATE TABLE content.resource_practice_session (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL, -- external -> identity.account.id
    resource_id uuid NOT NULL REFERENCES content.resource(id),
    resource_version bigint NOT NULL,
    support_plan_id uuid, -- external -> care.support_plan.id
    local_date date NOT NULL,
    completed_at timestamptz NOT NULL,
    started_at timestamptz,
    duration_seconds integer
);

CREATE TABLE content.resource_reward_preference (
    owner_id uuid PRIMARY KEY, -- external -> identity.account.id
    visibility_state varchar(16) NOT NULL,
    version bigint NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE TABLE content.achievement_preference (
    owner_id uuid PRIMARY KEY, -- external -> identity.account.id
    visibility_state varchar(16) NOT NULL,
    version bigint NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE TABLE content.resource_bookmark (
    owner_id uuid NOT NULL, -- external -> identity.account.id
    resource_id uuid NOT NULL,
    resource_version bigint NOT NULL,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (owner_id, resource_id),
    FOREIGN KEY (resource_id, resource_version)
        REFERENCES content.resource_publication_snapshot(resource_id, content_version)
);

CREATE TABLE content.resource_daily_assignment (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL, -- external -> identity.account.id
    local_date date NOT NULL,
    time_zone varchar(64) NOT NULL,
    support_plan_id uuid NOT NULL, -- external -> care.support_plan.id
    support_plan_version bigint NOT NULL,
    plan_tags text[] NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (owner_id, local_date)
);

CREATE TABLE content.resource_daily_assignment_item (
    assignment_id uuid NOT NULL REFERENCES content.resource_daily_assignment(id) ON DELETE CASCADE,
    ordinal smallint NOT NULL,
    resource_id uuid NOT NULL REFERENCES content.resource(id),
    selection_reason varchar(24) NOT NULL,
    PRIMARY KEY (assignment_id, ordinal),
    UNIQUE (assignment_id, resource_id)
);

CREATE TABLE content.resource_weekly_bingo (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL, -- external -> identity.account.id
    week_start date NOT NULL,
    time_zone varchar(64) NOT NULL,
    support_plan_id uuid NOT NULL, -- external -> care.support_plan.id
    support_plan_version bigint NOT NULL,
    plan_tags text[] NOT NULL,
    created_at timestamptz NOT NULL,
    version bigint NOT NULL,
    updated_at timestamptz NOT NULL,
    last_synced_at timestamptz NOT NULL,
    UNIQUE (owner_id, week_start, support_plan_id)
);

CREATE TABLE content.resource_weekly_bingo_item (
    board_id uuid NOT NULL REFERENCES content.resource_weekly_bingo(id) ON DELETE CASCADE,
    ordinal smallint NOT NULL,
    resource_id uuid NOT NULL REFERENCES content.resource(id),
    resource_version bigint NOT NULL,
    self_confirmed_at timestamptz,
    support_plan_action_id varchar(128),
    support_plan_action_version bigint,
    retired_at timestamptz,
    CHECK ((support_plan_action_id IS NULL) = (support_plan_action_version IS NULL)),
    CHECK (support_plan_action_version IS NULL OR support_plan_action_version >= 0),
    PRIMARY KEY (board_id, ordinal),
    UNIQUE (board_id, resource_id),
    FOREIGN KEY (resource_id, resource_version)
        REFERENCES content.resource_publication_snapshot(resource_id, content_version)
);

CREATE TABLE content.notification_preference (
    user_id uuid PRIMARY KEY, -- external -> identity.account.id
    owner_type varchar(16) NOT NULL, -- USER or SPECIALIST role boundary
    notifications_enabled boolean NOT NULL,
    channel_in_app_enabled boolean NOT NULL,
    channel_email_enabled boolean NOT NULL,
    channel_push_enabled boolean NOT NULL,
    group_journal_reminder_enabled boolean NOT NULL,
    group_emotion_check_in_enabled boolean NOT NULL,
    group_streak_milestone_enabled boolean NOT NULL,
    group_screening_reassessment_enabled boolean NOT NULL,
    group_appointment_message_enabled boolean NOT NULL,
    group_resource_system_enabled boolean NOT NULL,
    group_community_interaction_enabled boolean NOT NULL,
    quiet_hours_enabled boolean NOT NULL,
    quiet_hours_start time NOT NULL,
    quiet_hours_end time NOT NULL,
    time_zone varchar(64) NOT NULL,
    email_cadence varchar(24) NOT NULL,
    email_wellbeing_digest_enabled boolean NOT NULL,
    email_resource_reminders_enabled boolean NOT NULL,
    email_daily_digest_time time NOT NULL,
    email_resource_reminder_time time NOT NULL,
    email_appointment_reminders_enabled boolean NOT NULL,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE TABLE content.wellbeing_email_delivery (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL, -- external -> identity.account.id
    local_date date NOT NULL,
    delivery_kind varchar(24) NOT NULL,
    time_zone varchar(64) NOT NULL,
    state varchar(16) NOT NULL,
    attempt_count smallint NOT NULL,
    content_counts jsonb NOT NULL,
    provider_message_id varchar(160),
    failure_code varchar(48),
    claimed_at timestamptz NOT NULL,
    attempted_at timestamptz,
    delivered_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (owner_id, local_date, delivery_kind)
);

CREATE TABLE content.notification (
    id uuid PRIMARY KEY,
    recipient_id uuid NOT NULL, -- external -> identity.account.id
    category varchar(32) NOT NULL CHECK (category IN (
        'REMINDER', 'MESSAGE', 'APPOINTMENT', 'SYSTEM_RESOURCE',
        'ASSESSMENT_REASSESSMENT', 'STREAK_MILESTONE', 'JOURNAL_REMINDER',
        'EMOTION_CHECKIN_REMINDER', 'JOURNAL_STREAK_MILESTONE',
        'EMOTION_STREAK_MILESTONE', 'COMMUNITY_COMMENT', 'COMMUNITY_REPLY',
        'COMMUNITY_REACTION', 'COMMUNITY_MESSAGE', 'COMMUNITY_REQUEST', 'COMMUNITY_INVITATION', 'ASSESSMENT_RESULT_AVAILABLE',
        'SUPPORT_GUIDE_AVAILABLE', 'ACCOUNT_STATUS', 'ASSESSMENT', 'CHAT',
        'FOLLOW_UP', 'SAFETY', 'SYSTEM'
    )),
    title varchar(255) NOT NULL,
    body varchar(1000) NOT NULL,
    action_type varchar(40),
    action_target_id uuid,
    grouping_key varchar(160),
    priority varchar(16) NOT NULL,
    read_at timestamptz,
    expires_at timestamptz NOT NULL,
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    deleted_at timestamptz,
    source varchar(64) NOT NULL,
    source_identity varchar(160) NOT NULL,
    request_fingerprint char(64) NOT NULL,
    delivery_state varchar(16) NOT NULL,
    version bigint NOT NULL,
    UNIQUE (recipient_id, source, source_identity),
    CHECK (
        (action_type IS NULL AND action_target_id IS NULL)
        OR (action_type IN (
            'OPEN_JOURNAL', 'OPEN_MESSAGES', 'OPEN_APPOINTMENTS',
            'OPEN_RESOURCES', 'OPEN_ASSESSMENTS', 'OPEN_SPECIALIST_APPOINTMENTS'
        ) AND action_target_id IS NULL)
        OR (action_type IN (
            'OPEN_RESOURCE', 'OPEN_COMMUNITY_POST', 'OPEN_SUPPORT_GUIDE',
            'OPEN_COMMUNITY_CONVERSATION', 'OPEN_COMMUNITY_REQUEST', 'OPEN_COMMUNITY_INVITATION'
        ) AND action_target_id IS NOT NULL)
    )
);

CREATE TABLE content.community_notification_job (
    id uuid PRIMARY KEY,
    recipient_id uuid NOT NULL,
    source varchar(64) NOT NULL CHECK (source IN ('COMMUNITY_INTERACTION_V1', 'COMMUNITY_COMMENT_REACTION_V1', 'COMMUNITY_CHAT_V1')),
    source_identity varchar(160) NOT NULL,
    request_fingerprint char(64) NOT NULL,
    projection jsonb NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('PENDING', 'DELIVERED', 'CANCELLED')),
    available_at timestamptz NOT NULL,
    claim_token uuid,
    claim_until timestamptz,
    attempts integer NOT NULL,
    notification_id uuid REFERENCES content.notification(id),
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (recipient_id, source, source_identity),
    CHECK ((claim_token IS NULL) = (claim_until IS NULL)),
    CHECK (state <> 'DELIVERED' OR notification_id IS NOT NULL)
);

CREATE TABLE content.appointment_email_reminder (
    id uuid PRIMARY KEY,
    recipient_id uuid NOT NULL, -- external -> identity.account.id
    appointment_id uuid NOT NULL, -- external -> consultation.appointment.id
    appointment_version bigint NOT NULL,
    appointment_status varchar(24) NOT NULL,
    modality varchar(24) NOT NULL,
    scheduled_start_at timestamptz NOT NULL,
    target_at timestamptz NOT NULL,
    due_at timestamptz NOT NULL,
    delivery_state varchar(24) NOT NULL,
    attempt_count integer NOT NULL,
    first_attempt_at timestamptz,
    last_attempt_at timestamptz,
    next_attempt_at timestamptz,
    provider_idempotency_key uuid NOT NULL,
    provider_message_id varchar(255),
    failure_code varchar(64),
    delivered_at timestamptz,
    invalidated_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (recipient_id, appointment_id, appointment_version)
);

CREATE TABLE content.push_device_registration (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL, -- external -> identity.account.id
    installation_id uuid NOT NULL,
    platform varchar(16) NOT NULL,
    provider varchar(16) NOT NULL,
    provider_token_ciphertext text NOT NULL,
    provider_token_iv varchar(32) NOT NULL,
    provider_token_auth_tag varchar(32) NOT NULL,
    provider_token_hash char(64) NOT NULL,
    permission_status varchar(16) NOT NULL,
    status varchar(16) NOT NULL,
    app_version varchar(32),
    last_registered_at timestamptz NOT NULL,
    revoked_at timestamptz,
    invalidated_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL,
    UNIQUE (owner_id, installation_id)
);

CREATE UNIQUE INDEX uq_push_device_active_token
    ON content.push_device_registration (provider, provider_token_hash)
    WHERE status IN ('ACTIVE', 'DISABLED');

CREATE TABLE content.push_delivery_attempt (
    id uuid PRIMARY KEY,
    notification_id uuid NOT NULL REFERENCES content.notification(id),
    device_registration_id uuid NOT NULL REFERENCES content.push_device_registration(id),
    state varchar(20) NOT NULL,
    attempt_count smallint NOT NULL,
    available_at timestamptz NOT NULL,
    claimed_at timestamptz,
    provider_ticket_id varchar(160),
    failure_code varchar(48),
    last_attempt_at timestamptz,
    accepted_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (notification_id, device_registration_id)
);

CREATE TABLE content.appointment_reminder_checkpoint (
    appointment_id uuid PRIMARY KEY, -- external -> consultation.appointment.id
    latest_version bigint NOT NULL,
    latest_status varchar(24) NOT NULL,
    last_message_id uuid NOT NULL,
    updated_at timestamptz NOT NULL
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

CREATE TABLE content.content_admin_audit_outbox (
    id uuid PRIMARY KEY,
    deduplication_key varchar(200) NOT NULL UNIQUE,
    event_type varchar(120) NOT NULL,
    correlation_id uuid NOT NULL,
    payload jsonb NOT NULL,
    occurred_at timestamptz NOT NULL,
    published_at timestamptz,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
