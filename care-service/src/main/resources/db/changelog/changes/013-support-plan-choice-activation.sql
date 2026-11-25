--liquibase formatted sql

--changeset mentalbridge:care-014-support-plan-choice-activation runInTransaction:true
ALTER TABLE support_plan
    ADD COLUMN activated_at timestamptz;

ALTER TABLE support_plan_slot
    ALTER COLUMN selected_resource_id DROP NOT NULL,
    ALTER COLUMN selected_content_version DROP NOT NULL,
    ALTER COLUMN selected_publication_id DROP NOT NULL,
    ALTER COLUMN selected_role DROP NOT NULL,
    ALTER COLUMN selected_category DROP NOT NULL,
    ALTER COLUMN selected_title DROP NOT NULL,
    ALTER COLUMN selected_summary DROP NOT NULL;

ALTER TABLE support_plan_slot
    ADD CONSTRAINT ck_support_plan_selected_resource_complete CHECK (
        (selected_resource_id IS NULL
            AND selected_content_version IS NULL
            AND selected_publication_id IS NULL
            AND selected_role IS NULL
            AND selected_category IS NULL
            AND selected_title IS NULL
            AND selected_summary IS NULL
            AND selected_external_url IS NULL)
        OR
        (selected_resource_id IS NOT NULL
            AND selected_content_version IS NOT NULL
            AND selected_publication_id IS NOT NULL
            AND selected_role IS NOT NULL
            AND selected_category IS NOT NULL
            AND selected_title IS NOT NULL
            AND selected_summary IS NOT NULL)
    );

ALTER TABLE support_plan_slot_alternative
    DROP CONSTRAINT ck_support_plan_alternative_ordinal,
    ADD CONSTRAINT ck_support_plan_alternative_ordinal CHECK (ordinal BETWEEN 1 AND 11);

CREATE UNIQUE INDEX ux_support_plan_current
    ON support_plan (user_id) WHERE status IN ('ACTIVE','PAUSED');

CREATE TABLE support_plan_command (
    user_id uuid NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    command_type varchar(16) NOT NULL,
    request_hash varchar(64) NOT NULL,
    support_plan_id uuid NOT NULL,
    expected_version bigint NOT NULL,
    resulting_version bigint NOT NULL,
    resulting_status varchar(16) NOT NULL,
    resulting_updated_at timestamptz NOT NULL,
    evaluation_policy_version varchar(64) NOT NULL,
    entitlement_package varchar(16) NOT NULL,
    entitlement_source varchar(16) NOT NULL,
    entitlement_policy_version varchar(64) NOT NULL,
    entitlement_version bigint NOT NULL,
    entitlement_decided_at timestamptz NOT NULL,
    resource_policy_version varchar(64) NOT NULL,
    resources_resolved_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, idempotency_key),
    CONSTRAINT fk_support_plan_command_owner
        FOREIGN KEY (support_plan_id, user_id)
        REFERENCES support_plan(id, user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_support_plan_command_type CHECK (command_type = 'ACTIVATE'),
    CONSTRAINT ck_support_plan_command_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_support_plan_command_versions CHECK (
        expected_version >= 0 AND resulting_version = expected_version + 1
    ),
    CONSTRAINT ck_support_plan_command_status CHECK (resulting_status IN ('DRAFT','ACTIVE')),
    CONSTRAINT ck_support_plan_command_entitlement CHECK (
        entitlement_package IN ('PLUS','PREMIUM') AND entitlement_source IN ('DEMO','PAID')
    ),
    CONSTRAINT ck_support_plan_command_policy CHECK (
        evaluation_policy_version = 'mb-support-routing-capstone-v2'
        AND entitlement_policy_version = 'service-entitlement-v1'
        AND resource_policy_version = 'content-eligibility-v1'
    )
);

CREATE TABLE support_plan_command_selection (
    user_id uuid NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    ordinal smallint NOT NULL,
    slot_key varchar(64) NOT NULL,
    resource_id uuid NOT NULL,
    content_version bigint NOT NULL,
    PRIMARY KEY (user_id, idempotency_key, ordinal),
    CONSTRAINT fk_support_plan_command_selection
        FOREIGN KEY (user_id, idempotency_key)
        REFERENCES support_plan_command(user_id, idempotency_key) ON DELETE RESTRICT,
    CONSTRAINT ux_support_plan_command_slot UNIQUE (user_id, idempotency_key, slot_key),
    CONSTRAINT ux_support_plan_command_resource UNIQUE (user_id, idempotency_key, resource_id, content_version),
    CONSTRAINT ck_support_plan_command_selection_ordinal CHECK (ordinal BETWEEN 1 AND 5),
    CONSTRAINT ck_support_plan_command_selection_version CHECK (content_version >= 0)
);
