--liquibase formatted sql

--changeset mentalbridge:consultation-023-livekit-video-room
create table appointment_video_room (
    appointment_id uuid primary key references appointment(id),
    room_name varchar(64) not null unique,
    user_identity varchar(64) not null,
    specialist_identity varchar(64) not null,
    state varchar(16) not null,
    failure_reason varchar(64),
    next_close_at timestamptz,
    closed_at timestamptz,
    created_at timestamptz not null,
    retention_hold_reason varchar(64),
    version bigint not null default 0,
    constraint ck_video_room_state check (state in ('PREPARING','OPEN','CLOSING','CLOSED','PURGED')),
    constraint ck_video_room_closed check (state not in ('CLOSED','PURGED') or closed_at is not null),
    constraint ck_video_room_identities check (user_identity <> specialist_identity)
);

alter table appointment drop constraint ck_appointment_session_state;
alter table appointment add constraint ck_appointment_session_state check (
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
    or (status = 'SESSION_ENDED' and modality = 'IN_APP_VIDEO'
        and session_policy_version = 'livekit-video-completion-v1'
        and session_ended_at is not null and session_outcome is null
        and session_outcome_reason is null and session_settled_at is null
        and completion_fact_id is null)
    or (status = 'COMPLETED'
        and session_outcome = 'COMPLETED'
        and session_outcome_reason = 'EVIDENCE_REQUIREMENTS_MET'
        and session_policy_version = 'chat-session-completion-v1'
        and session_ended_at is not null and session_settled_at is not null
        and completion_fact_id is not null)
);

create index ix_video_room_closure on appointment_video_room(next_close_at,appointment_id)
    where state in ('PREPARING','OPEN','CLOSING');
