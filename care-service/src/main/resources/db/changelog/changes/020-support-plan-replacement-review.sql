--liquibase formatted sql

--changeset mentalbridge:care-021-support-plan-replacement-review runInTransaction:true
ALTER TABLE reassessment_summary
    ADD CONSTRAINT ux_reassessment_summary_owner_reference UNIQUE (id, user_id);

ALTER TABLE support_plan_command
    DROP CONSTRAINT ck_support_plan_command_type,
    DROP CONSTRAINT ck_support_plan_command_status,
    ADD COLUMN source_support_plan_id uuid,
    ADD COLUMN source_support_plan_version bigint,
    ADD COLUMN reassessment_summary_id uuid,
    ADD COLUMN replacement_review_outcome varchar(64),
    ADD CONSTRAINT ck_support_plan_command_type CHECK (command_type IN ('ACTIVATE','REPLACE')),
    ADD CONSTRAINT ck_support_plan_command_status CHECK (resulting_status = 'ACTIVE'),
    ADD CONSTRAINT ck_support_plan_command_replacement CHECK (
        (command_type = 'ACTIVATE'
            AND source_support_plan_id IS NULL
            AND source_support_plan_version IS NULL
            AND reassessment_summary_id IS NULL
            AND replacement_review_outcome IS NULL)
        OR
        (command_type = 'REPLACE'
            AND source_support_plan_id IS NOT NULL
            AND source_support_plan_version >= 0
            AND reassessment_summary_id IS NOT NULL
            AND replacement_review_outcome IN (
                'CURRENT_PLAN_VALID_ALTERNATIVES_AVAILABLE',
                'CURRENT_PLAN_NOT_ADMISSIBLE'
            ))
    ),
    ADD CONSTRAINT fk_support_plan_command_source_owner
        FOREIGN KEY (source_support_plan_id, user_id)
        REFERENCES support_plan(id, user_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_support_plan_command_reassessment_owner
        FOREIGN KEY (reassessment_summary_id, user_id)
        REFERENCES reassessment_summary(id, user_id) ON DELETE RESTRICT;

CREATE UNIQUE INDEX ux_support_plan_replacement_target
    ON support_plan_command (support_plan_id)
    WHERE command_type = 'REPLACE';
