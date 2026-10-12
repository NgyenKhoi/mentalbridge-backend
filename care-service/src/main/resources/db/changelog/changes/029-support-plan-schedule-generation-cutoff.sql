--liquibase formatted sql

--changeset mentalbridge:care-030-support-plan-schedule-generation-cutoff runInTransaction:true
ALTER TABLE support_plan_activity_schedule
    ADD COLUMN occurrence_generation_until date;

UPDATE support_plan_activity_schedule
SET occurrence_generation_until = effective_until
WHERE status = 'ENDED';
