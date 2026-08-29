--liquibase formatted sql

--changeset mentalbridge:care-003-assessment-foundation runInTransaction:true
CREATE TABLE anonymous_assessment_session (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash varchar(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    closed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_anonymous_assessment_session_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_anonymous_assessment_session_token_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_anonymous_assessment_session_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_anonymous_assessment_session_closed CHECK (closed_at IS NULL OR closed_at >= created_at)
);

CREATE INDEX ix_anonymous_assessment_session_expiry
    ON anonymous_assessment_session (expires_at, id)
    WHERE closed_at IS NULL;

CREATE TABLE questionnaire_definition (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    instrument varchar(16) NOT NULL,
    version varchar(32) NOT NULL,
    locale varchar(16) NOT NULL,
    title varchar(255) NOT NULL,
    reference_period_days smallint NOT NULL,
    expected_question_count smallint NOT NULL,
    scoring_version varchar(32) NOT NULL,
    response_options jsonb NOT NULL,
    source_reference varchar(512) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_questionnaire_definition_version UNIQUE (instrument, version, locale),
    CONSTRAINT ck_questionnaire_definition_instrument CHECK (instrument IN ('PHQ9', 'GAD7')),
    CONSTRAINT ck_questionnaire_definition_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    CONSTRAINT ck_questionnaire_definition_publication CHECK (
        (status = 'DRAFT' AND published_at IS NULL) OR
        (status IN ('PUBLISHED', 'RETIRED') AND published_at IS NOT NULL)
    ),
    CONSTRAINT ck_questionnaire_definition_expected_count CHECK (
        (instrument = 'PHQ9' AND expected_question_count = 9) OR
        (instrument = 'GAD7' AND expected_question_count = 7)
    ),
    CONSTRAINT ck_questionnaire_definition_reference_period CHECK (reference_period_days > 0),
    CONSTRAINT ck_questionnaire_definition_response_options CHECK (
        jsonb_typeof(response_options) = 'array' AND jsonb_array_length(response_options) = 4
    ),
    CONSTRAINT ck_questionnaire_definition_source CHECK (length(btrim(source_reference)) > 0)
);

CREATE UNIQUE INDEX ux_questionnaire_definition_current
    ON questionnaire_definition (instrument, locale)
    WHERE status = 'PUBLISHED';

CREATE TABLE questionnaire_question (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    definition_id uuid NOT NULL REFERENCES questionnaire_definition(id) ON DELETE RESTRICT,
    item_number smallint NOT NULL,
    prompt text NOT NULL,
    safety_item boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_questionnaire_question_item UNIQUE (definition_id, item_number),
    CONSTRAINT ux_questionnaire_question_definition_id UNIQUE (definition_id, id),
    CONSTRAINT ck_questionnaire_question_item_number CHECK (item_number BETWEEN 1 AND 32),
    CONSTRAINT ck_questionnaire_question_prompt CHECK (length(btrim(prompt)) > 0)
);

CREATE TABLE questionnaire_score_band (
    definition_id uuid NOT NULL REFERENCES questionnaire_definition(id) ON DELETE RESTRICT,
    code varchar(24) NOT NULL,
    minimum_score smallint NOT NULL,
    maximum_score smallint NOT NULL,
    ordinal smallint NOT NULL,
    PRIMARY KEY (definition_id, code),
    CONSTRAINT ux_questionnaire_score_band_ordinal UNIQUE (definition_id, ordinal),
    CONSTRAINT ux_questionnaire_score_band_minimum UNIQUE (definition_id, minimum_score),
    CONSTRAINT ck_questionnaire_score_band_code CHECK (code IN (
        'MINIMAL', 'MILD', 'MODERATE', 'MODERATELY_SEVERE', 'SEVERE'
    )),
    CONSTRAINT ck_questionnaire_score_band_range CHECK (
        minimum_score >= 0 AND maximum_score <= 27 AND minimum_score <= maximum_score
    ),
    CONSTRAINT ck_questionnaire_score_band_ordinal CHECK (ordinal > 0)
);

CREATE TABLE assessment_submission (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid REFERENCES user_profile(account_id) ON DELETE RESTRICT,
    anonymous_session_id uuid REFERENCES anonymous_assessment_session(id) ON DELETE RESTRICT,
    definition_id uuid NOT NULL REFERENCES questionnaire_definition(id) ON DELETE RESTRICT,
    idempotency_key varchar(128) NOT NULL,
    request_hash varchar(64) NOT NULL,
    submitted_at timestamptz NOT NULL,
    retention_expires_at timestamptz,
    voided_at timestamptz,
    void_reason_code varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_assessment_submission_definition UNIQUE (id, definition_id),
    CONSTRAINT ck_assessment_submission_owner CHECK (
        (user_id IS NOT NULL AND anonymous_session_id IS NULL AND retention_expires_at IS NULL) OR
        (user_id IS NULL AND anonymous_session_id IS NOT NULL AND retention_expires_at > submitted_at)
    ),
    CONSTRAINT ck_assessment_submission_idempotency_key CHECK (length(idempotency_key) BETWEEN 16 AND 128),
    CONSTRAINT ck_assessment_submission_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_assessment_submission_void CHECK (
        (voided_at IS NULL AND void_reason_code IS NULL) OR
        (voided_at IS NOT NULL AND voided_at >= submitted_at AND void_reason_code IS NOT NULL)
    ),
    CONSTRAINT ck_assessment_submission_void_reason CHECK (
        void_reason_code IS NULL OR void_reason_code ~ '^[A-Z0-9_]+$'
    )
);

CREATE UNIQUE INDEX ux_assessment_submission_user_idempotency
    ON assessment_submission (user_id, idempotency_key)
    WHERE user_id IS NOT NULL;

CREATE UNIQUE INDEX ux_assessment_submission_anonymous_idempotency
    ON assessment_submission (anonymous_session_id, idempotency_key)
    WHERE anonymous_session_id IS NOT NULL;

CREATE INDEX ix_assessment_submission_user_history
    ON assessment_submission (user_id, submitted_at DESC, id DESC)
    WHERE user_id IS NOT NULL AND voided_at IS NULL;

CREATE INDEX ix_assessment_submission_anonymous_expiry
    ON assessment_submission (retention_expires_at, id)
    WHERE anonymous_session_id IS NOT NULL;

CREATE TABLE assessment_answer (
    submission_id uuid NOT NULL,
    definition_id uuid NOT NULL,
    question_id uuid NOT NULL,
    answer_value smallint NOT NULL,
    PRIMARY KEY (submission_id, question_id),
    CONSTRAINT fk_assessment_answer_submission_definition
        FOREIGN KEY (submission_id, definition_id)
        REFERENCES assessment_submission(id, definition_id) ON DELETE CASCADE,
    CONSTRAINT fk_assessment_answer_question_definition
        FOREIGN KEY (definition_id, question_id)
        REFERENCES questionnaire_question(definition_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_assessment_answer_value CHECK (answer_value BETWEEN 0 AND 3)
);

CREATE TABLE assessment_result (
    submission_id uuid PRIMARY KEY REFERENCES assessment_submission(id) ON DELETE CASCADE,
    total_score smallint NOT NULL,
    screening_level varchar(24) NOT NULL,
    scoring_version varchar(32) NOT NULL,
    safety_item_positive boolean NOT NULL,
    disclaimer_code varchar(64) NOT NULL DEFAULT 'SCREENING_NOT_DIAGNOSIS',
    calculated_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_assessment_result_total_score CHECK (total_score BETWEEN 0 AND 27),
    CONSTRAINT ck_assessment_result_screening_level CHECK (screening_level IN (
        'MINIMAL', 'MILD', 'MODERATE', 'MODERATELY_SEVERE', 'SEVERE'
    )),
    CONSTRAINT ck_assessment_result_disclaimer CHECK (disclaimer_code = 'SCREENING_NOT_DIAGNOSIS')
);

CREATE TABLE outbox_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    message_type varchar(120) NOT NULL,
    schema_version varchar(24) NOT NULL,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id uuid NOT NULL,
    aggregate_version bigint NOT NULL,
    correlation_id uuid NOT NULL,
    payload jsonb NOT NULL,
    occurred_at timestamptz NOT NULL,
    published_at timestamptz,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_outbox_aggregate_message_version UNIQUE (
        aggregate_type, aggregate_id, aggregate_version, message_type
    ),
    CONSTRAINT ck_outbox_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_outbox_aggregate_version CHECK (aggregate_version >= 0),
    CONSTRAINT ck_outbox_attempt_count CHECK (attempt_count >= 0)
);

CREATE INDEX ix_outbox_pending
    ON outbox_event (COALESCE(next_attempt_at, occurred_at), occurred_at, id)
    WHERE published_at IS NULL;
