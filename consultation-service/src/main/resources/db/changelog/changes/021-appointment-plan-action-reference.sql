--liquibase formatted sql

--changeset mentalbridge:consultation-021-appointment-plan-action-reference
alter table appointment
    add column originating_plan_action_id uuid;

create index ix_appointment_user_plan_action_schedule
    on appointment (user_account_id, originating_plan_action_id, scheduled_start_at desc, id desc)
    where originating_plan_action_id is not null;
