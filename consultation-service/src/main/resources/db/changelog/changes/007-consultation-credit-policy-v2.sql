--liquibase formatted sql

--changeset mentalbridge:consultation-007-consultation-credit-policy-v2
alter table service_credit_period
    add column credit_policy_version varchar(64);

update service_credit_period
set credit_policy_version = 'consultation-credit-v1';

alter table service_credit_period
    alter column credit_policy_version set not null,
    drop constraint ck_service_credit_period_allocation,
    add constraint ck_service_credit_period_policy
        check (credit_policy_version in ('consultation-credit-v1', 'consultation-credit-v2')),
    add constraint ck_service_credit_period_allocation check (
        (credit_policy_version = 'consultation-credit-v1' and package_code = 'PLUS' and allocated_count = 1)
        or (credit_policy_version = 'consultation-credit-v1' and package_code = 'PREMIUM' and allocated_count = 3)
        or (credit_policy_version = 'consultation-credit-v2' and package_code = 'PLUS' and allocated_count = 4)
        or (credit_policy_version = 'consultation-credit-v2' and package_code = 'PREMIUM' and allocated_count = 10)
    );

alter table service_credit
    drop constraint ck_service_credit_ordinal,
    add constraint ck_service_credit_ordinal check (ordinal between 1 and 10);

alter table appointment
    add column replaces_appointment_id uuid references appointment(id),
    drop constraint ck_appointment_status,
    add constraint ck_appointment_status
        check (status in ('REQUESTED', 'CONFIRMED', 'IN_PROGRESS', 'REJECTED', 'EXPIRED', 'CANCELLED')),
    add constraint ck_appointment_not_self_replacement
        check (replaces_appointment_id is null or replaces_appointment_id <> id);

drop index uq_appointment_active_slot;
create unique index uq_appointment_active_slot
    on appointment (availability_slot_id)
    where status in ('REQUESTED', 'CONFIRMED', 'IN_PROGRESS');

drop index uq_appointment_active_credit;
create unique index uq_appointment_active_credit
    on appointment (service_credit_id)
    where status in ('REQUESTED', 'CONFIRMED', 'IN_PROGRESS');

create unique index uq_appointment_replacement
    on appointment (replaces_appointment_id)
    where replaces_appointment_id is not null;
