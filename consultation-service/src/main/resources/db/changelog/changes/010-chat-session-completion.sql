--liquibase formatted sql

--changeset mentalbridge:consultation-010-chat-session-completion
alter table appointment
    add column session_outcome varchar(40),
    add column session_outcome_reason varchar(64),
    add column session_policy_version varchar(64),
    add column session_ended_at timestamptz,
    add column session_settled_at timestamptz,
    add column evidence_review_started_at timestamptz,
    add column evidence_failure_reason varchar(64),
    add column completion_fact_id uuid;

alter table appointment
    drop constraint ck_appointment_status,
    add constraint ck_appointment_status check (status in (
        'REQUESTED', 'CONFIRMED', 'IN_PROGRESS', 'SESSION_ENDED', 'COMPLETED',
        'REJECTED', 'EXPIRED', 'CANCELLED'
    )),
    drop constraint ck_appointment_decision,
    add constraint ck_appointment_decision check (
        (status = 'REQUESTED' and decided_at is null and decision_reason is null)
        or (status in ('CONFIRMED', 'IN_PROGRESS', 'SESSION_ENDED', 'COMPLETED')
            and decided_at is not null and decision_reason = 'SPECIALIST_ACCEPTED')
        or (status = 'REJECTED'
            and decided_at is not null and decision_reason = 'SPECIALIST_REJECTED')
        or (status = 'EXPIRED'
            and decided_at is not null and decision_reason = 'DECISION_DEADLINE_EXPIRED')
        or (status = 'CANCELLED' and (
            (decided_at is null and decision_reason is null)
            or (decided_at is not null and decision_reason = 'SPECIALIST_ACCEPTED')
        ))
    ),
    add constraint ck_appointment_session_outcome check (
        session_outcome is null or session_outcome in (
            'COMPLETED', 'USER_NO_SHOW', 'SPECIALIST_NO_SHOW', 'BOTH_NO_SHOW',
            'INSUFFICIENT_EVIDENCE', 'EVIDENCE_REVIEW'
        )
    ),
    add constraint ck_appointment_session_state check (
        (status not in ('SESSION_ENDED', 'COMPLETED')
            and session_outcome is null and session_outcome_reason is null
            and session_policy_version is null and session_ended_at is null
            and session_settled_at is null and evidence_review_started_at is null
            and completion_fact_id is null)
        or (status = 'SESSION_ENDED'
            and session_policy_version = 'chat-session-completion-v1'
            and session_ended_at is not null
            and (session_outcome is null or session_outcome <> 'COMPLETED')
            and completion_fact_id is null
            and ((session_outcome is null and session_settled_at is null)
                 or (session_outcome is not null and session_settled_at is not null)))
        or (status = 'COMPLETED'
            and session_outcome = 'COMPLETED'
            and session_outcome_reason = 'EVIDENCE_REQUIREMENTS_MET'
            and session_policy_version = 'chat-session-completion-v1'
            and session_ended_at is not null and session_settled_at is not null
            and completion_fact_id is not null)
    ),
    add constraint uq_appointment_completion_fact unique (completion_fact_id);

alter table appointment_status_history
    drop constraint ck_appointment_history_status,
    add constraint ck_appointment_history_status check (
        (from_status is null or from_status in (
            'REQUESTED', 'CONFIRMED', 'IN_PROGRESS', 'SESSION_ENDED', 'COMPLETED',
            'REJECTED', 'EXPIRED', 'CANCELLED'
        )) and to_status in (
            'REQUESTED', 'CONFIRMED', 'IN_PROGRESS', 'SESSION_ENDED', 'COMPLETED',
            'REJECTED', 'EXPIRED', 'CANCELLED'
        )
    );

create table appointment_chat_evidence (
    id uuid primary key,
    appointment_id uuid not null references appointment(id),
    evidence_id uuid not null,
    participant_account_id uuid not null,
    participant_role varchar(16) not null,
    evidence_type varchar(24) not null,
    interval_started_at timestamptz,
    message_id uuid,
    occurred_at timestamptz not null,
    received_at timestamptz not null,
    constraint uq_appointment_chat_evidence unique (appointment_id, evidence_id),
    constraint ck_appointment_chat_evidence_role check (participant_role in ('USER', 'SPECIALIST')),
    constraint ck_appointment_chat_evidence_type check (
        evidence_type in ('CHECK_IN', 'PRESENCE_INTERVAL', 'ACCEPTED_MESSAGE')
    ),
    constraint ck_appointment_chat_evidence_shape check (
        (evidence_type = 'CHECK_IN' and interval_started_at is null and message_id is null)
        or (evidence_type = 'PRESENCE_INTERVAL' and interval_started_at is not null
            and interval_started_at < occurred_at and message_id is null
            and occurred_at - interval_started_at <= interval '60 seconds')
        or (evidence_type = 'ACCEPTED_MESSAGE' and interval_started_at is null and message_id is not null)
    )
);

create index ix_appointment_chat_evidence_evaluation
    on appointment_chat_evidence (appointment_id, participant_role, evidence_type, occurred_at);

create index ix_appointment_chat_end_due
    on appointment (scheduled_end_at, id)
    where status in ('CONFIRMED', 'IN_PROGRESS');

create index ix_appointment_chat_settlement_due
    on appointment (scheduled_end_at, id)
    where status = 'SESSION_ENDED' and session_outcome is null;
