--liquibase formatted sql

--changeset mentalbridge:consultation-005-online-appointment-request
create table appointment (
    id uuid primary key,
    user_account_id uuid not null,
    specialist_account_id uuid not null references specialist_profile(account_id),
    availability_slot_id uuid not null references availability_slot(id),
    service_credit_id uuid not null references service_credit(id),
    status varchar(24) not null,
    modality varchar(24) not null,
    scheduled_start_at timestamptz not null,
    scheduled_end_at timestamptz not null,
    display_timezone varchar(64) not null,
    requested_at timestamptz not null,
    decision_deadline_at timestamptz not null,
    idempotency_key varchar(128) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0,
    constraint uq_appointment_user_command unique (user_account_id, idempotency_key),
    constraint ck_appointment_status check (status in ('REQUESTED', 'CONFIRMED', 'REJECTED', 'EXPIRED', 'CANCELLED')),
    constraint ck_appointment_modality check (modality in ('IN_APP_CHAT', 'IN_APP_VIDEO')),
    constraint ck_appointment_duration check (scheduled_end_at = scheduled_start_at + interval '60 minutes'),
    constraint ck_appointment_deadline check (
        decision_deadline_at > requested_at and decision_deadline_at <= scheduled_start_at
    ),
    constraint ck_appointment_key check (
        length(idempotency_key) between 16 and 128 and idempotency_key !~ '[^!-~]'
    )
);

create unique index uq_appointment_active_slot
    on appointment (availability_slot_id)
    where status in ('REQUESTED', 'CONFIRMED');

create unique index uq_appointment_active_credit
    on appointment (service_credit_id)
    where status in ('REQUESTED', 'CONFIRMED');

create index ix_appointment_user_schedule
    on appointment (user_account_id, scheduled_start_at desc, id desc);
