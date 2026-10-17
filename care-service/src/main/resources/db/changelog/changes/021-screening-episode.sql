--liquibase formatted sql

--changeset mentalbridge:care-022-screening-episode runInTransaction:true
CREATE TABLE screening_episode (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES user_profile(account_id) ON DELETE RESTRICT,
    purpose varchar(24) NOT NULL,
    status varchar(16) NOT NULL,
    phq9_assessment_id uuid,
    gad7_assessment_id uuid,
    support_evaluation_id uuid,
    presentation_evaluation_id uuid,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    completed_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ux_screening_episode_owner_reference UNIQUE (id, user_id),
    CONSTRAINT fk_screening_episode_phq9_owner
        FOREIGN KEY (phq9_assessment_id, user_id)
        REFERENCES assessment_submission(id, user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_screening_episode_gad7_owner
        FOREIGN KEY (gad7_assessment_id, user_id)
        REFERENCES assessment_submission(id, user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_screening_episode_support_evaluation_owner
        FOREIGN KEY (support_evaluation_id, user_id)
        REFERENCES support_evaluation_v2(id, user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_screening_episode_presentation_evaluation_owner
        FOREIGN KEY (presentation_evaluation_id, user_id)
        REFERENCES support_evaluation(id, user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_screening_episode_purpose CHECK (purpose IN ('INITIAL_CHECK','REASSESSMENT')),
    CONSTRAINT ck_screening_episode_status CHECK (status IN ('IN_PROGRESS','READY','COMPLETED')),
    CONSTRAINT ck_screening_episode_version CHECK (version >= 0),
    CONSTRAINT ck_screening_episode_distinct_evidence CHECK (
        phq9_assessment_id IS NULL OR gad7_assessment_id IS NULL
        OR phq9_assessment_id <> gad7_assessment_id
    ),
    CONSTRAINT ck_screening_episode_state CHECK (
        (status = 'IN_PROGRESS' AND support_evaluation_id IS NULL
            AND presentation_evaluation_id IS NULL AND completed_at IS NULL)
        OR (status = 'READY' AND phq9_assessment_id IS NOT NULL
            AND gad7_assessment_id IS NOT NULL AND support_evaluation_id IS NULL
            AND presentation_evaluation_id IS NULL AND completed_at IS NULL)
        OR (status = 'COMPLETED' AND phq9_assessment_id IS NOT NULL
            AND gad7_assessment_id IS NOT NULL AND support_evaluation_id IS NOT NULL
            AND completed_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX ux_screening_episode_open
    ON screening_episode (user_id, purpose)
    WHERE status IN ('IN_PROGRESS','READY');

CREATE INDEX ix_screening_episode_owner_history
    ON screening_episode (user_id, purpose, created_at DESC, id DESC);
