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
/* ACTIVE — consultation-service / mentalbridge_consultation                  */
/* Evidence: Consultation Liquibase changesets 001-007.                       */
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
    version bigint NOT NULL,
    published_version bigint NOT NULL
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

CREATE TABLE consultation.specialist_credential_document (
    id uuid PRIMARY KEY,
    specialist_account_id uuid NOT NULL REFERENCES consultation.specialist_profile(account_id) ON DELETE CASCADE,
    title varchar(160) NOT NULL,
    issuer varchar(160) NOT NULL,
    issue_year smallint NOT NULL,
    credential_type varchar(24) NOT NULL,
    file_name varchar(180) NOT NULL,
    media_type varchar(24) NOT NULL,
    file_bytes bigint NOT NULL,
    storage_key varchar(180) NOT NULL UNIQUE,
    storage_format varchar(8) NOT NULL,
    verification_status varchar(16) NOT NULL,
    badge_visible boolean NOT NULL,
    reviewed_by uuid,
    reviewed_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL
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

CREATE TABLE consultation.specialist_profile_approved_version (
    id uuid PRIMARY KEY,
    specialist_account_id uuid NOT NULL REFERENCES consultation.specialist_profile(account_id),
    published_version bigint NOT NULL,
    profile_snapshot jsonb NOT NULL,
    approved_by uuid NOT NULL,
    approved_at timestamptz NOT NULL,
    source_amendment_id uuid,
    UNIQUE (specialist_account_id, published_version)
);

CREATE TABLE consultation.specialist_profile_amendment (
    id uuid PRIMARY KEY,
    specialist_account_id uuid NOT NULL REFERENCES consultation.specialist_profile(account_id),
    base_published_version bigint NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('DRAFT', 'PENDING_REVIEW', 'REJECTED', 'APPROVED', 'CANCELLED')),
    proposed_profile jsonb NOT NULL,
    submitted_at timestamptz,
    reviewed_at timestamptz,
    reviewed_by uuid,
    reason_code varchar(64),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL,
    FOREIGN KEY (specialist_account_id, base_published_version)
        REFERENCES consultation.specialist_profile_approved_version(specialist_account_id, published_version)
);

ALTER TABLE consultation.specialist_profile_approved_version ADD FOREIGN KEY (source_amendment_id)
    REFERENCES consultation.specialist_profile_amendment(id);

CREATE TABLE consultation.specialist_profile_amendment_history (
    id uuid PRIMARY KEY,
    amendment_id uuid NOT NULL REFERENCES consultation.specialist_profile_amendment(id),
    amendment_version bigint NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('DRAFT', 'PENDING_REVIEW', 'REJECTED', 'APPROVED', 'CANCELLED')),
    proposed_profile jsonb NOT NULL,
    actor_account_id uuid NOT NULL,
    actor_role varchar(16) NOT NULL,
    reason_code varchar(64),
    occurred_at timestamptz NOT NULL,
    UNIQUE (amendment_id, amendment_version)
);

CREATE TABLE consultation.subscription_plan (
    code varchar(16) PRIMARY KEY,
    display_name varchar(64) NOT NULL,
    sort_order integer NOT NULL UNIQUE
);

CREATE TABLE consultation.subscription_plan_version (
    id uuid PRIMARY KEY,
    plan_code varchar(16) NOT NULL REFERENCES consultation.subscription_plan(code),
    version varchar(64) NOT NULL,
    pricing_policy_version varchar(64) NOT NULL DEFAULT 'upgrade-offset-v1',
    currency char(3) NOT NULL,
    price_minor bigint NOT NULL,
    billing_period_months integer NOT NULL,
    consultation_credits_per_period integer NOT NULL,
    max_active_reservations integer NOT NULL,
    non_consultation_value_minor bigint NOT NULL,
    credit_value_minor bigint NOT NULL,
    specialist_share_bps integer NOT NULL,
    ai_quota_code varchar(64) NOT NULL,
    support_plan_enabled boolean NOT NULL,
    effective_from timestamptz NOT NULL,
    retired_at timestamptz,
    created_at timestamptz NOT NULL,
    UNIQUE (plan_code, version)
);

