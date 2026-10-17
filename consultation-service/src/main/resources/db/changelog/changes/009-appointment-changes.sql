--liquibase formatted sql

--changeset mentalbridge:consultation-009-appointment-changes
alter table appointment
    add column cancelled_by uuid,
    add column cancellation_credit_outcome varchar(40);

alter table appointment_status_history
    add column credit_outcome varchar(40);

update appointment original
set cancellation_reason = 'USER_RESCHEDULED',
    cancelled_at = replacement.requested_at,
    cancelled_by = original.user_account_id,
    cancellation_credit_outcome = 'TRANSFERRED_TO_REPLACEMENT'
from appointment replacement
where replacement.replaces_appointment_id = original.id
  and original.status = 'CANCELLED';

update appointment cancelled
set cancelled_by = (
        select history.changed_by
        from appointment_status_history history
        where history.appointment_id = cancelled.id and history.to_status = 'CANCELLED'
        order by history.changed_at desc, history.id desc
        limit 1
    ),
    cancellation_credit_outcome = 'RELEASED'
where cancelled.status = 'CANCELLED'
  and cancelled.cancelled_by is null
  and cancelled.cancellation_reason = 'SPECIALIST_SUSPENDED'
  and exists (
      select 1 from appointment_status_history history
      where history.appointment_id = cancelled.id and history.to_status = 'CANCELLED'
        and history.changed_by is not null
  );

insert into appointment_status_history (
    id, appointment_id, from_status, to_status, changed_by, reason,
    idempotency_key, changed_at, credit_outcome
)
select gen_random_uuid(), appointment.id, null, 'REQUESTED', appointment.user_account_id,
       'APPOINTMENT_REQUESTED', 'request:' || appointment.id, appointment.requested_at, null
from appointment
where not exists (
    select 1 from appointment_status_history history
    where history.appointment_id = appointment.id and history.reason = 'APPOINTMENT_REQUESTED'
);

insert into appointment_status_history (
    id, appointment_id, from_status, to_status, changed_by, reason,
    idempotency_key, changed_at, credit_outcome
)
select gen_random_uuid(), original.id,
       case when original.decision_reason = 'SPECIALIST_ACCEPTED' then 'CONFIRMED' else 'REQUESTED' end,
       'CANCELLED', original.user_account_id,
       'USER_RESCHEDULED', 'reschedule:' || replacement.id, replacement.requested_at,
       'TRANSFERRED_TO_REPLACEMENT'
from appointment original
join appointment replacement on replacement.replaces_appointment_id = original.id
where original.status = 'CANCELLED'
  and not exists (
      select 1 from appointment_status_history history
      where history.appointment_id = original.id and history.reason = 'USER_RESCHEDULED'
  );

update appointment_status_history history
set credit_outcome = 'RELEASED'
where history.to_status = 'CANCELLED'
  and history.credit_outcome is null
  and history.reason = 'SPECIALIST_SUSPENDED';

alter table appointment drop constraint ck_appointment_cancellation;

alter table appointment add constraint ck_appointment_cancellation check (
    (status <> 'CANCELLED'
        and cancelled_at is null
        and cancellation_reason is null
        and cancelled_by is null
        and cancellation_credit_outcome is null)
    or (status = 'CANCELLED'
        and cancelled_at is not null
        and cancellation_reason is not null
        and cancellation_reason in (
            'USER_CANCELLED', 'USER_RESCHEDULED', 'SPECIALIST_SUSPENDED'
        )
        and cancelled_by is not null
        and cancellation_credit_outcome is not null
        and cancellation_credit_outcome in ('RELEASED', 'FORFEITED', 'TRANSFERRED_TO_REPLACEMENT'))
);

alter table appointment_status_history add constraint ck_appointment_history_credit_outcome check (
    (to_status = 'CANCELLED'
        and changed_by is not null
        and credit_outcome is not null
        and credit_outcome in ('RELEASED', 'FORFEITED', 'TRANSFERRED_TO_REPLACEMENT'))
    or (to_status <> 'CANCELLED' and credit_outcome is null)
);
