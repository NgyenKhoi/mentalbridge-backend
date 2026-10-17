--liquibase formatted sql

--changeset mentalbridge:care-016-support-plan-lifecycle-history runInTransaction:true
ALTER TABLE support_plan
    ADD COLUMN completion_reason varchar(32),
    ADD CONSTRAINT ck_support_plan_completion_reason CHECK (
        (status = 'COMPLETED' AND completion_reason IN ('USER_DECISION','PLAN_NO_LONGER_FITS','OTHER'))
        OR completion_reason IS NULL
    );

CREATE INDEX ix_support_plan_owner_terminal_history
    ON support_plan (user_id, updated_at DESC, id DESC)
    WHERE status IN ('COMPLETED','SUPERSEDED','DISCARDED');
