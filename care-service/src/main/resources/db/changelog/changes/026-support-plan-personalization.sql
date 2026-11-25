--liquibase formatted sql

--changeset mentalbridge:care-027-support-plan-personalization runInTransaction:true
CREATE TABLE support_plan_personalization (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES user_profile(account_id) ON DELETE RESTRICT,
    revision bigint NOT NULL,
    schema_version varchar(64) NOT NULL,
    status varchar(16) NOT NULL,
    preferred_focus varchar(32),
    desired_pace varchar(16),
    daily_time_budget_minutes smallint,
    preferred_daypart varchar(16),
    submitted_at timestamptz NOT NULL,
    CONSTRAINT ux_support_plan_personalization_owner_revision UNIQUE (user_id, revision),
    CONSTRAINT ux_support_plan_personalization_owner_reference UNIQUE (id, user_id, revision),
    CONSTRAINT ck_support_plan_personalization_revision CHECK (revision >= 1),
    CONSTRAINT ck_support_plan_personalization_schema CHECK (
        schema_version = 'support-plan-personalization-v1'
    ),
    CONSTRAINT ck_support_plan_personalization_status CHECK (status IN ('PROVIDED', 'SKIPPED')),
    CONSTRAINT ck_support_plan_personalization_focus CHECK (
        preferred_focus IS NULL OR preferred_focus IN (
            'ANXIETY', 'MOOD', 'SLEEP', 'MOTIVATION', 'UNDERSTANDING_EMOTIONS'
        )
    ),
    CONSTRAINT ck_support_plan_personalization_pace CHECK (
        desired_pace IS NULL OR desired_pace IN ('GENTLE', 'BALANCED', 'ACTIVE')
    ),
    CONSTRAINT ck_support_plan_personalization_time_budget CHECK (
        daily_time_budget_minutes IS NULL OR daily_time_budget_minutes IN (5, 10, 15, 20, 30)
    ),
    CONSTRAINT ck_support_plan_personalization_daypart CHECK (
        preferred_daypart IS NULL OR preferred_daypart IN (
            'MORNING', 'AFTERNOON', 'EVENING', 'FLEXIBLE'
        )
    ),
    CONSTRAINT ck_support_plan_personalization_status_shape CHECK (
        status = 'PROVIDED' OR (
            preferred_focus IS NULL AND desired_pace IS NULL
            AND daily_time_budget_minutes IS NULL AND preferred_daypart IS NULL
        )
    )
);

CREATE INDEX ix_support_plan_personalization_current
    ON support_plan_personalization (user_id, revision DESC);

CREATE TABLE support_plan_personalization_activity_mode (
    id uuid PRIMARY KEY,
    personalization_id uuid NOT NULL REFERENCES support_plan_personalization(id) ON DELETE RESTRICT,
    ordinal smallint NOT NULL,
    activity_mode varchar(32) NOT NULL,
    CONSTRAINT ux_support_plan_personalization_activity_mode_ordinal UNIQUE (
        personalization_id, ordinal
    ),
    CONSTRAINT ux_support_plan_personalization_activity_mode UNIQUE (
        personalization_id, activity_mode
    ),
    CONSTRAINT ck_support_plan_personalization_activity_mode_ordinal CHECK (ordinal BETWEEN 1 AND 5),
    CONSTRAINT ck_support_plan_personalization_activity_mode CHECK (activity_mode IN (
        'READING', 'GUIDED_PRACTICE', 'JOURNALING', 'EMOTION_CHECK_IN', 'ROUTINE'
    ))
);

CREATE TABLE support_plan_personalization_barrier (
    id uuid PRIMARY KEY,
    personalization_id uuid NOT NULL REFERENCES support_plan_personalization(id) ON DELETE RESTRICT,
    ordinal smallint NOT NULL,
    barrier varchar(32) NOT NULL,
    CONSTRAINT ux_support_plan_personalization_barrier_ordinal UNIQUE (
        personalization_id, ordinal
    ),
    CONSTRAINT ux_support_plan_personalization_barrier UNIQUE (personalization_id, barrier),
    CONSTRAINT ck_support_plan_personalization_barrier_ordinal CHECK (ordinal BETWEEN 1 AND 3),
    CONSTRAINT ck_support_plan_personalization_barrier CHECK (barrier IN (
        'LOW_ENERGY', 'LIMITED_TIME', 'DIFFICULT_TO_START'
    ))
);

ALTER TABLE support_plan
    ADD COLUMN personalization_id uuid,
    ADD COLUMN personalization_revision bigint,
    ADD COLUMN personalization_schema_version varchar(64),
    ADD CONSTRAINT fk_support_plan_personalization_owner
        FOREIGN KEY (personalization_id, user_id, personalization_revision)
        REFERENCES support_plan_personalization(id, user_id, revision) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_support_plan_personalization_snapshot CHECK (
        (personalization_id IS NULL AND personalization_revision IS NULL
            AND personalization_schema_version IS NULL)
        OR (personalization_id IS NOT NULL AND personalization_revision >= 1
            AND personalization_schema_version = 'support-plan-personalization-v1')
    );

ALTER TABLE support_plan_template_family
    ADD COLUMN focus_title varchar(255),
    ADD COLUMN focus_rationale varchar(2048),
    ADD COLUMN focus_reason_code varchar(64),
    ADD CONSTRAINT ck_support_plan_template_focus_copy CHECK (
        (focus_title IS NULL AND focus_rationale IS NULL AND focus_reason_code IS NULL)
        OR (length(btrim(focus_title)) BETWEEN 1 AND 255
            AND length(btrim(focus_rationale)) BETWEEN 1 AND 2048
            AND focus_reason_code = 'USER_PREFERENCE_AND_CARE_POLICY')
    );
