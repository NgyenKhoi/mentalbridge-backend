--liquibase formatted sql

--changeset mentalbridge:consultation-008-appointment-decisions
alter table appointment
    add column decided_at timestamptz,
    add column decision_reason varchar(64);

update appointment
set decided_at = updated_at,
    decision_reason = case status
        when 'CONFIRMED' then 'SPECIALIST_ACCEPTED'
        when 'IN_PROGRESS' then 'SPECIALIST_ACCEPTED'
        when 'REJECTED' then 'SPECIALIST_REJECTED'
        when 'EXPIRED' then 'DECISION_DEADLINE_EXPIRED'
    end
where status in ('CONFIRMED', 'IN_PROGRESS', 'REJECTED', 'EXPIRED');

alter table appointment
    add constraint ck_appointment_decision check (
        (status = 'REQUESTED' and decided_at is null and decision_reason is null)
        or (status in ('CONFIRMED', 'IN_PROGRESS')
            and decided_at is not null and decision_reason = 'SPECIALIST_ACCEPTED')
        or (status = 'REJECTED'
            and decided_at is not null and decision_reason = 'SPECIALIST_REJECTED')
        or (status = 'EXPIRED'
            and decided_at is not null and decision_reason = 'DECISION_DEADLINE_EXPIRED')
        or (status = 'CANCELLED' and (
            (decided_at is null and decision_reason is null)
            or (decided_at is not null and decision_reason = 'SPECIALIST_ACCEPTED')
        ))
    );

alter table appointment_status_history
    add column idempotency_key varchar(128),
    add constraint ck_appointment_history_key check (
        idempotency_key is null
        or (length(idempotency_key) between 16 and 128 and idempotency_key !~ '[^!-~]')
    );

create unique index uq_appointment_history_command
    on appointment_status_history (appointment_id, idempotency_key)
    where idempotency_key is not null;

create index ix_appointment_request_expiry
    on appointment (decision_deadline_at, id)
    where status = 'REQUESTED';
