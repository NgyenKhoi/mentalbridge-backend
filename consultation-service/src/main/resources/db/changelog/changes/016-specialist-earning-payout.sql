--liquibase formatted sql

--changeset mentalbridge:consultation-016-specialist-earning-payout
create table specialist_earning (
    id uuid primary key,
    appointment_id uuid not null references appointment(id),
    completion_fact_id uuid not null,
    consumed_credit_id uuid not null references service_credit(id),
    specialist_account_id uuid not null references specialist_profile(account_id),
    plan_version varchar(64) not null,
    currency char(3) not null,
    credit_allocation_minor bigint not null,
    specialist_share_bps integer not null,
    specialist_amount_minor bigint not null,
    platform_allocation_minor bigint not null,
    idempotency_source varchar(128) not null,
    status varchar(24) not null,
    earned_at timestamptz not null,
    settlement_available_at timestamptz not null,
    paid_at timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0,
    constraint uq_specialist_earning_appointment unique (appointment_id),
    constraint uq_specialist_earning_completion unique (completion_fact_id),
    constraint uq_specialist_earning_credit unique (consumed_credit_id),
    constraint uq_specialist_earning_source unique (idempotency_source),
    constraint ck_specialist_earning_currency check (currency = 'VND'),
    constraint ck_specialist_earning_values check (
        credit_allocation_minor = 300000
        and specialist_share_bps = 7000
        and specialist_amount_minor = 210000
        and platform_allocation_minor = 90000
        and specialist_amount_minor + platform_allocation_minor = credit_allocation_minor
    ),
    constraint ck_specialist_earning_status check (
        status in ('PENDING_SETTLEMENT', 'AVAILABLE', 'PROCESSING', 'PAID', 'REVERSED')
    ),
    constraint ck_specialist_earning_settlement check (settlement_available_at >= earned_at),
    constraint ck_specialist_earning_paid check (
        (status = 'PAID' and paid_at is not null) or (status <> 'PAID' and paid_at is null)
    )
);

create index ix_specialist_earning_owner_status
    on specialist_earning (specialist_account_id, status, settlement_available_at, id);

create table specialist_payout_destination (
    id uuid primary key,
    specialist_account_id uuid not null references specialist_profile(account_id),
    payout_provider varchar(16) not null,
    destination_type varchar(24) not null,
    destination_ciphertext text not null,
    encryption_key_version varchar(32) not null,
    destination_fingerprint char(64) not null,
    display_hint varchar(64) not null,
    status varchar(16) not null,
    verified_at timestamptz not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0,
    constraint uq_specialist_payout_destination_fingerprint unique (
        specialist_account_id, destination_fingerprint
    ),
    constraint ck_specialist_payout_destination_provider check (payout_provider in ('FAKE', 'MOMO')),
    constraint ck_specialist_payout_destination_type check (destination_type in ('MOMO_WALLET', 'BANK_ACCOUNT')),
    constraint ck_specialist_payout_destination_status check (status in ('VERIFIED', 'DISABLED')),
    constraint ck_specialist_payout_destination_hint check (btrim(display_hint) <> '')
);

create table specialist_payout (
    id uuid primary key,
    specialist_account_id uuid not null references specialist_profile(account_id),
    destination_id uuid not null references specialist_payout_destination(id),
    currency char(3) not null,
    amount_minor bigint not null,
    payout_provider varchar(16) not null,
    status varchar(16) not null,
    idempotency_key varchar(128) not null,
    requested_on date not null,
    requested_at timestamptz not null,
    completed_at timestamptz,
    last_failure_code varchar(64),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0,
    constraint uq_specialist_payout_command unique (specialist_account_id, idempotency_key),
    constraint uq_specialist_payout_day unique (specialist_account_id, requested_on),
    constraint ck_specialist_payout_currency check (currency = 'VND'),
    constraint ck_specialist_payout_amount check (amount_minor >= 100000),
    constraint ck_specialist_payout_provider check (payout_provider in ('FAKE', 'MOMO')),
    constraint ck_specialist_payout_status check (status in ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'UNKNOWN')),
    constraint ck_specialist_payout_completion check (
        (status = 'SUCCEEDED' and completed_at is not null) or (status <> 'SUCCEEDED' and completed_at is null)
    )
);

create index ix_specialist_payout_owner_history
    on specialist_payout (specialist_account_id, requested_at desc, id desc);

create table specialist_payout_attempt (
    id uuid primary key,
    payout_id uuid not null references specialist_payout(id),
    payout_provider varchar(16) not null,
    attempt_number integer not null,
    provider_idempotency_key varchar(128) not null,
    provider_payout_reference varchar(128),
    status varchar(16) not null,
    requested_at timestamptz not null,
    provider_confirmed_at timestamptz,
    failed_at timestamptz,
    failure_code varchar(64),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0,
    constraint uq_specialist_payout_attempt_number unique (payout_id, attempt_number),
    constraint uq_specialist_payout_attempt_key unique (payout_provider, provider_idempotency_key),
    constraint uq_specialist_payout_provider_reference unique (payout_provider, provider_payout_reference),
    constraint ck_specialist_payout_attempt_provider check (payout_provider in ('FAKE', 'MOMO')),
    constraint ck_specialist_payout_attempt_number check (attempt_number > 0),
    constraint ck_specialist_payout_attempt_status check (status in ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'UNKNOWN'))
);

create table specialist_payout_item (
    payout_id uuid not null references specialist_payout(id),
    earning_id uuid not null references specialist_earning(id),
    amount_minor bigint not null,
    primary key (payout_id, earning_id),
    constraint uq_specialist_payout_item_earning unique (earning_id),
    constraint ck_specialist_payout_item_amount check (amount_minor > 0)
);

create table specialist_payout_status_history (
    id uuid primary key,
    payout_id uuid not null references specialist_payout(id),
    from_status varchar(16),
    to_status varchar(16) not null,
    reason_code varchar(64) not null,
    changed_at timestamptz not null,
    constraint ck_specialist_payout_history_status check (
        (from_status is null or from_status in ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'UNKNOWN'))
        and to_status in ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'UNKNOWN')
    )
);

create index ix_specialist_payout_status_history
    on specialist_payout_status_history (payout_id, changed_at, id);

create table payout_provider_event (
    id uuid primary key,
    payout_attempt_id uuid references specialist_payout_attempt(id),
    payout_provider varchar(16) not null,
    provider_event_id varchar(128) not null,
    event_type varchar(32) not null,
    payload_sha256 char(64) not null,
    processing_status varchar(24) not null,
    failure_code varchar(64),
    received_at timestamptz not null,
    processed_at timestamptz,
    constraint uq_payout_provider_event unique (payout_provider, provider_event_id),
    constraint ck_payout_provider_event_provider check (payout_provider in ('FAKE', 'MOMO')),
    constraint ck_payout_provider_event_status check (
        processing_status in ('RECEIVED', 'PROCESSED', 'REJECTED', 'FAILED')
    )
);
