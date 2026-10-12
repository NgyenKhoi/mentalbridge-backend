--liquibase formatted sql

--changeset mentalbridge:care-028-support-plan-cadence runInTransaction:true
ALTER TABLE support_plan_activity_schedule
    DROP CONSTRAINT ck_support_plan_activity_schedule_version,
    DROP CONSTRAINT ck_support_plan_activity_schedule_status,
    DROP CONSTRAINT ck_support_plan_activity_schedule_recurrence,
    DROP CONSTRAINT ck_support_plan_activity_schedule_recurrence_v3,
    DROP CONSTRAINT ck_support_plan_activity_schedule_source,
    ADD COLUMN recurrence_days_mask smallint,
    ADD COLUMN preferred_daypart varchar(16),
    ADD COLUMN estimated_duration_minutes smallint,
    ADD CONSTRAINT ck_support_plan_activity_schedule_version CHECK (
        schedule_version >= 1 AND source_plan_version >= 0
    ),
    ADD CONSTRAINT ck_support_plan_activity_schedule_status CHECK (
        status IN ('DRAFT','ACTIVE','PAUSED','ENDED')
    ),
    ADD CONSTRAINT ck_support_plan_activity_schedule_source CHECK (
        (source_action_id IS NULL AND source_action_version IS NULL AND source_action_type IS NULL
            AND source_slot_key IS NOT NULL AND source_resource_id IS NOT NULL
            AND source_content_version IS NOT NULL AND source_content_version >= 0
            AND ordinal BETWEEN 1 AND 5
            AND (schedule_policy_version IS NULL
                OR length(btrim(schedule_policy_version)) BETWEEN 1 AND 128))
        OR (source_action_id IS NOT NULL AND source_action_version IS NOT NULL
            AND source_action_version >= 0 AND source_action_type IS NOT NULL
            AND source_slot_key IS NULL AND source_resource_id IS NULL
            AND source_content_version IS NULL AND ordinal BETWEEN 1 AND 20
            AND length(btrim(schedule_policy_version)) BETWEEN 1 AND 128)
    ),
    ADD CONSTRAINT ck_support_plan_activity_schedule_recurrence_v3 CHECK (
        (source_action_id IS NULL AND schedule_policy_version IS NULL
            AND recurrence_type IN ('DAILY','WEEKLY'))
        OR (schedule_policy_version IS NOT NULL
            AND recurrence_type IN ('ONCE','DAILY','WEEKLY'))
    ),
    ADD CONSTRAINT ck_support_plan_activity_schedule_recurrence CHECK (
        (recurrence_type IN ('ONCE','DAILY')
            AND recurrence_day_of_week IS NULL AND recurrence_days_mask IS NULL)
        OR (recurrence_type = 'WEEKLY' AND (
            (recurrence_day_of_week BETWEEN 1 AND 7 AND recurrence_days_mask IS NULL)
            OR (recurrence_day_of_week IS NULL AND recurrence_days_mask BETWEEN 1 AND 127)
        ))
    ),
    ADD CONSTRAINT ck_support_plan_activity_schedule_workload CHECK (
        (schedule_policy_version IS DISTINCT FROM 'support-plan-activity-schedule-v2'
            AND recurrence_days_mask IS NULL
            AND preferred_daypart IS NULL
            AND estimated_duration_minutes IS NULL)
        OR (schedule_policy_version = 'support-plan-activity-schedule-v2'
            AND (preferred_daypart IS NULL OR preferred_daypart IN (
                'MORNING','AFTERNOON','EVENING','FLEXIBLE'
            ))
            AND estimated_duration_minutes BETWEEN 5 AND 180
            AND estimated_duration_minutes % 5 = 0)
    );

CREATE UNIQUE INDEX ux_support_plan_activity_schedule_current_slot
    ON support_plan_activity_schedule (support_plan_id, source_slot_key)
    WHERE source_slot_key IS NOT NULL AND status IN ('DRAFT','ACTIVE','PAUSED');

CREATE UNIQUE INDEX ux_support_plan_activity_schedule_current_action
    ON support_plan_activity_schedule (support_plan_id, source_action_id)
    WHERE source_action_id IS NOT NULL AND status IN ('DRAFT','ACTIVE','PAUSED');

ALTER TABLE support_plan_activity_occurrence
    DROP CONSTRAINT ck_support_plan_activity_occurrence_reason,
    ADD CONSTRAINT ck_support_plan_activity_occurrence_reason CHECK (
        (state = 'CANCELLED' AND state_reason IN (
            'PLAN_PAUSED','PLAN_COMPLETED','PLAN_REPLACED','ACTION_ENDED','SCHEDULE_CHANGED'
        ))
        OR (state <> 'CANCELLED' AND state_reason IS NULL)
    );

CREATE TABLE support_plan_schedule_command (
    user_id uuid NOT NULL REFERENCES user_profile(account_id) ON DELETE RESTRICT,
    idempotency_key varchar(128) NOT NULL,
    request_hash char(64) NOT NULL,
    support_plan_id uuid NOT NULL,
    action_id uuid NOT NULL,
    expected_plan_version bigint NOT NULL,
    resulting_plan_version bigint NOT NULL,
    resulting_schedule_id uuid,
    resulting_schedule_version integer,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, idempotency_key),
    CONSTRAINT fk_support_plan_schedule_command_owner
        FOREIGN KEY (support_plan_id, user_id)
        REFERENCES support_plan(id, user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_support_plan_schedule_command_hash CHECK (
        request_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_support_plan_schedule_command_versions CHECK (
        expected_plan_version >= 0
        AND resulting_plan_version >= expected_plan_version
        AND (resulting_schedule_version IS NULL OR resulting_schedule_version >= 1)
        AND ((resulting_schedule_id IS NULL AND resulting_schedule_version IS NULL)
            OR (resulting_schedule_id IS NOT NULL AND resulting_schedule_version IS NOT NULL))
    )
);

CREATE INDEX ix_support_plan_schedule_command_plan
    ON support_plan_schedule_command (support_plan_id, created_at, idempotency_key);
