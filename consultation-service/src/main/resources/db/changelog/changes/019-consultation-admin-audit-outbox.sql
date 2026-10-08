--liquibase formatted sql

--changeset mentalbridge:consultation-019-consultation-admin-audit-outbox
create table consultation_admin_audit_outbox (
    id uuid primary key,
    deduplication_key varchar(200) not null unique,
    event_type varchar(120) not null,
    correlation_id uuid not null,
    target_account_id uuid,
    payload jsonb not null,
    occurred_at timestamptz not null,
    published_at timestamptz,
    attempt_count integer not null default 0,
    next_attempt_at timestamptz,
    created_at timestamptz not null,
    constraint ck_consultation_admin_audit_payload check (jsonb_typeof(payload) = 'object'),
    constraint ck_consultation_admin_audit_attempts check (attempt_count >= 0),
    constraint ck_consultation_admin_audit_publish_state check (published_at is null or next_attempt_at is null)
);

create index ix_consultation_admin_audit_due
    on consultation_admin_audit_outbox (coalesce(next_attempt_at, occurred_at), occurred_at, id)
    where published_at is null;
