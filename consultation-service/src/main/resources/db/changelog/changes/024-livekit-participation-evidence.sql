--liquibase formatted sql

--changeset mentalbridge:consultation-024-livekit-participation-evidence
create table appointment_video_event (
    event_id varchar(128) primary key,
    appointment_id uuid not null references appointment_video_room(appointment_id),
    event_type varchar(32) not null,
    room_sid varchar(128) not null,
    participant_identity varchar(64),
    participant_sid varchar(128),
    participant_role varchar(16),
    occurred_at timestamptz not null,
    received_at timestamptz not null,
    constraint ck_video_event_type check (event_type in ('participant_joined','participant_left','room_finished')),
    constraint ck_video_event_participant check (
        (event_type='room_finished' and participant_identity is null and participant_sid is null and participant_role is null)
        or (event_type<>'room_finished' and participant_identity is not null and participant_sid is not null and participant_role in ('USER','SPECIALIST'))
    )
);
create index ix_video_event_session on appointment_video_event(appointment_id,room_sid,participant_sid,occurred_at);
create index ix_video_event_retention on appointment_video_event(received_at,appointment_id);
