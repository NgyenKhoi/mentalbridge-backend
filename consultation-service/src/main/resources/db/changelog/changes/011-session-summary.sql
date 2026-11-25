--liquibase formatted sql

--changeset mentalbridge:consultation-011-session-summary
create table session_summary (
    id uuid primary key,
    appointment_id uuid not null references appointment(id),
    user_account_id uuid not null,
    specialist_account_id uuid not null,
    summary_version bigint not null,
    schema_version varchar(40) not null default 'session-summary-v1',
    topics_discussed jsonb not null,
    progress_summary varchar(1000),
    specialist_note_for_user varchar(1000),
    follow_up_suggested boolean not null,
    amends_summary_id uuid references session_summary(id),
    idempotency_key varchar(128) not null,
    request_hash varchar(64) not null,
    published_at timestamptz not null,
    constraint uq_session_summary_version unique (appointment_id, summary_version),
    constraint uq_session_summary_command unique (specialist_account_id, idempotency_key),
    constraint ck_session_summary_version check (summary_version > 0),
    constraint ck_session_summary_schema check (schema_version = 'session-summary-v1'),
    constraint ck_session_summary_topics check (
        jsonb_typeof(topics_discussed) = 'array'
        and jsonb_array_length(topics_discussed) between 1 and 8
    ),
    constraint ck_session_summary_amendment check (
        (summary_version = 1 and amends_summary_id is null)
        or (summary_version > 1 and amends_summary_id is not null)
    )
);

create table agreed_next_step (
    id uuid primary key,
    summary_id uuid not null references session_summary(id),
    ordinal smallint not null,
    step_type varchar(32) not null,
    title varchar(160) not null,
    details varchar(500),
    resource_id uuid,
    resource_version varchar(64),
    created_at timestamptz not null,
    constraint uq_agreed_next_step_ordinal unique (summary_id, ordinal),
    constraint ck_agreed_next_step_ordinal check (ordinal between 0 and 7),
    constraint ck_agreed_next_step_type check (step_type in (
        'CHECKLIST', 'JOURNAL', 'EMOTION_CHECK_IN', 'REASSESSMENT',
        'FOLLOW_UP_APPOINTMENT', 'PLATFORM_RESOURCE'
    )),
    constraint ck_agreed_next_step_resource check (
        (step_type = 'PLATFORM_RESOURCE' and resource_id is not null and resource_version is not null)
        or (step_type <> 'PLATFORM_RESOURCE' and resource_id is null and resource_version is null)
    )
);

create table agreed_next_step_state (
    next_step_id uuid primary key references agreed_next_step(id),
    user_account_id uuid not null,
    state varchar(16) not null default 'PENDING',
    hidden boolean not null default false,
    version bigint not null default 0,
    updated_at timestamptz not null,
    constraint ck_agreed_next_step_state check (state in ('PENDING', 'COMPLETED', 'SKIPPED')),
    constraint ck_agreed_next_step_state_version check (version >= 0)
);

create table session_summary_reuse_consent (
    summary_id uuid primary key references session_summary(id),
    user_account_id uuid not null,
    approved boolean not null default false,
    version bigint not null default 0,
    updated_at timestamptz not null,
    approved_at timestamptz,
    revoked_at timestamptz,
    constraint ck_session_summary_reuse_version check (version >= 0),
    constraint ck_session_summary_reuse_audit check (
        (approved and approved_at is not null and revoked_at is null)
        or (not approved and approved_at is null)
    )
);

create index ix_session_summary_appointment
    on session_summary (appointment_id, summary_version desc);

create index ix_session_summary_user
    on session_summary (user_account_id, published_at desc);
