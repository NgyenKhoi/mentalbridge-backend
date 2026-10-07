--liquibase formatted sql

--changeset mentalbridge:consultation-016-appointment-disputes
alter table service_credit_ledger
    alter column event_type type varchar(32),
    drop constraint ck_service_credit_ledger_event,
    add constraint ck_service_credit_ledger_event check (
        event_type in ('PROVISIONED', 'HELD', 'CONSUMED', 'RELEASED', 'FORFEITED', 'ADJUSTED_RELEASED')
    );

create table appointment_dispute (
    id uuid primary key,
    appointment_id uuid not null references appointment(id),
    appointment_version bigint not null,
    opened_by_account_id uuid not null,
    opened_by_role varchar(16) not null,
    reason_code varchar(64) not null,
    evidence_type varchar(48),
    evidence_occurred_at timestamptz,
    opened_at timestamptz not null,
    eligible_until timestamptz not null,
    status varchar(16) not null,
    resolution_outcome varchar(48),
    resolution_reason varchar(64),
    resolved_by uuid,
    resolved_at timestamptz,
    prior_appointment_status varchar(24),
    prior_session_outcome varchar(40),
    resulting_appointment_status varchar(24),
    resulting_session_outcome varchar(40),
    credit_action varchar(32),
    open_idempotency_key varchar(128) not null,
    resolution_idempotency_key varchar(128),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0,
    constraint uq_appointment_dispute_appointment unique (appointment_id),
    constraint uq_appointment_dispute_open_command unique (opened_by_account_id, open_idempotency_key),
    constraint uq_appointment_dispute_resolution_command unique (resolved_by, resolution_idempotency_key),
    constraint ck_appointment_dispute_role check (opened_by_role in ('USER', 'SPECIALIST')),
    constraint ck_appointment_dispute_reason check (reason_code in (
        'OUTCOME_INCORRECT', 'PARTICIPATION_EVIDENCE_INCORRECT',
        'SESSION_DELIVERY_NOT_RECOGNIZED', 'TECHNICAL_FAILURE'
    )),
    constraint ck_appointment_dispute_evidence check (
        (evidence_type is null and evidence_occurred_at is null)
        or (evidence_type in ('ACCESS_LOG', 'CONNECTION_INCIDENT', 'PROVIDER_INCIDENT')
            and evidence_occurred_at is not null)
    ),
    constraint ck_appointment_dispute_window check (eligible_until >= opened_at),
    constraint ck_appointment_dispute_status check (status in ('OPEN', 'RESOLVED')),
    constraint ck_appointment_dispute_resolution check (
        (status = 'OPEN' and resolution_outcome is null and resolution_reason is null
            and resolved_by is null and resolved_at is null and prior_appointment_status is null
            and prior_session_outcome is null and resulting_appointment_status is null
            and resulting_session_outcome is null and credit_action is null
            and resolution_idempotency_key is null)
        or
        (status = 'RESOLVED' and resolution_outcome in ('UPHOLD_RECORDED_OUTCOME', 'RELEASE_USER_CREDIT')
            and resolution_reason is not null and resolved_by is not null and resolved_at is not null
            and prior_appointment_status is not null and prior_session_outcome is not null
            and resulting_appointment_status is not null and resulting_session_outcome is not null
            and credit_action in ('NONE', 'ALREADY_AVAILABLE', 'ADJUSTED_RELEASED')
            and resolution_idempotency_key is not null)
    ),
    constraint ck_appointment_dispute_open_key check (char_length(open_idempotency_key) between 16 and 128),
    constraint ck_appointment_dispute_resolution_key check (
        resolution_idempotency_key is null or char_length(resolution_idempotency_key) between 16 and 128
    )
);

create index ix_appointment_dispute_admin_queue
    on appointment_dispute (status, opened_at, id);
