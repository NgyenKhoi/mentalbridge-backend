--liquibase formatted sql

--changeset mentalbridge:consultation-027-immediate-appointment-booking
alter table appointment
    drop constraint ck_appointment_deadline,
    add constraint ck_appointment_deadline check (
        decision_deadline_at > requested_at and decision_deadline_at <= scheduled_end_at
    );
