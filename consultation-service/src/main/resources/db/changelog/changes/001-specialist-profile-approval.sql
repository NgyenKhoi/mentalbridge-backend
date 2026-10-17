--liquibase formatted sql

--changeset mentalbridge:consultation-001-specialist-profile-approval
create table specialist_profile (
    account_id uuid primary key,
    display_name varchar(120) not null,
    biography varchar(2000) not null,
    years_experience smallint not null,
    timezone varchar(64) not null,
    approval_status varchar(16) not null default 'PENDING',
    submitted_at timestamptz,
    reviewed_at timestamptz,
    reviewed_by uuid,
    decision_reason_code varchar(64),
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    version bigint not null default 0,
    constraint ck_specialist_years_experience check (years_experience between 0 and 80),
    constraint ck_specialist_timezone_not_blank check (btrim(timezone) <> ''),
    constraint ck_specialist_approval_status check (
        approval_status in ('PENDING', 'APPROVED', 'REJECTED', 'SUSPENDED')
    ),
    constraint ck_specialist_review_state check (
        (approval_status = 'PENDING' and reviewed_at is null and reviewed_by is null and decision_reason_code is null)
        or (approval_status = 'APPROVED' and submitted_at is not null and reviewed_at is not null and reviewed_by is not null and decision_reason_code is null)
        or (approval_status in ('REJECTED', 'SUSPENDED') and submitted_at is not null and reviewed_at is not null and reviewed_by is not null and decision_reason_code is not null)
    )
);

create table specialist_profile_support_area (
    specialist_account_id uuid not null references specialist_profile(account_id) on delete cascade,
    support_area varchar(40) not null,
    primary key (specialist_account_id, support_area),
    constraint ck_specialist_support_area check (
        support_area in ('DEPRESSIVE_SYMPTOMS', 'ANXIETY_SYMPTOMS')
    )
);

create table specialist_profile_language (
    specialist_account_id uuid not null references specialist_profile(account_id) on delete cascade,
    language_tag varchar(16) not null,
    primary key (specialist_account_id, language_tag),
    constraint ck_specialist_language_tag check (language_tag in ('vi', 'en'))
);

create table specialist_profile_status_history (
    id uuid primary key,
    specialist_account_id uuid not null references specialist_profile(account_id),
    approval_status varchar(16) not null,
    actor_account_id uuid not null,
    actor_role varchar(16) not null,
    reason_code varchar(64),
    occurred_at timestamptz not null,
    constraint ck_specialist_history_status check (
        approval_status in ('PENDING', 'APPROVED', 'REJECTED', 'SUSPENDED')
    ),
    constraint ck_specialist_history_actor_role check (actor_role in ('SPECIALIST', 'ADMIN')),
    constraint ck_specialist_history_reason check (
        (approval_status in ('PENDING', 'APPROVED') and reason_code is null)
        or (approval_status in ('REJECTED', 'SUSPENDED') and reason_code is not null)
    )
);

create index idx_specialist_profile_pending_submission
    on specialist_profile (submitted_at, account_id)
    where approval_status = 'PENDING' and submitted_at is not null;

create index idx_specialist_profile_history_account_time
    on specialist_profile_status_history (specialist_account_id, occurred_at desc, id desc);
