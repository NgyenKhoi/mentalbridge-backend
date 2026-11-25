--liquibase formatted sql

--changeset mentalbridge:consultation-20261104080000-payos-specialist-payout
alter table specialist_payout_destination
    alter column verified_at drop not null,
    alter column status type varchar(24),
    drop constraint uq_specialist_payout_destination_fingerprint,
    drop constraint ck_specialist_payout_destination_provider,
    drop constraint ck_specialist_payout_destination_status,
    add column is_current boolean not null default false,
    add column confirmed_by uuid,
    add constraint ck_specialist_payout_destination_provider check (payout_provider in ('FAKE', 'MOMO', 'PAYOS')),
    add constraint ck_specialist_payout_destination_status
        check (status in ('PENDING_CONFIRMATION', 'VERIFIED', 'DISABLED')),
    add constraint ck_specialist_payout_destination_verification check (
        (status <> 'VERIFIED' or verified_at is not null)
        and (status <> 'PENDING_CONFIRMATION' or (verified_at is null and confirmed_by is null))
        and (payout_provider <> 'PAYOS' or status <> 'VERIFIED' or confirmed_by is not null)
    ),
    add constraint ck_specialist_payout_destination_payos_bank
        check (payout_provider <> 'PAYOS' or destination_type = 'BANK_ACCOUNT');

update specialist_payout_destination d set is_current=true
where d.id in (
    select distinct on (specialist_account_id) id
    from specialist_payout_destination order by specialist_account_id, updated_at desc, id desc
);

create unique index uq_specialist_payout_destination_current
    on specialist_payout_destination (specialist_account_id) where is_current;
create index ix_specialist_payout_destination_confirmation_queue
    on specialist_payout_destination (status, updated_at desc, id desc) where is_current;

alter table specialist_payout
    drop constraint ck_specialist_payout_provider,
    add constraint ck_specialist_payout_provider check (payout_provider in ('FAKE', 'MOMO', 'PAYOS')),
    add constraint uq_specialist_payout_provider_identity unique (id, payout_provider);

alter table specialist_payout_attempt
    drop constraint ck_specialist_payout_attempt_provider,
    add column provider_transfer_reference varchar(128),
    add column reconciliation_status varchar(16) not null default 'PENDING',
    add column next_reconciliation_at timestamptz,
    add column reconciliation_started_at timestamptz,
    add column reconciliation_deadline_at timestamptz,
    add column reconciliation_lease_until timestamptz,
    add column reconciliation_lease_token uuid,
    add column review_required_at timestamptz,
    add column reconciliation_count integer not null default 0,
    add constraint ck_specialist_payout_attempt_provider check (payout_provider in ('FAKE', 'MOMO', 'PAYOS')),
    add constraint fk_specialist_payout_attempt_provider foreign key (payout_id, payout_provider)
        references specialist_payout (id, payout_provider) not valid,
    add constraint uq_specialist_payout_transfer_reference unique (payout_provider, provider_transfer_reference),
    add constraint uq_specialist_payout_attempt_identity unique (payout_id, id),
    add constraint ck_specialist_payout_reconciliation_status
        check (reconciliation_status in ('PENDING', 'POLLING', 'NEEDS_REVIEW', 'COMPLETE')),
    add constraint ck_specialist_payout_reconciliation_count check (reconciliation_count >= 0),
    add constraint ck_specialist_payout_reconciliation_horizon check (
        (reconciliation_started_at is null and reconciliation_deadline_at is null)
        or (reconciliation_started_at is not null and reconciliation_deadline_at is not null and reconciliation_deadline_at =
            reconciliation_started_at + interval '24 hours')
    ),
    add constraint ck_specialist_payout_payos_key check (
        payout_provider <> 'PAYOS'
        or provider_idempotency_key ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    );

update specialist_payout_attempt set reconciliation_status='COMPLETE' where status in ('SUCCEEDED', 'FAILED');

create index ix_specialist_payout_reconciliation_due
    on specialist_payout_attempt (next_reconciliation_at, id)
    where reconciliation_status in ('PENDING', 'POLLING');

alter table payout_provider_event
    drop constraint ck_payout_provider_event_provider,
    add constraint ck_payout_provider_event_provider check (payout_provider in ('FAKE', 'MOMO', 'PAYOS'));

create table payout_destination_confirmation_command (
    id uuid primary key,
    actor_account_id uuid not null,
    idempotency_key varchar(128) not null,
    destination_id uuid not null references specialist_payout_destination(id),
    expected_version bigint not null,
    reason_code varchar(32) not null,
    result_version bigint not null,
    created_at timestamptz not null,
    constraint uq_payout_destination_confirmation_command unique (actor_account_id, idempotency_key),
    constraint ck_payout_destination_confirmation_key check (length(idempotency_key) between 16 and 128),
    constraint ck_payout_destination_confirmation_version check (expected_version >= 0 and result_version=expected_version+1),
    constraint ck_payout_destination_confirmation_reason check (reason_code='ACCOUNT_HOLDER_CHECKED')
);

create table payout_retry_command (
    id uuid primary key,
    actor_account_id uuid not null,
    idempotency_key varchar(128) not null,
    payout_id uuid not null references specialist_payout(id),
    expected_attempt_id uuid not null,
    result_attempt_id uuid not null,
    created_at timestamptz not null,
    constraint uq_payout_retry_command unique (actor_account_id, idempotency_key),
    constraint ck_payout_retry_key check (length(idempotency_key) between 16 and 128),
    constraint ck_payout_retry_distinct_attempt check (expected_attempt_id <> result_attempt_id),
    constraint fk_payout_retry_expected_attempt foreign key (payout_id, expected_attempt_id)
        references specialist_payout_attempt(payout_id, id),
    constraint fk_payout_retry_result_attempt foreign key (payout_id, result_attempt_id)
        references specialist_payout_attempt(payout_id, id)
);
