--liquibase formatted sql

--changeset mentalbridge:consultation-005-specialist-lifecycle
alter table specialist_profile add constraint ck_specialist_decision_reason_code check (
    decision_reason_code is null or decision_reason_code in (
        'PROFILE_INFORMATION_INCOMPLETE',
        'PROFILE_CONTENT_NOT_APPROVED',
        'OUTSIDE_SUPPORTED_SCOPE',
        'POLICY_VIOLATION',
        'QUALITY_REVIEW_REQUIRED',
        'ACCOUNT_REVIEW_REQUIRED'
    )
);

alter table specialist_profile add constraint ck_specialist_reason_matches_status check (
    approval_status in ('PENDING', 'APPROVED') and decision_reason_code is null
    or approval_status = 'REJECTED' and decision_reason_code in (
        'PROFILE_INFORMATION_INCOMPLETE',
        'PROFILE_CONTENT_NOT_APPROVED',
        'OUTSIDE_SUPPORTED_SCOPE'
    )
    or approval_status = 'SUSPENDED' and decision_reason_code in (
        'POLICY_VIOLATION',
        'QUALITY_REVIEW_REQUIRED',
        'ACCOUNT_REVIEW_REQUIRED'
    )
);

alter table specialist_profile_status_history add constraint ck_specialist_history_reason_code check (
    reason_code is null or reason_code in (
        'PROFILE_INFORMATION_INCOMPLETE',
        'PROFILE_CONTENT_NOT_APPROVED',
        'OUTSIDE_SUPPORTED_SCOPE',
        'POLICY_VIOLATION',
        'QUALITY_REVIEW_REQUIRED',
        'ACCOUNT_REVIEW_REQUIRED'
    )
);

alter table specialist_profile_status_history add constraint ck_specialist_history_reason_matches_status check (
    approval_status in ('PENDING', 'APPROVED') and reason_code is null
    or approval_status = 'REJECTED' and reason_code in (
        'PROFILE_INFORMATION_INCOMPLETE',
        'PROFILE_CONTENT_NOT_APPROVED',
        'OUTSIDE_SUPPORTED_SCOPE'
    )
    or approval_status = 'SUSPENDED' and reason_code in (
        'POLICY_VIOLATION',
        'QUALITY_REVIEW_REQUIRED',
        'ACCOUNT_REVIEW_REQUIRED'
    )
);

alter table availability_slot add constraint uq_availability_slot_owner
    unique (id, specialist_account_id);

create table appointment (
    id uuid primary key,
    slot_id uuid not null,
    credit_id uuid not null references service_credit(id),
    user_id uuid not null,
    specialist_id uuid not null references specialist_profile(account_id),
    status varchar(24) not null,
    scheduled_start_at timestamptz not null,
    scheduled_end_at timestamptz not null,
    scheduled_timezone varchar(64) not null,
    channel varchar(24) not null,
    user_timezone varchar(64) not null,
    response_deadline timestamptz not null,
    idempotency_key varchar(128) not null,
    cancellation_reason varchar(64),
    requested_at timestamptz not null,
    confirmed_at timestamptz,
    completed_at timestamptz,
    cancelled_at timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0,
    constraint fk_appointment_owned_slot foreign key (slot_id, specialist_id)
        references availability_slot(id, specialist_account_id),
    constraint uq_appointment_request unique (user_id, idempotency_key),
    constraint ck_appointment_duration check (
        scheduled_end_at = scheduled_start_at + interval '60 minutes'
    ),
    constraint ck_appointment_channel check (channel in ('IN_APP_CHAT', 'IN_APP_VIDEO')),
    constraint ck_appointment_status check (status in (
        'REQUESTED', 'CONFIRMED', 'IN_PROGRESS', 'SESSION_ENDED', 'COMPLETED',
        'REJECTED', 'EXPIRED', 'CANCELLED', 'USER_NO_SHOW',
        'SPECIALIST_NO_SHOW', 'DISPUTED'
    )),
    constraint ck_appointment_deadline check (
        response_deadline > requested_at and response_deadline < scheduled_start_at
    ),
    constraint ck_appointment_idempotency_key check (
        length(idempotency_key) between 16 and 128 and idempotency_key !~ '[^!-~]'
    ),
    constraint ck_appointment_cancellation check (
        (status = 'CANCELLED' and cancelled_at is not null and cancellation_reason is not null)
        or (status <> 'CANCELLED' and cancelled_at is null and cancellation_reason is null)
    )
);

create unique index uq_appointment_active_slot
    on appointment (slot_id)
    where status in ('REQUESTED', 'CONFIRMED', 'IN_PROGRESS', 'SESSION_ENDED', 'DISPUTED');

create index ix_appointment_specialist_future
    on appointment (specialist_id, scheduled_start_at, id)
    where status in ('REQUESTED', 'CONFIRMED');

create table appointment_status_history (
    id uuid primary key,
    appointment_id uuid not null references appointment(id),
    from_status varchar(24),
    to_status varchar(24) not null,
    changed_by uuid,
    reason varchar(64),
    changed_at timestamptz not null,
    constraint ck_appointment_history_status check (
        (from_status is null or from_status in (
            'REQUESTED', 'CONFIRMED', 'IN_PROGRESS', 'SESSION_ENDED', 'COMPLETED',
            'REJECTED', 'EXPIRED', 'CANCELLED', 'USER_NO_SHOW',
            'SPECIALIST_NO_SHOW', 'DISPUTED'
        )) and to_status in (
            'REQUESTED', 'CONFIRMED', 'IN_PROGRESS', 'SESSION_ENDED', 'COMPLETED',
            'REJECTED', 'EXPIRED', 'CANCELLED', 'USER_NO_SHOW',
            'SPECIALIST_NO_SHOW', 'DISPUTED'
        )
    )
);

create index ix_appointment_history_timeline
    on appointment_status_history (appointment_id, changed_at, id);