CREATE TABLE consultation.subscription_plan_entitlement (
    plan_version_id uuid NOT NULL REFERENCES consultation.subscription_plan_version(id),
    entitlement_code varchar(64) NOT NULL,
    PRIMARY KEY (plan_version_id, entitlement_code)
);

CREATE TABLE consultation.user_subscription (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL, -- external -> identity.account.id
    plan_version_id uuid NOT NULL REFERENCES consultation.subscription_plan_version(id),
    status varchar(24) NOT NULL,
    current_period_start timestamptz,
    current_period_end timestamptz,
    ended_at timestamptz,
    idempotency_key varchar(128) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL,
    UNIQUE (user_id, idempotency_key)
);

CREATE TABLE consultation.subscription_status_history (
    id uuid PRIMARY KEY,
    subscription_id uuid NOT NULL REFERENCES consultation.user_subscription(id),
    from_status varchar(24),
    to_status varchar(24) NOT NULL,
    reason_code varchar(64) NOT NULL,
    changed_at timestamptz NOT NULL
);

CREATE TABLE consultation.payment_transaction (
    id uuid PRIMARY KEY,
    subscription_id uuid NOT NULL REFERENCES consultation.user_subscription(id),
    target_plan_version_id uuid NOT NULL REFERENCES consultation.subscription_plan_version(id),
    payment_provider varchar(16) NOT NULL,
    momo_order_id varchar(128) NOT NULL,
    momo_request_id varchar(128) NOT NULL,
    momo_trans_id bigint,
    amount_minor bigint NOT NULL,
    currency char(3) NOT NULL,
    status varchar(16) NOT NULL,
    purpose varchar(16) NOT NULL,
    momo_result_code integer,
    momo_pay_type varchar(32),
    idempotency_key varchar(128) NOT NULL,
    momo_response_time_epoch_ms bigint,
    provider_occurred_at timestamptz,
    paid_at timestamptz,
    failed_at timestamptz,
    expires_at timestamptz NOT NULL,
    checkout_url varchar(2048),
    qr_code_url varchar(2048),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL,
    UNIQUE (payment_provider, momo_order_id),
    UNIQUE (payment_provider, momo_request_id),
    UNIQUE (payment_provider, momo_trans_id),
    CHECK (checkout_url IS NOT NULL OR qr_code_url IS NULL),
    UNIQUE (subscription_id, idempotency_key)
);

CREATE TABLE consultation.momo_payment_ipn (
    id uuid PRIMARY KEY,
    payment_id uuid REFERENCES consultation.payment_transaction(id),
    contract_version varchar(32) NOT NULL,
    deduplication_key char(64) NOT NULL UNIQUE,
    partner_code varchar(64) NOT NULL,
    order_id varchar(128) NOT NULL,
    request_id varchar(128) NOT NULL,
    amount_minor bigint NOT NULL,
    currency char(3) NOT NULL,
    order_info_sha256 char(64) NOT NULL,
    order_type varchar(64) NOT NULL,
    trans_id bigint NOT NULL,
    result_code integer NOT NULL,
    result_message varchar(256) NOT NULL,
    pay_type varchar(32) NOT NULL,
    response_time_epoch_ms bigint NOT NULL,
    extra_data_sha256 char(64) NOT NULL,
    provider_occurred_at timestamptz NOT NULL,
    safe_optional_details jsonb NOT NULL,
    payload_sha256 char(64) NOT NULL,
    signature_key_version varchar(32) NOT NULL,
    signature_verified_at timestamptz NOT NULL,
    processing_status varchar(32) NOT NULL,
    failure_code varchar(64),
    received_at timestamptz NOT NULL,
    processed_at timestamptz,
    acknowledged_at timestamptz
);

