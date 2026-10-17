--liquibase formatted sql

--changeset mentalbridge:care-011-domain-aware-support-evaluation-v2 runInTransaction:true
ALTER TABLE assessment_submission
    ADD CONSTRAINT ux_assessment_submission_owner_reference UNIQUE (id, user_id);

CREATE TABLE support_evaluation_v2_policy_definition (
    version varchar(64) PRIMARY KEY,
    locale varchar(16) NOT NULL,
    status varchar(16) NOT NULL,
    reviewed_by varchar(160) NOT NULL,
    approved_at timestamptz NOT NULL,
    source_reference varchar(512) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_support_evaluation_v2_policy_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    CONSTRAINT ck_support_evaluation_v2_policy_locale CHECK (locale ~ '^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$'),
    CONSTRAINT ck_support_evaluation_v2_policy_review CHECK (length(btrim(reviewed_by)) > 0),
    CONSTRAINT ck_support_evaluation_v2_policy_source CHECK (length(btrim(source_reference)) > 0)
);

CREATE UNIQUE INDEX ux_support_evaluation_v2_policy_current
    ON support_evaluation_v2_policy_definition (locale)
    WHERE status = 'PUBLISHED';

CREATE TABLE support_evaluation_v2_eligible_definition (
    policy_version varchar(64) NOT NULL REFERENCES support_evaluation_v2_policy_definition(version) ON DELETE RESTRICT,
    definition_id uuid NOT NULL REFERENCES questionnaire_definition(id) ON DELETE RESTRICT,
    instrument varchar(16) NOT NULL,
    questionnaire_version varchar(32) NOT NULL,
    scoring_version varchar(32) NOT NULL,
    domain varchar(48) NOT NULL,
    PRIMARY KEY (policy_version, definition_id),
    CONSTRAINT ux_support_evaluation_v2_eligible_instrument_version UNIQUE (
        policy_version, instrument, questionnaire_version
    ),
    CONSTRAINT ck_support_evaluation_v2_eligible_mapping CHECK (
        (instrument = 'PHQ9' AND domain = 'DEPRESSIVE_SYMPTOMS') OR
        (instrument = 'GAD7' AND domain = 'ANXIETY_SYMPTOMS')
    ),
    CONSTRAINT ck_support_evaluation_v2_questionnaire_version CHECK (length(btrim(questionnaire_version)) > 0),
    CONSTRAINT ck_support_evaluation_v2_scoring_version CHECK (length(btrim(scoring_version)) > 0)
);

