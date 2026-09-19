--liquibase formatted sql

--changeset mentalbridge:care-013-support-plan-draft runInTransaction:true
CREATE TABLE support_plan (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES user_profile(account_id) ON DELETE RESTRICT,
    support_evaluation_id uuid NOT NULL,
    status varchar(16) NOT NULL,
    version bigint NOT NULL,
    evaluation_policy_version varchar(64) NOT NULL,
    evaluated_at timestamptz NOT NULL,
    selection_policy_version varchar(64) NOT NULL,
    resource_policy_version varchar(64) NOT NULL,
    resources_resolved_at timestamptz NOT NULL,
    entitlement_package varchar(16) NOT NULL,
    entitlement_source varchar(16) NOT NULL,
    entitlement_policy_version varchar(64) NOT NULL,
    entitlement_version bigint NOT NULL,
    entitlement_decided_at timestamptz NOT NULL,
    rationale_code varchar(64) NOT NULL,
    rationale_text varchar(2048) NOT NULL,
    safety_status varchar(32) NOT NULL,
    safety_reason_code varchar(64) NOT NULL,
    safety_policy_version varchar(64) NOT NULL,
    safety_guidance_code varchar(64) NOT NULL,
    safety_guidance varchar(2048) NOT NULL,
    selected_resource_count smallint NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_support_plan_evaluation_owner
        FOREIGN KEY (support_evaluation_id, user_id)
        REFERENCES support_evaluation_v2(id, user_id) ON DELETE RESTRICT,
    CONSTRAINT ux_support_plan_owner_reference UNIQUE (id, user_id),
    CONSTRAINT ck_support_plan_status CHECK (status IN ('DRAFT','ACTIVE','PAUSED','COMPLETED','SUPERSEDED')),
    CONSTRAINT ck_support_plan_version CHECK (version >= 0),
    CONSTRAINT ck_support_plan_evaluation_policy CHECK (evaluation_policy_version = 'mb-support-routing-capstone-v2'),
    CONSTRAINT ck_support_plan_selection_policy CHECK (selection_policy_version = 'mb-support-plan-selection-v1'),
    CONSTRAINT ck_support_plan_resource_policy CHECK (resource_policy_version = 'content-eligibility-v1'),
    CONSTRAINT ck_support_plan_entitlement CHECK (entitlement_package IN ('PLUS','PREMIUM') AND entitlement_source IN ('DEMO','PAID')),
    CONSTRAINT ck_support_plan_entitlement_version CHECK (entitlement_version >= 0),
    CONSTRAINT ck_support_plan_selected_count CHECK (selected_resource_count BETWEEN 1 AND 5),
    CONSTRAINT ck_support_plan_copy CHECK (length(btrim(rationale_text)) > 0 AND length(btrim(safety_guidance)) > 0)
);

CREATE UNIQUE INDEX ux_support_plan_current_draft
    ON support_plan (user_id) WHERE status = 'DRAFT';

CREATE INDEX ix_support_plan_user_history
    ON support_plan (user_id, created_at DESC, id DESC);

CREATE TABLE support_plan_template_family (
    id uuid PRIMARY KEY,
    support_plan_id uuid NOT NULL REFERENCES support_plan(id) ON DELETE RESTRICT,
    ordinal smallint NOT NULL,
    family varchar(64) NOT NULL,
    template_version integer NOT NULL,
    target_domain varchar(48) NOT NULL,
    CONSTRAINT ux_support_plan_template_ordinal UNIQUE (support_plan_id, ordinal),
    CONSTRAINT ux_support_plan_template_domain UNIQUE (support_plan_id, target_domain),
    CONSTRAINT ck_support_plan_template_ordinal CHECK (ordinal BETWEEN 1 AND 2),
    CONSTRAINT ck_support_plan_template_version CHECK (template_version = 1),
    CONSTRAINT ck_support_plan_template_domain CHECK (target_domain IN ('DEPRESSIVE_SYMPTOMS','ANXIETY_SYMPTOMS'))
);

CREATE TABLE support_plan_slot (
    id uuid PRIMARY KEY,
    support_plan_id uuid NOT NULL REFERENCES support_plan(id) ON DELETE RESTRICT,
    ordinal smallint NOT NULL,
    slot_key varchar(64) NOT NULL,
    slot_kind varchar(16) NOT NULL,
    target_domain varchar(48) NOT NULL,
    purpose_code varchar(64) NOT NULL,
    selected_resource_id uuid NOT NULL,
    selected_content_version bigint NOT NULL,
    selected_publication_id uuid NOT NULL,
    selected_role varchar(16) NOT NULL,
    selected_category varchar(32) NOT NULL,
    selected_title varchar(255) NOT NULL,
    selected_summary text NOT NULL,
    selected_external_url varchar(2048),
    CONSTRAINT ux_support_plan_slot_ordinal UNIQUE (support_plan_id, ordinal),
    CONSTRAINT ux_support_plan_slot_key UNIQUE (support_plan_id, slot_key),
    CONSTRAINT ux_support_plan_selected_resource UNIQUE (support_plan_id, selected_resource_id, selected_content_version),
    CONSTRAINT ck_support_plan_slot_ordinal CHECK (ordinal BETWEEN 1 AND 5),
    CONSTRAINT ck_support_plan_slot_kind CHECK (slot_kind IN ('CORE','OPTIONAL')),
    CONSTRAINT ck_support_plan_slot_domain CHECK (target_domain IN ('DEPRESSIVE_SYMPTOMS','ANXIETY_SYMPTOMS')),
    CONSTRAINT ck_support_plan_slot_version CHECK (selected_content_version >= 0),
    CONSTRAINT ck_support_plan_slot_role CHECK (selected_role IN ('PRIMARY','ADJUNCT')),
    CONSTRAINT ck_support_plan_slot_copy CHECK (length(btrim(selected_title)) > 0 AND length(btrim(selected_summary)) > 0)
);

CREATE TABLE support_plan_slot_alternative (
    id uuid PRIMARY KEY,
    support_plan_slot_id uuid NOT NULL REFERENCES support_plan_slot(id) ON DELETE RESTRICT,
    ordinal smallint NOT NULL,
    resource_id uuid NOT NULL,
    content_version bigint NOT NULL,
    publication_id uuid NOT NULL,
    eligibility_role varchar(16) NOT NULL,
    category varchar(32) NOT NULL,
    title varchar(255) NOT NULL,
    summary text NOT NULL,
    external_url varchar(2048),
    CONSTRAINT ux_support_plan_alternative_ordinal UNIQUE (support_plan_slot_id, ordinal),
    CONSTRAINT ux_support_plan_alternative_resource UNIQUE (support_plan_slot_id, resource_id, content_version),
    CONSTRAINT ck_support_plan_alternative_ordinal CHECK (ordinal BETWEEN 1 AND 10),
    CONSTRAINT ck_support_plan_alternative_version CHECK (content_version >= 0),
    CONSTRAINT ck_support_plan_alternative_role CHECK (eligibility_role IN ('PRIMARY','ADJUNCT')),
    CONSTRAINT ck_support_plan_alternative_copy CHECK (length(btrim(title)) > 0 AND length(btrim(summary)) > 0)
);

CREATE TABLE support_plan_request (
    user_id uuid NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_hash varchar(64) NOT NULL,
    support_plan_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, idempotency_key),
    CONSTRAINT fk_support_plan_request_owner
        FOREIGN KEY (support_plan_id, user_id)
        REFERENCES support_plan(id, user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_support_plan_request_key CHECK (length(idempotency_key) BETWEEN 16 AND 128),
    CONSTRAINT ck_support_plan_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$')
);
