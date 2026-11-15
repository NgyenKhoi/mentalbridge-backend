--liquibase formatted sql

--changeset mentalbridge:care-035-reassessment-symptom-checklist runInTransaction:true
ALTER TABLE reassessment_self_report
    ADD COLUMN symptom_catalog_revision varchar(128),
    ADD COLUMN symptom_codes text[] NOT NULL DEFAULT '{}',
    ADD CONSTRAINT ck_reassessment_self_report_symptoms CHECK (
        cardinality(symptom_codes) BETWEEN 0 AND 8
        AND array_position(symptom_codes, NULL) IS NULL
        AND ((symptom_catalog_revision IS NULL AND cardinality(symptom_codes) = 0)
            OR (symptom_catalog_revision IS NOT NULL
                AND length(btrim(symptom_catalog_revision)) BETWEEN 1 AND 128
                AND symptom_catalog_revision ~ '[^[:space:]]'))
        AND (deleted_at IS NULL
            OR (symptom_catalog_revision IS NULL AND cardinality(symptom_codes) = 0))
    );

ALTER TABLE support_plan
    ADD COLUMN checklist_provenance jsonb,
    ADD CONSTRAINT ck_support_plan_checklist_provenance CHECK (
        checklist_provenance IS NULL
        OR (model_version = 3 AND jsonb_typeof(checklist_provenance) = 'object')
    );
