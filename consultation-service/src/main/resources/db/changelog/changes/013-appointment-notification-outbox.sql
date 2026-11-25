--liquibase formatted sql

--changeset mentalbridge:consultation-013-appointment-notification-outbox
create table appointment_outbox_event (
    id uuid primary key,
    appointment_id uuid not null references appointment(id),
    appointment_version bigint not null,
    correlation_id uuid not null,
    payload jsonb not null,
    occurred_at timestamptz not null,
    published_at timestamptz,
    attempt_count integer not null default 0,
    next_attempt_at timestamptz,
    created_at timestamptz not null,
    constraint uq_appointment_outbox_version unique (appointment_id, appointment_version),
    constraint ck_appointment_outbox_version check (appointment_version >= 0),
    constraint ck_appointment_outbox_attempts check (attempt_count >= 0),
    constraint ck_appointment_outbox_payload check (jsonb_typeof(payload) = 'object')
);

create index ix_appointment_outbox_pending
    on appointment_outbox_event (coalesce(next_attempt_at, occurred_at), occurred_at, id)
    where published_at is null;
