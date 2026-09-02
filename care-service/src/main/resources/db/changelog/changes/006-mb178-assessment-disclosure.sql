--liquibase formatted sql

--changeset mentalbridge:care-010-assessment-disclosure-provenance runInTransaction:true
ALTER TABLE assessment_submission
    ADD COLUMN privacy_policy_version varchar(64);

UPDATE assessment_submission
SET privacy_policy_version = 'legacy-pre-mb178'
WHERE privacy_policy_version IS NULL;

ALTER TABLE assessment_submission
    ALTER COLUMN privacy_policy_version SET NOT NULL,
    ADD CONSTRAINT ck_assessment_submission_privacy_policy_version
        CHECK (length(btrim(privacy_policy_version)) > 0);
