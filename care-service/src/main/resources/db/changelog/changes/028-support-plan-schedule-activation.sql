--liquibase formatted sql

--changeset mentalbridge:care-029-support-plan-schedule-activation runInTransaction:true
ALTER TABLE support_plan_activity_schedule
    ADD COLUMN activated_at timestamptz;

UPDATE support_plan_activity_schedule schedule
SET activated_at = schedule.created_at
WHERE schedule.status IN ('ACTIVE','PAUSED')
    OR (schedule.status = 'ENDED' AND EXISTS (
        SELECT 1
        FROM support_plan_activity_occurrence occurrence
        WHERE occurrence.activity_schedule_id = schedule.id
    ));
