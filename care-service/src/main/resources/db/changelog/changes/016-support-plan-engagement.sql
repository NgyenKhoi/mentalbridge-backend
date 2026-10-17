--liquibase formatted sql

--changeset mentalbridge:care-017-support-plan-engagement runInTransaction:true
ALTER TABLE support_plan_activity_occurrence
    ADD COLUMN hidden boolean NOT NULL DEFAULT false,
    ADD COLUMN helpfulness varchar(24),
    ADD COLUMN barrier_code varchar(32),
    ADD COLUMN reflection varchar(500),
    ADD COLUMN summary_reuse_approved boolean NOT NULL DEFAULT false,
    ADD COLUMN engagement_updated_at timestamptz,
    ADD CONSTRAINT ck_support_plan_occurrence_helpfulness CHECK (
        helpfulness IS NULL OR helpfulness IN ('NOT_HELPFUL','A_LITTLE_HELPFUL','HELPFUL','VERY_HELPFUL')
    ),
    ADD CONSTRAINT ck_support_plan_occurrence_barrier CHECK (
        barrier_code IS NULL OR barrier_code IN (
            'LOW_ENERGY','NOT_ENOUGH_TIME','DIFFICULT_TO_START','NOT_A_GOOD_FIT','OTHER'
        )
    ),
    ADD CONSTRAINT ck_support_plan_occurrence_reflection CHECK (
        reflection IS NULL OR (length(btrim(reflection)) BETWEEN 1 AND 500)
    ),
    ADD CONSTRAINT ck_support_plan_occurrence_engagement CHECK (
        (state IN ('SCHEDULED','CANCELLED')
            AND helpfulness IS NULL AND barrier_code IS NULL AND reflection IS NULL
            AND summary_reuse_approved = false)
        OR (state = 'COMPLETED' AND barrier_code IS NULL)
        OR (state = 'SKIPPED' AND helpfulness IS NULL)
    );
