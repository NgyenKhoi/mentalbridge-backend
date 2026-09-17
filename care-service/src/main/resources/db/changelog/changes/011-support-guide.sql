--liquibase formatted sql

--changeset mentalbridge:care-012-support-guide runInTransaction:true
CREATE TABLE support_guide (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES user_profile(account_id) ON DELETE RESTRICT,
    support_evaluation_id uuid NOT NULL,
    guide_policy_version varchar(64) NOT NULL,
    generated_at timestamptz NOT NULL,
    explanation_code varchar(64) NOT NULL,
    explanation_text varchar(2048) NOT NULL,
    safety_status varchar(32) NOT NULL,
    safety_reason_code varchar(64) NOT NULL,
    safety_policy_version varchar(64) NOT NULL,
    safety_guidance_code varchar(64) NOT NULL,
    safety_guidance varchar(2048) NOT NULL,
    resource_status varchar(16) NOT NULL,
    resource_policy_version varchar(64) NOT NULL,
    resources_resolved_at timestamptz NOT NULL,
    phrasing_status varchar(32) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_support_guide_evaluation_owner
        FOREIGN KEY (support_evaluation_id, user_id)
        REFERENCES support_evaluation_v2(id, user_id) ON DELETE RESTRICT,
    CONSTRAINT ux_support_guide_evaluation UNIQUE (user_id, support_evaluation_id, guide_policy_version),
    CONSTRAINT ux_support_guide_owner_reference UNIQUE (id, user_id),
    CONSTRAINT ck_support_guide_policy CHECK (guide_policy_version = 'mb-support-guide-capstone-v1'),
    CONSTRAINT ck_support_guide_resource_status CHECK (resource_status IN ('AVAILABLE','PARTIAL','EMPTY','STALE','UNAVAILABLE')),
    CONSTRAINT ck_support_guide_phrasing CHECK (phrasing_status IN ('STANDARD','AI_UNAVAILABLE_FALLBACK')),
    CONSTRAINT ck_support_guide_text CHECK (length(btrim(explanation_text)) > 0 AND length(btrim(safety_guidance)) > 0)
);

CREATE INDEX ix_support_guide_user_history
    ON support_guide (user_id, generated_at DESC, id DESC);

CREATE TABLE support_guide_resource (
    id uuid PRIMARY KEY,
    support_guide_id uuid NOT NULL REFERENCES support_guide(id) ON DELETE RESTRICT,
    ordinal smallint NOT NULL,
    resource_id uuid NOT NULL,
    content_version bigint NOT NULL,
    publication_id uuid NOT NULL,
    domain varchar(48) NOT NULL,
    eligibility_role varchar(16) NOT NULL,
    category varchar(32) NOT NULL,
    title varchar(255) NOT NULL,
    summary text NOT NULL,
    external_url varchar(2048),
    CONSTRAINT ux_support_guide_resource_ordinal UNIQUE (support_guide_id, ordinal),
    CONSTRAINT ux_support_guide_resource UNIQUE (support_guide_id, resource_id, domain),
    CONSTRAINT ck_support_guide_resource_ordinal CHECK (ordinal BETWEEN 1 AND 4),
    CONSTRAINT ck_support_guide_resource_version CHECK (content_version >= 0),
    CONSTRAINT ck_support_guide_resource_domain CHECK (domain IN ('DEPRESSIVE_SYMPTOMS','ANXIETY_SYMPTOMS')),
    CONSTRAINT ck_support_guide_resource_role CHECK (eligibility_role IN ('PRIMARY','ADJUNCT')),
    CONSTRAINT ck_support_guide_resource_copy CHECK (length(btrim(title)) > 0 AND length(btrim(summary)) > 0)
);

CREATE TABLE support_guide_request (
    user_id uuid NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_hash varchar(64) NOT NULL,
    support_guide_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, idempotency_key),
    CONSTRAINT fk_support_guide_request_owner
        FOREIGN KEY (support_guide_id, user_id)
        REFERENCES support_guide(id, user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_support_guide_request_key CHECK (length(idempotency_key) BETWEEN 16 AND 128),
    CONSTRAINT ck_support_guide_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$')
);
