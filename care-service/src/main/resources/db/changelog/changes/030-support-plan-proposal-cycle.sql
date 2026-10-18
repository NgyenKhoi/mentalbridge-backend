--liquibase formatted sql

--changeset mentalbridge:care-031-support-plan-proposal-cycle runInTransaction:true
ALTER TABLE support_plan
    ADD COLUMN cycle_timezone varchar(64),
    ADD COLUMN cycle_number integer,
    ADD COLUMN cycle_started_on date,
    ADD COLUMN cycle_paused_days integer,
    ADD COLUMN cycle_paused_on date,
    ADD CONSTRAINT ck_support_plan_cycle CHECK ((
        (cycle_timezone IS NULL AND cycle_number IS NULL AND cycle_started_on IS NULL
            AND cycle_paused_days IS NULL AND cycle_paused_on IS NULL
            AND (plan_policy_version IS DISTINCT FROM 'support-plan-proposal-v1'
                OR status IN ('DRAFT','DISCARDED')))
        OR (model_version = 3 AND plan_policy_version = 'support-plan-proposal-v1'
            AND cycle_timezone IS NOT NULL AND length(btrim(cycle_timezone)) BETWEEN 1 AND 64
            AND cycle_number IS NOT NULL AND cycle_number >= 1
            AND cycle_paused_days IS NOT NULL AND cycle_paused_days >= 0
            AND ((status IN ('DRAFT','DISCARDED') AND cycle_started_on IS NULL AND cycle_paused_on IS NULL)
                OR (status IN ('ACTIVE','COMPLETED','SUPERSEDED')
                    AND cycle_started_on IS NOT NULL AND cycle_paused_on IS NULL)
                OR (status = 'PAUSED' AND cycle_started_on IS NOT NULL
                    AND cycle_paused_on IS NOT NULL AND cycle_paused_on >= cycle_started_on)))
    ) IS TRUE);

ALTER TABLE support_plan_command
    DROP CONSTRAINT ck_support_plan_command_type,
    DROP CONSTRAINT ck_support_plan_command_replacement,
    ADD CONSTRAINT ck_support_plan_command_type CHECK (command_type IN ('ACTIVATE','REPLACE','CYCLE_CONTINUE')),
    ADD CONSTRAINT ck_support_plan_command_replacement CHECK (
        (command_type IN ('ACTIVATE','CYCLE_CONTINUE')
            AND source_support_plan_id IS NULL AND source_support_plan_version IS NULL
            AND reassessment_summary_id IS NULL AND plan_change_request_id IS NULL
            AND replacement_review_outcome IS NULL)
        OR (command_type = 'REPLACE'
            AND source_support_plan_id IS NOT NULL AND source_support_plan_version >= 0
            AND ((reassessment_summary_id IS NOT NULL AND plan_change_request_id IS NULL
                    AND replacement_review_outcome IN (
                        'CURRENT_PLAN_VALID_ALTERNATIVES_AVAILABLE','CURRENT_PLAN_NOT_ADMISSIBLE'))
                OR (reassessment_summary_id IS NULL AND plan_change_request_id IS NOT NULL
                    AND replacement_review_outcome = 'SPECIALIST_PROPOSAL_ACCEPTED')))
    );
