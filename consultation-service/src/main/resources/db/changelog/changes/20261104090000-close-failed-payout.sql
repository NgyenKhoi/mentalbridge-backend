--liquibase formatted sql

--changeset mentalbridge:consultation-20261104090000-close-failed-payout
alter table specialist_payout
    add column closed_at timestamptz,
    add column closed_by uuid,
    add constraint ck_specialist_payout_closed check (
        (closed_at is null and closed_by is null)
        or (closed_at is not null and closed_by is not null and status = 'FAILED')
    );

alter table specialist_payout_item
    add column released_at timestamptz,
    drop constraint uq_specialist_payout_item_earning;

create unique index uq_specialist_payout_item_active_earning
    on specialist_payout_item (earning_id) where released_at is null;

create table payout_close_command (
    id uuid primary key,
    actor_account_id uuid not null,
    idempotency_key varchar(128) not null,
    payout_id uuid not null references specialist_payout(id),
    expected_attempt_id uuid not null,
    reason_code varchar(32) not null,
    created_at timestamptz not null,
    constraint uq_payout_close_command unique (actor_account_id, idempotency_key),
    constraint ck_payout_close_key check (length(idempotency_key) between 16 and 128),
    constraint ck_payout_close_reason check (reason_code = 'DESTINATION_UNUSABLE'),
    constraint fk_payout_close_expected_attempt foreign key (payout_id, expected_attempt_id)
        references specialist_payout_attempt(payout_id, id)
);