CREATE TABLE consultation.subscription_upgrade (
    id uuid PRIMARY KEY,
    subscription_id uuid NOT NULL REFERENCES consultation.user_subscription(id),
    from_plan_version_id uuid NOT NULL REFERENCES consultation.subscription_plan_version(id),
    to_plan_version_id uuid NOT NULL REFERENCES consultation.subscription_plan_version(id),
    payment_id uuid NOT NULL UNIQUE REFERENCES consultation.payment_transaction(id),
    pricing_policy_version varchar(64) NOT NULL DEFAULT 'upgrade-offset-v1',
    old_period_start timestamptz NOT NULL,
    old_period_end timestamptz NOT NULL,
    total_period_seconds bigint NOT NULL,
    remaining_period_seconds bigint NOT NULL,
    remaining_feature_value_minor bigint NOT NULL,
    available_credit_value_minor bigint NOT NULL,
    offset_minor bigint NOT NULL,
    amount_due_minor bigint NOT NULL,
    currency char(3) NOT NULL,
    status varchar(24) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    quoted_at timestamptz NOT NULL,
    quote_expires_at timestamptz NOT NULL,
    applied_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL,
    UNIQUE (subscription_id, idempotency_key)
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
    credit_policy_version varchar(64) NOT NULL,
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

CREATE TABLE consultation.subscription_upgrade_credit (
    upgrade_id uuid NOT NULL REFERENCES consultation.subscription_upgrade(id),
    credit_id uuid NOT NULL UNIQUE REFERENCES consultation.service_credit(id),
    allocated_value_minor bigint NOT NULL,
    PRIMARY KEY (upgrade_id, credit_id)
);

CREATE TABLE consultation.service_credit_ledger (
    id uuid PRIMARY KEY,
    credit_id uuid NOT NULL REFERENCES consultation.service_credit(id),
    account_id uuid NOT NULL, -- external -> identity.account.id
    event_type varchar(32) NOT NULL,
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
    replaces_appointment_id uuid REFERENCES consultation.appointment(id),
    originating_plan_action_id uuid, -- opaque external -> care.plan_action/action projection; no FK or lifecycle authority
    decided_at timestamptz,
    decision_reason varchar(64),
    cancellation_reason varchar(64),
    cancelled_at timestamptz,
    cancelled_by uuid, -- external -> identity.account.id
    cancellation_credit_outcome varchar(40),
    session_outcome varchar(40),
    session_outcome_reason varchar(64),
    session_policy_version varchar(64),
    session_ended_at timestamptz,
    session_settled_at timestamptz,
    evidence_review_started_at timestamptz,
    evidence_failure_reason varchar(64),
    completion_fact_id uuid UNIQUE,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL,
    UNIQUE (user_account_id, idempotency_key),
    CONSTRAINT ck_appointment_deadline CHECK (
        decision_deadline_at > requested_at AND decision_deadline_at <= scheduled_end_at
    )
);

CREATE TABLE consultation.appointment_status_history (
    id uuid PRIMARY KEY,
    appointment_id uuid NOT NULL REFERENCES consultation.appointment(id),
    from_status varchar(24),
    to_status varchar(24) NOT NULL,
    changed_by uuid, -- external -> identity.account.id
    reason varchar(64),
    idempotency_key varchar(128),
    credit_outcome varchar(40),
    changed_at timestamptz NOT NULL
);

CREATE TABLE consultation.appointment_video_room (
    appointment_id uuid PRIMARY KEY REFERENCES consultation.appointment(id),
    room_name varchar(64) NOT NULL UNIQUE,
    user_identity varchar(64) NOT NULL,
    specialist_identity varchar(64) NOT NULL,
    state varchar(16) NOT NULL,
    failure_reason varchar(64),
    next_close_at timestamptz,
    next_verification_at timestamptz,
    closed_at timestamptz,
    created_at timestamptz NOT NULL,
    retention_hold_reason varchar(64),
    version bigint NOT NULL
);

CREATE TABLE consultation.appointment_video_confirmation (
    appointment_id uuid PRIMARY KEY REFERENCES consultation.appointment(id),
    id uuid NOT NULL UNIQUE,
    confirmed_by uuid NOT NULL, -- external -> identity.account.id
    source_version bigint NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    accepted_at timestamptz NOT NULL,
    policy_version varchar(64) NOT NULL,
    UNIQUE (confirmed_by, idempotency_key),
    UNIQUE (appointment_id, id)
);

ALTER TABLE consultation.appointment ADD COLUMN video_confirmation_id uuid;
ALTER TABLE consultation.appointment ADD FOREIGN KEY(id,video_confirmation_id)
    REFERENCES consultation.appointment_video_confirmation(appointment_id,id);

CREATE TABLE consultation.appointment_video_event (
    event_id varchar(128) PRIMARY KEY,
    appointment_id uuid NOT NULL REFERENCES consultation.appointment_video_room(appointment_id),
    event_type varchar(32) NOT NULL,
    room_sid varchar(128) NOT NULL,
    participant_identity varchar(64),
    participant_sid varchar(128),
    participant_role varchar(16),
    occurred_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL
);

CREATE TABLE consultation.appointment_chat_evidence (
    id uuid PRIMARY KEY,
    appointment_id uuid NOT NULL REFERENCES consultation.appointment(id),
    evidence_id uuid NOT NULL,
    participant_account_id uuid NOT NULL, -- external -> identity.account.id
    participant_role varchar(16) NOT NULL,
    evidence_type varchar(24) NOT NULL,
    interval_started_at timestamptz,
    message_id uuid, -- Realtime-owned identifier; no message content crosses the boundary
    occurred_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL,
    UNIQUE (appointment_id, evidence_id)
);

CREATE TABLE consultation.appointment_dispute (
    id uuid PRIMARY KEY,
    appointment_id uuid NOT NULL UNIQUE REFERENCES consultation.appointment(id),
    appointment_version bigint NOT NULL,
    opened_by_account_id uuid NOT NULL, -- external -> identity.account.id
    opened_by_role varchar(16) NOT NULL,
    reason_code varchar(64) NOT NULL,
    evidence_type varchar(48),
    evidence_occurred_at timestamptz,
    opened_at timestamptz NOT NULL,
    eligible_until timestamptz NOT NULL,
    status varchar(16) NOT NULL,
    resolution_outcome varchar(48),
    resolution_reason varchar(64),
    resolved_by uuid, -- external -> identity.account.id
    resolved_at timestamptz,
    prior_appointment_status varchar(24),
    prior_session_outcome varchar(40),
    resulting_appointment_status varchar(24),
    resulting_session_outcome varchar(40),
    credit_action varchar(32),
    open_idempotency_key varchar(128) NOT NULL,
    resolution_idempotency_key varchar(128),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL,
    UNIQUE (opened_by_account_id, open_idempotency_key),
    UNIQUE (resolved_by, resolution_idempotency_key)
);

CREATE TABLE consultation.appointment_rating (
    appointment_id uuid PRIMARY KEY REFERENCES consultation.appointment(id),
    user_account_id uuid NOT NULL, -- external -> identity.account.id
    specialist_account_id uuid NOT NULL REFERENCES consultation.specialist_profile(account_id),
    rating smallint NOT NULL,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE TABLE consultation.specialist_rating_aggregate (
    specialist_account_id uuid PRIMARY KEY REFERENCES consultation.specialist_profile(account_id),
    rating_count bigint NOT NULL,
    rating_sum bigint NOT NULL,
    version bigint NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE TABLE consultation.specialist_earning (
    id uuid PRIMARY KEY,
    appointment_id uuid NOT NULL UNIQUE REFERENCES consultation.appointment(id),
    completion_fact_id uuid NOT NULL UNIQUE,
    consumed_credit_id uuid NOT NULL UNIQUE REFERENCES consultation.service_credit(id),
    specialist_account_id uuid NOT NULL REFERENCES consultation.specialist_profile(account_id),
    plan_version varchar(64) NOT NULL,
    currency char(3) NOT NULL,
    credit_allocation_minor bigint NOT NULL,
    specialist_share_bps integer NOT NULL,
    specialist_amount_minor bigint NOT NULL,
    platform_allocation_minor bigint NOT NULL,
    idempotency_source varchar(128) NOT NULL UNIQUE,
    status varchar(24) NOT NULL,
    earned_at timestamptz NOT NULL,
    settlement_available_at timestamptz NOT NULL,
    paid_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL
);

CREATE TABLE consultation.specialist_payout_destination (
    id uuid PRIMARY KEY,
    specialist_account_id uuid NOT NULL REFERENCES consultation.specialist_profile(account_id),
    payout_provider varchar(16) NOT NULL,
    destination_type varchar(24) NOT NULL,
    destination_ciphertext text NOT NULL,
    encryption_key_version varchar(32) NOT NULL,
    destination_fingerprint char(64) NOT NULL,
    display_hint varchar(64) NOT NULL,
    status varchar(16) NOT NULL,
    verified_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL,
    UNIQUE (specialist_account_id, destination_fingerprint)
);

CREATE TABLE consultation.specialist_payout (
    id uuid PRIMARY KEY,
    specialist_account_id uuid NOT NULL REFERENCES consultation.specialist_profile(account_id),
    destination_id uuid NOT NULL REFERENCES consultation.specialist_payout_destination(id),
    currency char(3) NOT NULL,
    amount_minor bigint NOT NULL,
    payout_provider varchar(16) NOT NULL,
    status varchar(16) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    requested_on date NOT NULL,
    requested_at timestamptz NOT NULL,
    completed_at timestamptz,
    last_failure_code varchar(64),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL,
    UNIQUE (specialist_account_id, idempotency_key),
    UNIQUE (specialist_account_id, requested_on)
);

CREATE TABLE consultation.specialist_payout_attempt (
    id uuid PRIMARY KEY,
    payout_id uuid NOT NULL REFERENCES consultation.specialist_payout(id),
    payout_provider varchar(16) NOT NULL,
    attempt_number integer NOT NULL,
    provider_idempotency_key varchar(128) NOT NULL,
    provider_payout_reference varchar(128),
    status varchar(16) NOT NULL,
    requested_at timestamptz NOT NULL,
    provider_confirmed_at timestamptz,
    failed_at timestamptz,
    failure_code varchar(64),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL,
    UNIQUE (payout_id, attempt_number),
    UNIQUE (payout_provider, provider_idempotency_key),
    UNIQUE (payout_provider, provider_payout_reference)
);

CREATE TABLE consultation.specialist_payout_item (
    payout_id uuid NOT NULL REFERENCES consultation.specialist_payout(id),
    earning_id uuid NOT NULL UNIQUE REFERENCES consultation.specialist_earning(id),
    amount_minor bigint NOT NULL,
    PRIMARY KEY (payout_id, earning_id)
);

CREATE TABLE consultation.specialist_payout_status_history (
    id uuid PRIMARY KEY,
    payout_id uuid NOT NULL REFERENCES consultation.specialist_payout(id),
    from_status varchar(16),
    to_status varchar(16) NOT NULL,
    reason_code varchar(64) NOT NULL,
    changed_at timestamptz NOT NULL
);

CREATE TABLE consultation.payout_provider_event (
    id uuid PRIMARY KEY,
    payout_attempt_id uuid REFERENCES consultation.specialist_payout_attempt(id),
    payout_provider varchar(16) NOT NULL,
    provider_event_id varchar(128) NOT NULL,
    event_type varchar(32) NOT NULL,
    payload_sha256 char(64) NOT NULL,
    processing_status varchar(24) NOT NULL,
    failure_code varchar(64),
    received_at timestamptz NOT NULL,
    processed_at timestamptz,
    UNIQUE (payout_provider, provider_event_id)
);

CREATE TABLE consultation.specialist_client_continuity_audit (
    id uuid PRIMARY KEY,
    specialist_account_id uuid NOT NULL, -- external -> identity.account.id
    action varchar(16) NOT NULL,
    outcome varchar(16) NOT NULL,
    reason_code varchar(64) NOT NULL,
    relationship_count integer NOT NULL,
    occurred_at timestamptz NOT NULL
);

CREATE TABLE consultation.session_summary (
    id uuid PRIMARY KEY,
    appointment_id uuid NOT NULL REFERENCES consultation.appointment(id),
    user_account_id uuid NOT NULL, -- external -> identity.account.id
    specialist_account_id uuid NOT NULL, -- external -> identity.account.id
    summary_version bigint NOT NULL,
    schema_version varchar(40) NOT NULL,
    topics_discussed jsonb NOT NULL,
    progress_summary varchar(1000),
    specialist_note_for_user varchar(1000),
    follow_up_suggested boolean NOT NULL,
    amends_summary_id uuid REFERENCES consultation.session_summary(id),
    idempotency_key varchar(128) NOT NULL,
    request_hash varchar(64) NOT NULL,
    published_at timestamptz NOT NULL,
    UNIQUE (appointment_id, summary_version),
    UNIQUE (specialist_account_id, idempotency_key)
);

CREATE TABLE consultation.agreed_next_step (
    id uuid PRIMARY KEY,
    summary_id uuid NOT NULL REFERENCES consultation.session_summary(id),
    ordinal smallint NOT NULL,
    step_type varchar(32) NOT NULL,
    title varchar(160) NOT NULL,
    details varchar(500),
    resource_id uuid, -- external -> content.resource.id
    resource_version varchar(64),
    resource_proposal_reason_code varchar(48),
    created_at timestamptz NOT NULL,
    UNIQUE (summary_id, ordinal)
);

CREATE TABLE consultation.agreed_next_step_state (
    next_step_id uuid PRIMARY KEY REFERENCES consultation.agreed_next_step(id),
    user_account_id uuid NOT NULL, -- external -> identity.account.id
    state varchar(16) NOT NULL,
    hidden boolean NOT NULL,
    version bigint NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE TABLE consultation.session_summary_reuse_consent (
    summary_id uuid PRIMARY KEY REFERENCES consultation.session_summary(id),
    user_account_id uuid NOT NULL, -- external -> identity.account.id
    approved boolean NOT NULL,
    version bigint NOT NULL,
    updated_at timestamptz NOT NULL,
    approved_at timestamptz,
    revoked_at timestamptz
);

CREATE TABLE consultation.appointment_outbox_event (
    id uuid PRIMARY KEY,
    appointment_id uuid NOT NULL REFERENCES consultation.appointment(id),
    appointment_version bigint NOT NULL,
    correlation_id uuid NOT NULL,
    payload jsonb NOT NULL,
    occurred_at timestamptz NOT NULL,
    published_at timestamptz,
    attempt_count integer NOT NULL,
    next_attempt_at timestamptz,
    created_at timestamptz NOT NULL,
    UNIQUE (appointment_id, appointment_version)
);

CREATE TABLE consultation.consultation_admin_audit_outbox (
    id uuid PRIMARY KEY,
    deduplication_key varchar(200) NOT NULL UNIQUE,
    event_type varchar(120) NOT NULL,
    correlation_id uuid NOT NULL,
    target_account_id uuid,
    payload jsonb NOT NULL,
    occurred_at timestamptz NOT NULL,
    published_at timestamptz,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    created_at timestamptz NOT NULL
);