CREATE TABLE support_evaluation_v2 (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES user_profile(account_id) ON DELETE RESTRICT,
    phq9_assessment_id uuid NOT NULL,
    gad7_assessment_id uuid NOT NULL,
    policy_version varchar(64) NOT NULL REFERENCES support_evaluation_v2_policy_definition(version) ON DELETE RESTRICT,
    evaluated_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_support_evaluation_v2_phq9_owner
        FOREIGN KEY (phq9_assessment_id, user_id)
        REFERENCES assessment_submission(id, user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_support_evaluation_v2_gad7_owner
        FOREIGN KEY (gad7_assessment_id, user_id)
        REFERENCES assessment_submission(id, user_id) ON DELETE RESTRICT,
    CONSTRAINT ux_support_evaluation_v2_owner_reference UNIQUE (id, user_id),
    CONSTRAINT ux_support_evaluation_v2_phq9_reference UNIQUE (id, phq9_assessment_id),
    CONSTRAINT ux_support_evaluation_v2_evidence UNIQUE (
        user_id, phq9_assessment_id, gad7_assessment_id, policy_version
    ),
    CONSTRAINT ck_support_evaluation_v2_distinct_evidence CHECK (phq9_assessment_id <> gad7_assessment_id)
);

CREATE INDEX ix_support_evaluation_v2_user_history
    ON support_evaluation_v2 (user_id, evaluated_at DESC, id DESC);

CREATE TABLE support_evaluation_v2_domain (
    id uuid PRIMARY KEY,
    support_evaluation_id uuid NOT NULL REFERENCES support_evaluation_v2(id) ON DELETE RESTRICT,
    ordinal smallint NOT NULL,
    assessment_id uuid NOT NULL,
    definition_id uuid NOT NULL,
    instrument varchar(16) NOT NULL,
    domain varchar(48) NOT NULL,
    questionnaire_version varchar(32) NOT NULL,
    scoring_version varchar(32) NOT NULL,
    screening_level varchar(24) NOT NULL,
    support_pathway varchar(48) NOT NULL,
    reason_code varchar(64) NOT NULL,
    CONSTRAINT fk_support_evaluation_v2_domain_evidence
        FOREIGN KEY (assessment_id, definition_id)
        REFERENCES assessment_submission(id, definition_id) ON DELETE RESTRICT,
    CONSTRAINT ux_support_evaluation_v2_domain_ordinal UNIQUE (support_evaluation_id, ordinal),
    CONSTRAINT ux_support_evaluation_v2_domain_instrument UNIQUE (support_evaluation_id, instrument),
    CONSTRAINT ux_support_evaluation_v2_domain_name UNIQUE (support_evaluation_id, domain),
    CONSTRAINT ck_support_evaluation_v2_domain_mapping CHECK (
        (ordinal = 1 AND instrument = 'PHQ9' AND domain = 'DEPRESSIVE_SYMPTOMS' AND
         screening_level IN ('MINIMAL', 'MILD', 'MODERATE', 'MODERATELY_SEVERE', 'SEVERE') AND
         reason_code = 'PHQ9_LEVEL_' || screening_level) OR
        (ordinal = 2 AND instrument = 'GAD7' AND domain = 'ANXIETY_SYMPTOMS' AND
         screening_level IN ('MINIMAL', 'MILD', 'MODERATE', 'SEVERE') AND
         reason_code = 'GAD7_LEVEL_' || screening_level)
    ),
    CONSTRAINT ck_support_evaluation_v2_domain_pathway CHECK (
        (screening_level IN ('MINIMAL', 'MILD') AND support_pathway = 'SELF_GUIDED_SUPPORT') OR
        (screening_level IN ('MODERATE', 'MODERATELY_SEVERE', 'SEVERE') AND
         support_pathway = 'PROFESSIONAL_SUPPORT_RECOMMENDED')
    ),
    CONSTRAINT ck_support_evaluation_v2_domain_versions CHECK (
        length(btrim(questionnaire_version)) > 0 AND length(btrim(scoring_version)) > 0
    )
);

CREATE TABLE support_evaluation_v2_safety (
    support_evaluation_id uuid PRIMARY KEY,
    source_assessment_id uuid NOT NULL,
    instrument varchar(16) NOT NULL,
    trigger_code varchar(32) NOT NULL,
    safety_status varchar(32) NOT NULL,
    safety_policy_version varchar(64) NOT NULL,
    reason_code varchar(64) NOT NULL,
    CONSTRAINT fk_support_evaluation_v2_safety_source
        FOREIGN KEY (support_evaluation_id, source_assessment_id)
        REFERENCES support_evaluation_v2(id, phq9_assessment_id) ON DELETE RESTRICT,
    CONSTRAINT ck_support_evaluation_v2_safety_source CHECK (
        instrument = 'PHQ9' AND trigger_code = 'PHQ9_ITEM_9'
    ),
    CONSTRAINT ck_support_evaluation_v2_safety_status CHECK (
        (safety_status = 'NEGATIVE_SAFETY_SCREEN' AND reason_code = 'PHQ9_ITEM9_NEGATIVE') OR
        (safety_status = 'POSITIVE_SAFETY_SCREEN' AND reason_code = 'PHQ9_ITEM9_POSITIVE')
    ),
    CONSTRAINT ck_support_evaluation_v2_safety_policy CHECK (length(btrim(safety_policy_version)) > 0)
);

CREATE TABLE support_evaluation_v2_request (
    user_id uuid NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_hash varchar(64) NOT NULL,
    support_evaluation_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, idempotency_key),
    CONSTRAINT fk_support_evaluation_v2_request_owner
        FOREIGN KEY (support_evaluation_id, user_id)
        REFERENCES support_evaluation_v2(id, user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_support_evaluation_v2_request_key CHECK (length(idempotency_key) BETWEEN 16 AND 128),
    CONSTRAINT ck_support_evaluation_v2_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

INSERT INTO support_evaluation_v2_policy_definition (
    version, locale, status, reviewed_by, approved_at, source_reference
) VALUES (
    'mb-support-routing-capstone-v2',
    'vi-VN',
    'PUBLISHED',
    'ADR 0012 and ADR 0013 accepted product decisions',
    '2026-09-16T00:00:00Z',
    'GitHub #48; MB-335; MB-SCOPE-DOMAIN-001; MB-SUPPORT-PLAN-001'
);

INSERT INTO support_evaluation_v2_eligible_definition (
    policy_version, definition_id, instrument, questionnaire_version, scoring_version, domain
) VALUES
    ('mb-support-routing-capstone-v2', '10000000-0000-0000-0000-000000000002', 'PHQ9', 'phq9-vi-vn-capstone-v1', 'phq9-standard-bands-v1', 'DEPRESSIVE_SYMPTOMS'),
    ('mb-support-routing-capstone-v2', '10000000-0000-0000-0000-000000000004', 'PHQ9', 'phq9-vi-vn-capstone-v2', 'phq9-standard-bands-v1', 'DEPRESSIVE_SYMPTOMS'),
    ('mb-support-routing-capstone-v2', '10000000-0000-0000-0000-000000000003', 'GAD7', 'gad7-vi-vn-adult-v1', 'gad7-standard-bands-v1', 'ANXIETY_SYMPTOMS');
