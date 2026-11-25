--liquibase formatted sql

--changeset mentalbridge:consultation-025-livekit-video-completion
create table appointment_video_confirmation (
    appointment_id uuid primary key references appointment(id),
    id uuid not null unique,
    confirmed_by uuid not null,
    source_version bigint not null,
    idempotency_key varchar(128) not null,
    accepted_at timestamptz not null,
    policy_version varchar(64) not null,
    constraint uq_video_confirmation_command unique(confirmed_by,idempotency_key),
    constraint uq_video_confirmation_owner unique(appointment_id,id),
    constraint ck_video_confirmation_key check(char_length(idempotency_key) between 16 and 128),
    constraint ck_video_confirmation_version check(source_version>=0),
    constraint ck_video_confirmation_policy check(policy_version='livekit-video-completion-v1')
);
alter table appointment add column video_confirmation_id uuid references appointment_video_confirmation(id);
alter table appointment add constraint fk_video_confirmation_owner foreign key(id,video_confirmation_id)
    references appointment_video_confirmation(appointment_id,id);
alter table appointment drop constraint ck_appointment_session_state;
alter table appointment add constraint ck_appointment_session_state check (
    (status not in ('SESSION_ENDED', 'COMPLETED')
        and session_outcome is null and session_outcome_reason is null
        and session_policy_version is null and session_ended_at is null
        and session_settled_at is null and evidence_review_started_at is null
        and completion_fact_id is null and video_confirmation_id is null)
    or (status = 'SESSION_ENDED'
        and session_policy_version = 'chat-session-completion-v1'
        and session_ended_at is not null
        and (session_outcome is null or session_outcome <> 'COMPLETED')
        and completion_fact_id is null and video_confirmation_id is null
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
        and completion_fact_id is not null and video_confirmation_id is null)
    or (status = 'COMPLETED' and modality = 'IN_APP_VIDEO'
        and session_outcome = 'COMPLETED'
        and session_outcome_reason = 'EVIDENCE_REQUIREMENTS_MET'
        and session_policy_version = 'livekit-video-completion-v1'
        and session_ended_at is not null and session_ended_at >= scheduled_start_at and session_ended_at <= scheduled_end_at
        and session_settled_at is not null and session_settled_at >= session_ended_at
        and completion_fact_id is not null and video_confirmation_id is not null)
);

create index ix_video_pending_verification on appointment(id)
    where modality='IN_APP_VIDEO' and status='SESSION_ENDED' and session_outcome is null;

alter table appointment_video_room add column next_verification_at timestamptz;
