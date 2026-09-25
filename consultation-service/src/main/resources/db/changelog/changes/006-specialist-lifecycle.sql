--liquibase formatted sql

--changeset mentalbridge:consultation-006-specialist-lifecycle
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

alter table appointment add column cancellation_reason varchar(64);
alter table appointment add column cancelled_at timestamptz;

alter table appointment add constraint ck_appointment_cancellation check (
    (status = 'CANCELLED' and cancelled_at is not null and cancellation_reason is not null)
    or (status <> 'CANCELLED' and cancelled_at is null and cancellation_reason is null)
);

create index ix_appointment_specialist_future
    on appointment (specialist_account_id, scheduled_start_at, id)
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
            'REQUESTED', 'CONFIRMED', 'REJECTED', 'EXPIRED', 'CANCELLED'
        )) and to_status in (
            'REQUESTED', 'CONFIRMED', 'REJECTED', 'EXPIRED', 'CANCELLED'
        )
    )
);

create index ix_appointment_history_timeline
    on appointment_status_history (appointment_id, changed_at, id);
