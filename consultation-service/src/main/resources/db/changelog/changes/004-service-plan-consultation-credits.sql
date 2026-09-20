--liquibase formatted sql

--changeset mentalbridge:consultation-004-service-plan-consultation-credits
create table service_credit_period (
    id uuid primary key,
    account_id uuid not null,
    plan_version varchar(64) not null,
    package_code varchar(16) not null,
    source varchar(16) not null,
    source_reference varchar(128) not null,
    period_start timestamptz not null,
    period_end timestamptz not null,
    allocated_count integer not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0,
    constraint uq_service_credit_period unique (account_id, plan_version, period_start, period_end),
    constraint ck_service_credit_period_package check (package_code in ('PLUS', 'PREMIUM')),
    constraint ck_service_credit_period_source check (source in ('DEMO', 'PAID')),
    constraint ck_service_credit_period_reference check (btrim(source_reference) <> ''),
    constraint ck_service_credit_period_window check (period_end > period_start),
    constraint ck_service_credit_period_allocation check (
        (package_code = 'PLUS' and allocated_count = 1)
        or (package_code = 'PREMIUM' and allocated_count = 3)
    )
);

create index ix_service_credit_period_account_history
    on service_credit_period (account_id, period_start desc, id);

create table service_credit (
    id uuid primary key,
    period_id uuid not null references service_credit_period(id),
    ordinal integer not null,
    state varchar(16) not null,
    appointment_id uuid,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0,
    constraint uq_service_credit_ordinal unique (period_id, ordinal),
    constraint ck_service_credit_ordinal check (ordinal between 1 and 3),
    constraint ck_service_credit_state check (state in ('AVAILABLE', 'HELD', 'CONSUMED', 'FORFEITED')),
    constraint ck_service_credit_appointment check (
        (state = 'AVAILABLE' and appointment_id is null)
        or (state in ('HELD', 'CONSUMED', 'FORFEITED') and appointment_id is not null)
    )
);

create index ix_service_credit_period_state
    on service_credit (period_id, state, ordinal);

create table service_credit_ledger (
    id uuid primary key,
    credit_id uuid not null references service_credit(id),
    account_id uuid not null,
    event_type varchar(16) not null,
    appointment_id uuid,
    idempotency_key varchar(128) not null,
    occurred_at timestamptz not null,
    constraint uq_service_credit_ledger_command unique (account_id, idempotency_key),
    constraint ck_service_credit_ledger_event check (event_type in ('PROVISIONED', 'HELD', 'CONSUMED', 'RELEASED', 'FORFEITED')),
    constraint ck_service_credit_ledger_key check (char_length(idempotency_key) between 16 and 128),
    constraint ck_service_credit_ledger_appointment check (
        (event_type = 'PROVISIONED' and appointment_id is null)
        or (event_type <> 'PROVISIONED' and appointment_id is not null)
    )
);

create index ix_service_credit_ledger_account_history
    on service_credit_ledger (account_id, occurred_at desc, id desc);
