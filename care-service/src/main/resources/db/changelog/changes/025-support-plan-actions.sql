--liquibase formatted sql

--changeset mentalbridge:care-026-support-plan-actions runInTransaction:true
ALTER TABLE support_plan
    ALTER COLUMN selection_policy_version DROP NOT NULL,
    ALTER COLUMN resource_policy_version DROP NOT NULL,
    ALTER COLUMN resources_resolved_at DROP NOT NULL,
    ALTER COLUMN selected_resource_count DROP NOT NULL,
    ADD COLUMN model_version integer NOT NULL DEFAULT 2,
    ADD COLUMN plan_policy_version varchar(128),
    ADD COLUMN action_count smallint NOT NULL DEFAULT 0,
    ADD CONSTRAINT ux_support_plan_model_reference UNIQUE (id, model_version),
    ADD CONSTRAINT ck_support_plan_model_version CHECK (model_version IN (2, 3)),
    ADD CONSTRAINT ck_support_plan_model_shape CHECK (
        (model_version = 2 AND plan_policy_version IS NULL AND action_count = 0
            AND selection_policy_version IS NOT NULL AND resource_policy_version IS NOT NULL
            AND resources_resolved_at IS NOT NULL AND selected_resource_count BETWEEN 1 AND 5)
        OR (model_version = 3 AND length(btrim(plan_policy_version)) BETWEEN 1 AND 128
            AND action_count BETWEEN 1 AND 20 AND selection_policy_version IS NULL
            AND resource_policy_version IS NULL AND resources_resolved_at IS NULL
            AND selected_resource_count IS NULL)
    );
