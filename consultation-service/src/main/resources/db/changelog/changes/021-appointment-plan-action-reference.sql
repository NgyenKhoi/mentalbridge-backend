--liquibase formatted sql

--changeset mentalbridge:consultation-021-appointment-plan-action-reference
alter table appointment
    add column originating_plan_action_id uuid;
