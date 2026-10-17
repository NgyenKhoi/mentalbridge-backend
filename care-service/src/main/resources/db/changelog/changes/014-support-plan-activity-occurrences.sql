--liquibase formatted sql

--changeset mentalbridge:care-015-support-plan-activity-occurrences runInTransaction:true
ALTER TABLE support_plan
    DROP CONSTRAINT ck_support_plan_status,
    ADD CONSTRAINT ck_support_plan_status CHECK (
        status IN ('DRAFT','ACTIVE','PAUSED','COMPLETED','SUPERSEDED','DISCARDED')
    ),
    ADD COLUMN completed_at timestamptz,
    ADD COLUMN superseded_at timestamptz,
    ADD COLUMN discarded_at timestamptz,
    ADD CONSTRAINT ck_support_plan_lifecycle_times CHECK (
        (status = 'DRAFT' AND activated_at IS NULL AND completed_at IS NULL
            AND superseded_at IS NULL AND discarded_at IS NULL)
        OR (status IN ('ACTIVE','PAUSED') AND activated_at IS NOT NULL AND completed_at IS NULL
            AND superseded_at IS NULL AND discarded_at IS NULL)
        OR (status = 'COMPLETED' AND activated_at IS NOT NULL AND completed_at IS NOT NULL
            AND superseded_at IS NULL AND discarded_at IS NULL)
        OR (status = 'SUPERSEDED' AND activated_at IS NOT NULL AND completed_at IS NULL
            AND superseded_at IS NOT NULL AND discarded_at IS NULL)
        OR (status = 'DISCARDED' AND activated_at IS NULL AND completed_at IS NULL
            AND superseded_at IS NULL AND discarded_at IS NOT NULL)
    );

CREATE TABLE support_plan_activity_schedule (
    id uuid PRIMARY KEY,
    support_plan_id uuid NOT NULL REFERENCES support_plan(id) ON DELETE RESTRICT,
    user_id uuid NOT NULL,
    ordinal smallint NOT NULL,
    schedule_version integer NOT NULL,
    source_plan_version bigint NOT NULL,
    source_slot_key varchar(64) NOT NULL,
    source_resource_id uuid NOT NULL,
    source_content_version bigint NOT NULL,
    source_title varchar(255) NOT NULL,
    recurrence_type varchar(16) NOT NULL,
    recurrence_day_of_week smallint,
    local_time time NOT NULL,
    timezone varchar(64) NOT NULL,
    effective_from date NOT NULL,
    effective_until date,
    status varchar(16) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ux_support_plan_activity_schedule_owner UNIQUE (id, support_plan_id, user_id),
    CONSTRAINT fk_support_plan_activity_schedule_owner
        FOREIGN KEY (support_plan_id, user_id)
        REFERENCES support_plan(id, user_id) ON DELETE RESTRICT,
    CONSTRAINT ux_support_plan_activity_schedule_source
        UNIQUE (support_plan_id, source_slot_key, schedule_version),
    CONSTRAINT ux_support_plan_activity_schedule_ordinal
        UNIQUE (support_plan_id, ordinal, schedule_version),
    CONSTRAINT ck_support_plan_activity_schedule_ordinal CHECK (ordinal BETWEEN 1 AND 5),
    CONSTRAINT ck_support_plan_activity_schedule_version CHECK (schedule_version >= 1 AND source_plan_version >= 1),
    CONSTRAINT ck_support_plan_activity_schedule_content_version CHECK (source_content_version >= 0),
    CONSTRAINT ck_support_plan_activity_schedule_recurrence CHECK (
        (recurrence_type = 'DAILY' AND recurrence_day_of_week IS NULL)
        OR (recurrence_type = 'WEEKLY' AND recurrence_day_of_week BETWEEN 1 AND 7)
    ),
    CONSTRAINT ck_support_plan_activity_schedule_status CHECK (status IN ('ACTIVE','PAUSED','ENDED')),
    CONSTRAINT ck_support_plan_activity_schedule_dates CHECK (
        effective_until IS NULL OR effective_until >= effective_from
    ),
    CONSTRAINT ck_support_plan_activity_schedule_copy CHECK (length(btrim(source_title)) > 0),
    CONSTRAINT ck_support_plan_activity_schedule_timezone CHECK (length(btrim(timezone)) > 0)
);

CREATE TABLE support_plan_activity_occurrence (
    id uuid PRIMARY KEY,
    activity_schedule_id uuid NOT NULL,
    support_plan_id uuid NOT NULL,
    user_id uuid NOT NULL,
    schedule_version integer NOT NULL,
    local_date date NOT NULL,
    local_time time NOT NULL,
    timezone varchar(64) NOT NULL,
    scheduled_at timestamptz NOT NULL,
    state varchar(16) NOT NULL,
    state_reason varchar(32),
    source_plan_version bigint NOT NULL,
    source_slot_key varchar(64) NOT NULL,
    source_resource_id uuid NOT NULL,
    source_content_version bigint NOT NULL,
    source_title varchar(255) NOT NULL,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    completed_at timestamptz,
    skipped_at timestamptz,
    cancelled_at timestamptz,
    CONSTRAINT fk_support_plan_activity_occurrence_owner
        FOREIGN KEY (support_plan_id, user_id)
        REFERENCES support_plan(id, user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_support_plan_activity_occurrence_schedule
        FOREIGN KEY (activity_schedule_id, support_plan_id, user_id)
        REFERENCES support_plan_activity_schedule(id, support_plan_id, user_id) ON DELETE RESTRICT,
    CONSTRAINT ux_support_plan_activity_occurrence_intent
        UNIQUE (activity_schedule_id, schedule_version, local_date),
    CONSTRAINT ck_support_plan_activity_occurrence_version CHECK (schedule_version >= 1 AND version >= 0),
    CONSTRAINT ck_support_plan_activity_occurrence_source_version CHECK (
        source_plan_version >= 1 AND source_content_version >= 0
    ),
    CONSTRAINT ck_support_plan_activity_occurrence_state CHECK (
        state IN ('SCHEDULED','COMPLETED','SKIPPED','CANCELLED')
    ),
    CONSTRAINT ck_support_plan_activity_occurrence_state_times CHECK (
        (state = 'SCHEDULED' AND completed_at IS NULL AND skipped_at IS NULL AND cancelled_at IS NULL)
        OR (state = 'COMPLETED' AND completed_at IS NOT NULL AND skipped_at IS NULL AND cancelled_at IS NULL)
        OR (state = 'SKIPPED' AND completed_at IS NULL AND skipped_at IS NOT NULL AND cancelled_at IS NULL)
        OR (state = 'CANCELLED' AND completed_at IS NULL AND skipped_at IS NULL AND cancelled_at IS NOT NULL)
    ),
    CONSTRAINT ck_support_plan_activity_occurrence_reason CHECK (
        (state = 'CANCELLED' AND state_reason IN ('PLAN_PAUSED','PLAN_COMPLETED','PLAN_REPLACED'))
        OR (state <> 'CANCELLED' AND state_reason IS NULL)
    ),
    CONSTRAINT ck_support_plan_activity_occurrence_copy CHECK (length(btrim(source_title)) > 0),
    CONSTRAINT ck_support_plan_activity_occurrence_timezone CHECK (length(btrim(timezone)) > 0)
);

CREATE INDEX ix_support_plan_activity_occurrence_owner_time
    ON support_plan_activity_occurrence (user_id, support_plan_id, local_date, scheduled_at, id);

CREATE INDEX ix_support_plan_activity_occurrence_plan_state_time
    ON support_plan_activity_occurrence (support_plan_id, state, scheduled_at);
