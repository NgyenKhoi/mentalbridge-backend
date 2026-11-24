--liquibase formatted sql

--changeset mentalbridge:care-038-review-due-occurrence-pause runInTransaction:true
ALTER TABLE support_plan_activity_occurrence
    DROP CONSTRAINT ck_support_plan_activity_occurrence_reason,
    ADD CONSTRAINT ck_support_plan_activity_occurrence_reason CHECK (
        (state = 'CANCELLED' AND state_reason IN (
            'PLAN_PAUSED','PLAN_COMPLETED','PLAN_REPLACED','ACTION_ENDED','SCHEDULE_CHANGED','REVIEW_DUE'
        ))
        OR (state <> 'CANCELLED' AND state_reason IS NULL)
    );