CREATE TABLE support_plan_focus (
    id uuid PRIMARY KEY,
    support_plan_id uuid NOT NULL,
    support_plan_model_version integer NOT NULL DEFAULT 3,
    user_id uuid NOT NULL,
    ordinal smallint NOT NULL,
    title varchar(255) NOT NULL,
    rationale varchar(2048),
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ux_support_plan_focus_owner UNIQUE (id, support_plan_id, user_id),
    CONSTRAINT ux_support_plan_focus_ordinal UNIQUE (support_plan_id, ordinal),
    CONSTRAINT fk_support_plan_focus_owner
        FOREIGN KEY (support_plan_id, user_id)
        REFERENCES support_plan(id, user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_support_plan_focus_model
        FOREIGN KEY (support_plan_id, support_plan_model_version)
        REFERENCES support_plan(id, model_version) ON DELETE RESTRICT,
    CONSTRAINT ck_support_plan_focus_model CHECK (support_plan_model_version = 3),
    CONSTRAINT ck_support_plan_focus_ordinal CHECK (ordinal BETWEEN 1 AND 10),
    CONSTRAINT ck_support_plan_focus_copy CHECK (
        length(btrim(title)) BETWEEN 1 AND 255
        AND (rationale IS NULL OR length(btrim(rationale)) BETWEEN 1 AND 2048)
    ),
    CONSTRAINT ck_support_plan_focus_version CHECK (version >= 0)
);

CREATE TABLE support_plan_action (
    id uuid PRIMARY KEY,
    support_plan_id uuid NOT NULL,
    plan_focus_id uuid NOT NULL,
    user_id uuid NOT NULL,
    ordinal smallint NOT NULL,
    action_type varchar(32) NOT NULL,
    purpose_code varchar(64) NOT NULL,
    rationale varchar(2048),
    source_owner varchar(32),
    source_kind varchar(48),
    source_id varchar(255),
    source_revision varchar(128),
    source_policy_version varchar(128),
    source_publication_id uuid,
    source_eligibility_policy_version varchar(128),
    source_verified_at timestamptz,
    provenance_type varchar(32) NOT NULL,
    provenance_reference_id varchar(255),
    provenance_reference_version varchar(128),
    provenance_accepted_at timestamptz NOT NULL,
    presentation_title varchar(255) NOT NULL,
    presentation_summary varchar(4096),
    presentation_instructions varchar(2048),
    presentation_external_url varchar(2048),
    status varchar(16) NOT NULL,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    activated_at timestamptz,
    ended_at timestamptz,
    CONSTRAINT ux_support_plan_action_owner UNIQUE (id, support_plan_id, user_id),
    CONSTRAINT ux_support_plan_action_source_identity
        UNIQUE (id, support_plan_id, user_id, action_type),
    CONSTRAINT ux_support_plan_action_ordinal UNIQUE (plan_focus_id, ordinal),
    CONSTRAINT fk_support_plan_action_owner
        FOREIGN KEY (support_plan_id, user_id)
        REFERENCES support_plan(id, user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_support_plan_action_focus_owner
        FOREIGN KEY (plan_focus_id, support_plan_id, user_id)
        REFERENCES support_plan_focus(id, support_plan_id, user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_support_plan_action_ordinal CHECK (ordinal BETWEEN 1 AND 20),
    CONSTRAINT ck_support_plan_action_type CHECK (action_type IN (
        'RESOURCE', 'JOURNAL', 'EMOTION_CHECK_IN', 'ROUTINE_SELF_MANAGEMENT',
        'PROFESSIONAL_SUPPORT', 'REASSESSMENT_SCREENING', 'SELF_REPORT_REVIEW'
    )),
    CONSTRAINT ck_support_plan_action_copy CHECK (
        length(btrim(purpose_code)) BETWEEN 1 AND 64
        AND (rationale IS NULL OR length(btrim(rationale)) BETWEEN 1 AND 2048)
        AND length(btrim(presentation_title)) BETWEEN 1 AND 255
        AND (presentation_summary IS NULL OR length(btrim(presentation_summary)) BETWEEN 1 AND 4096)
        AND (presentation_instructions IS NULL OR length(btrim(presentation_instructions)) BETWEEN 1 AND 2048)
        AND (presentation_external_url IS NULL OR length(btrim(presentation_external_url)) BETWEEN 1 AND 2048)
    ),
    CONSTRAINT ck_support_plan_action_provenance CHECK (
        provenance_type IN ('USER_SELECTED', 'SUPPORT_GUIDE', 'SPECIALIST', 'SYSTEM_POLICY')
        AND provenance_accepted_at IS NOT NULL
        AND ((provenance_reference_id IS NULL AND provenance_reference_version IS NULL)
            OR (provenance_reference_id IS NOT NULL
                AND length(btrim(provenance_reference_id)) BETWEEN 1 AND 255
                AND provenance_reference_version IS NOT NULL
                AND length(btrim(provenance_reference_version)) BETWEEN 1 AND 128))
    ),
    CONSTRAINT ck_support_plan_action_source CHECK ((
        (action_type = 'RESOURCE'
            AND source_owner = 'CONTENT'
            AND source_kind = 'RESOURCE_VERSION'
            AND source_id IS NOT NULL
            AND source_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            AND source_revision IS NOT NULL
            AND CASE WHEN source_revision ~ '^(0|[1-9][0-9]{0,18})$'
                THEN source_revision::numeric <= 9223372036854775807 ELSE false END
            AND (source_policy_version IS NULL OR length(btrim(source_policy_version)) BETWEEN 1 AND 128)
            AND source_publication_id IS NOT NULL
            AND source_eligibility_policy_version IS NOT NULL
            AND length(btrim(source_eligibility_policy_version)) BETWEEN 1 AND 128
            AND source_verified_at IS NOT NULL)
        OR (action_type = 'JOURNAL' AND (
            (source_owner IS NULL AND source_kind IS NULL AND source_id IS NULL
                AND source_revision IS NULL AND source_policy_version IS NULL
                AND source_publication_id IS NULL AND source_eligibility_policy_version IS NULL
                AND source_verified_at IS NULL)
            OR (source_owner = 'JOURNAL_AI' AND source_kind = 'JOURNAL_TEMPLATE'
                AND source_id IS NOT NULL AND length(btrim(source_id)) BETWEEN 1 AND 255
                AND (source_revision IS NULL OR length(btrim(source_revision)) BETWEEN 1 AND 128)
                AND (source_policy_version IS NULL OR length(btrim(source_policy_version)) BETWEEN 1 AND 128)
                AND source_publication_id IS NULL AND source_eligibility_policy_version IS NULL)))
        OR (action_type = 'EMOTION_CHECK_IN' AND (
            (source_owner IS NULL AND source_kind IS NULL AND source_id IS NULL
                AND source_revision IS NULL AND source_policy_version IS NULL
                AND source_publication_id IS NULL AND source_eligibility_policy_version IS NULL
                AND source_verified_at IS NULL)
            OR (source_owner = 'JOURNAL_AI' AND source_kind = 'EMOTION_CHECK_IN_CAPABILITY'
                AND source_id IS NOT NULL AND length(btrim(source_id)) BETWEEN 1 AND 255
                AND (source_revision IS NULL OR length(btrim(source_revision)) BETWEEN 1 AND 128)
                AND (source_policy_version IS NULL OR length(btrim(source_policy_version)) BETWEEN 1 AND 128)
                AND source_publication_id IS NULL AND source_eligibility_policy_version IS NULL)))
        OR (action_type IN ('ROUTINE_SELF_MANAGEMENT', 'SELF_REPORT_REVIEW')
            AND source_owner IS NULL AND source_kind IS NULL AND source_id IS NULL
            AND source_revision IS NULL AND source_policy_version IS NULL
            AND source_publication_id IS NULL AND source_eligibility_policy_version IS NULL
            AND source_verified_at IS NULL)
        OR (action_type = 'PROFESSIONAL_SUPPORT'
            AND source_owner = 'CONSULTATION'
            AND source_kind IN ('PROFESSIONAL_SUPPORT_PATHWAY', 'APPOINTMENT')
            AND source_id IS NOT NULL AND length(btrim(source_id)) BETWEEN 1 AND 255
            AND (source_revision IS NULL OR length(btrim(source_revision)) BETWEEN 1 AND 128)
            AND (source_policy_version IS NULL OR length(btrim(source_policy_version)) BETWEEN 1 AND 128)
            AND source_publication_id IS NULL AND source_eligibility_policy_version IS NULL)
        OR (action_type = 'REASSESSMENT_SCREENING'
            AND source_owner = 'CARE'
            AND source_kind = 'QUESTIONNAIRE_DEFINITION'
            AND source_id IS NOT NULL
            AND source_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            AND source_revision IS NOT NULL AND length(btrim(source_revision)) BETWEEN 1 AND 128
            AND (source_policy_version IS NULL OR length(btrim(source_policy_version)) BETWEEN 1 AND 128)
            AND source_publication_id IS NULL AND source_eligibility_policy_version IS NULL)
    ) IS TRUE),
    CONSTRAINT ck_support_plan_action_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ENDED')),
    CONSTRAINT ck_support_plan_action_lifecycle CHECK (
        (status = 'DRAFT' AND activated_at IS NULL AND ended_at IS NULL)
        OR (status = 'ACTIVE' AND activated_at IS NOT NULL AND ended_at IS NULL)
        OR (status = 'ENDED' AND activated_at IS NOT NULL AND ended_at IS NOT NULL
            AND ended_at >= activated_at)
    ),
    CONSTRAINT ck_support_plan_action_version CHECK (version >= 0)
);

CREATE INDEX ix_support_plan_action_plan_focus
    ON support_plan_action (support_plan_id, plan_focus_id, ordinal, id);

ALTER TABLE support_plan_activity_schedule
    ALTER COLUMN source_slot_key DROP NOT NULL,
    ALTER COLUMN source_resource_id DROP NOT NULL,
    ALTER COLUMN source_content_version DROP NOT NULL,
    DROP CONSTRAINT ck_support_plan_activity_schedule_ordinal,
    DROP CONSTRAINT ck_support_plan_activity_schedule_content_version,
    ADD COLUMN source_action_id uuid,
    ADD COLUMN source_action_version bigint,
    ADD COLUMN source_action_type varchar(32),
    ADD COLUMN schedule_policy_version varchar(128),
    ADD COLUMN reminder_enabled boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT fk_support_plan_activity_schedule_action
        FOREIGN KEY (source_action_id, support_plan_id, user_id, source_action_type)
        REFERENCES support_plan_action(id, support_plan_id, user_id, action_type) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_support_plan_activity_schedule_source CHECK (
        (source_action_id IS NULL AND source_action_version IS NULL AND source_action_type IS NULL
            AND source_slot_key IS NOT NULL AND source_resource_id IS NOT NULL
            AND source_content_version IS NOT NULL AND source_content_version >= 0
            AND ordinal BETWEEN 1 AND 5 AND schedule_policy_version IS NULL)
        OR (source_action_id IS NOT NULL AND source_action_version IS NOT NULL
            AND source_action_version >= 0 AND source_action_type IS NOT NULL
            AND source_slot_key IS NULL AND source_resource_id IS NULL
            AND source_content_version IS NULL AND ordinal BETWEEN 1 AND 20
            AND length(btrim(schedule_policy_version)) BETWEEN 1 AND 128)
    ),
    ADD CONSTRAINT ck_support_plan_activity_schedule_recurrence_v3 CHECK (
        (source_action_id IS NULL AND recurrence_type IN ('DAILY', 'WEEKLY'))
        OR (source_action_id IS NOT NULL AND recurrence_type IN ('ONCE', 'DAILY', 'WEEKLY'))
    );

ALTER TABLE support_plan_activity_schedule
    DROP CONSTRAINT ck_support_plan_activity_schedule_recurrence,
    ADD CONSTRAINT ck_support_plan_activity_schedule_recurrence CHECK (
        (recurrence_type IN ('ONCE', 'DAILY') AND recurrence_day_of_week IS NULL)
        OR (recurrence_type = 'WEEKLY' AND recurrence_day_of_week BETWEEN 1 AND 7)
    );

CREATE UNIQUE INDEX ux_support_plan_activity_schedule_action
    ON support_plan_activity_schedule (support_plan_id, source_action_id, schedule_version)
    WHERE source_action_id IS NOT NULL;

ALTER TABLE support_plan_activity_schedule
    ADD CONSTRAINT ux_support_plan_activity_schedule_action_snapshot UNIQUE (
        id, support_plan_id, user_id, schedule_version, source_plan_version,
        source_action_id, source_action_version, source_action_type
    );

ALTER TABLE support_plan_activity_occurrence
    ALTER COLUMN source_slot_key DROP NOT NULL,
    ALTER COLUMN source_resource_id DROP NOT NULL,
    ALTER COLUMN source_content_version DROP NOT NULL,
    ADD COLUMN source_action_id uuid,
    ADD COLUMN source_action_version bigint,
    ADD COLUMN source_action_type varchar(32),
    ADD COLUMN evidence_owner varchar(32),
    ADD COLUMN evidence_kind varchar(48),
    ADD COLUMN evidence_id varchar(255),
    ADD COLUMN evidence_revision varchar(128),
    ADD COLUMN evidence_occurred_at timestamptz,
    ADD COLUMN evidence_verified_at timestamptz,
    ADD CONSTRAINT fk_support_plan_activity_occurrence_action
        FOREIGN KEY (source_action_id, support_plan_id, user_id, source_action_type)
        REFERENCES support_plan_action(id, support_plan_id, user_id, action_type) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_support_plan_activity_occurrence_action_schedule
        FOREIGN KEY (
            activity_schedule_id, support_plan_id, user_id, schedule_version, source_plan_version,
            source_action_id, source_action_version, source_action_type
        ) REFERENCES support_plan_activity_schedule (
            id, support_plan_id, user_id, schedule_version, source_plan_version,
            source_action_id, source_action_version, source_action_type
        ) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_support_plan_activity_occurrence_source CHECK (
        (source_action_id IS NULL AND source_action_version IS NULL AND source_action_type IS NULL
            AND source_slot_key IS NOT NULL AND source_resource_id IS NOT NULL
            AND source_content_version IS NOT NULL AND source_content_version >= 0)
        OR (source_action_id IS NOT NULL AND source_action_version IS NOT NULL
            AND source_action_version >= 0 AND source_action_type IS NOT NULL
            AND source_slot_key IS NULL AND source_resource_id IS NULL
            AND source_content_version IS NULL)
    ),
    ADD CONSTRAINT ck_support_plan_activity_occurrence_evidence CHECK ((
        (source_action_id IS NULL
            AND evidence_owner IS NULL AND evidence_kind IS NULL AND evidence_id IS NULL
            AND evidence_revision IS NULL AND evidence_occurred_at IS NULL
            AND evidence_verified_at IS NULL)
        OR (source_action_id IS NOT NULL AND state <> 'COMPLETED'
            AND evidence_owner IS NULL AND evidence_kind IS NULL AND evidence_id IS NULL
            AND evidence_revision IS NULL AND evidence_occurred_at IS NULL
            AND evidence_verified_at IS NULL)
        OR (source_action_id IS NOT NULL AND state = 'COMPLETED' AND (
            (source_action_type IN ('RESOURCE', 'ROUTINE_SELF_MANAGEMENT')
                AND evidence_owner = 'CARE' AND evidence_kind = 'USER_ACKNOWLEDGEMENT'
                AND evidence_id IS NOT NULL AND length(btrim(evidence_id)) BETWEEN 1 AND 255
                AND (evidence_revision IS NULL OR length(btrim(evidence_revision)) BETWEEN 1 AND 128)
                AND evidence_occurred_at IS NOT NULL AND evidence_verified_at IS NOT NULL)
            OR (source_action_type = 'SELF_REPORT_REVIEW'
                AND evidence_owner = 'CARE' AND evidence_kind = 'SELF_REPORT_REVIEW'
                AND evidence_id IS NOT NULL AND length(btrim(evidence_id)) BETWEEN 1 AND 255
                AND (evidence_revision IS NULL OR length(btrim(evidence_revision)) BETWEEN 1 AND 128)
                AND evidence_occurred_at IS NOT NULL AND evidence_verified_at IS NOT NULL)
            OR (source_action_type = 'JOURNAL' AND evidence_owner = 'JOURNAL_AI'
                AND evidence_kind = 'JOURNAL_ENTRY' AND evidence_id IS NOT NULL
                AND length(btrim(evidence_id)) BETWEEN 1 AND 255
                AND (evidence_revision IS NULL OR length(btrim(evidence_revision)) BETWEEN 1 AND 128)
                AND evidence_occurred_at IS NOT NULL AND evidence_verified_at IS NOT NULL)
            OR (source_action_type = 'EMOTION_CHECK_IN' AND evidence_owner = 'JOURNAL_AI'
                AND evidence_kind = 'EMOTION_CHECK_IN' AND evidence_id IS NOT NULL
                AND length(btrim(evidence_id)) BETWEEN 1 AND 255
                AND (evidence_revision IS NULL OR length(btrim(evidence_revision)) BETWEEN 1 AND 128)
                AND evidence_occurred_at IS NOT NULL AND evidence_verified_at IS NOT NULL)
            OR (source_action_type = 'PROFESSIONAL_SUPPORT' AND evidence_owner = 'CONSULTATION'
                AND evidence_kind = 'APPOINTMENT_FULFILMENT' AND evidence_id IS NOT NULL
                AND length(btrim(evidence_id)) BETWEEN 1 AND 255
                AND (evidence_revision IS NULL OR length(btrim(evidence_revision)) BETWEEN 1 AND 128)
                AND evidence_occurred_at IS NOT NULL AND evidence_verified_at IS NOT NULL)
            OR (source_action_type = 'REASSESSMENT_SCREENING' AND evidence_owner = 'CARE'
                AND evidence_kind = 'ASSESSMENT_HISTORY' AND evidence_id IS NOT NULL
                AND length(btrim(evidence_id)) BETWEEN 1 AND 255
                AND (evidence_revision IS NULL OR length(btrim(evidence_revision)) BETWEEN 1 AND 128)
                AND evidence_occurred_at IS NOT NULL AND evidence_verified_at IS NOT NULL)
        ))) IS TRUE
        AND (evidence_verified_at IS NULL OR evidence_verified_at >= evidence_occurred_at)
    );

CREATE UNIQUE INDEX ux_support_plan_activity_occurrence_evidence_fact
    ON support_plan_activity_occurrence (
        evidence_owner, evidence_kind, evidence_id, coalesce(evidence_revision, '')
    )
    WHERE evidence_owner IS NOT NULL;

ALTER TABLE support_plan_activity_occurrence
    DROP CONSTRAINT ck_support_plan_activity_occurrence_reason,
    ADD CONSTRAINT ck_support_plan_activity_occurrence_reason CHECK (
        (state = 'CANCELLED' AND state_reason IN (
            'PLAN_PAUSED', 'PLAN_COMPLETED', 'PLAN_REPLACED', 'ACTION_ENDED'
        ))
        OR (state <> 'CANCELLED' AND state_reason IS NULL)
    );
