--liquibase formatted sql

--changeset mentalbridge:care-005-assessment-safety-policy-provenance runInTransaction:true
ALTER TABLE assessment_result
    ADD COLUMN safety_status varchar(32),
    ADD COLUMN safety_policy_version varchar(64);

ALTER TABLE assessment_result
    ADD CONSTRAINT ck_assessment_result_safety_provenance CHECK (
        (safety_status IS NULL AND safety_policy_version IS NULL) OR
        (
            safety_status IN ('NEGATIVE_SAFETY_SCREEN', 'POSITIVE_SAFETY_SCREEN') AND
            length(btrim(safety_policy_version)) > 0 AND
            (
                (safety_item_positive = false AND safety_status = 'NEGATIVE_SAFETY_SCREEN') OR
                (safety_item_positive = true AND safety_status = 'POSITIVE_SAFETY_SCREEN')
            )
        )
    );
