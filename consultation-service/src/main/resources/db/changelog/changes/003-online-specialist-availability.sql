--liquibase formatted sql

--changeset mentalbridge:consultation-003-online-specialist-availability
create extension if not exists btree_gist;

create table availability_slot (
    id uuid primary key,
    specialist_account_id uuid not null references specialist_profile(account_id),
    start_at timestamptz not null,
    end_at timestamptz not null,
    timezone varchar(64) not null,
    modality varchar(24) not null,
    status varchar(16) not null default 'ACTIVE',
    idempotency_key varchar(128) not null,
    withdrawn_at timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0,
    constraint uq_availability_slot_idempotency unique (specialist_account_id, idempotency_key),
    constraint ck_availability_slot_duration check (end_at = start_at + interval '60 minutes'),
    constraint ck_availability_slot_timezone check (btrim(timezone) <> ''),
    constraint ck_availability_slot_modality check (modality in ('IN_APP_CHAT', 'IN_APP_VIDEO')),
    constraint ck_availability_slot_status check (status in ('ACTIVE', 'WITHDRAWN')),
    constraint ck_availability_slot_idempotency_key check (
        length(idempotency_key) between 16 and 128
        and idempotency_key !~ '[^!-~]'
    ),
    constraint ck_availability_slot_withdrawal check (
        (status = 'ACTIVE' and withdrawn_at is null)
        or (status = 'WITHDRAWN' and withdrawn_at is not null)
    )
);

alter table availability_slot add constraint ex_availability_slot_active_overlap
    exclude using gist (
        specialist_account_id with =,
        tstzrange(start_at, end_at, '[)') with &&
    ) where (status = 'ACTIVE');

create index idx_availability_slot_owner_start
    on availability_slot (specialist_account_id, start_at, id);
