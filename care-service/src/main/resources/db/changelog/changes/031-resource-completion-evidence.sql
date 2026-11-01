--liquibase formatted sql

--changeset mentalbridge:care-032-resource-completion-evidence runInTransaction:true
ALTER TABLE support_plan_activity_occurrence
    DROP CONSTRAINT ck_support_plan_activity_occurrence_evidence,
    ADD CONSTRAINT ck_support_plan_activity_occurrence_evidence CHECK ((
        (source_action_id IS NULL
            AND evidence_owner IS NULL AND evidence_kind IS NULL AND evidence_id IS NULL
            AND evidence_revision IS NULL AND evidence_occurred_at IS NULL
            AND evidence_verified_at IS NULL)
        OR (source_action_id IS NOT NULL AND state <> 'COMPLETED'
            AND evidence_owner IS NULL AND evidence_kind IS NULL AND evidence_id IS NULL
            AND evidence_revision IS NULL AND evidence_occurred_at IS NULL
            AND evidence_verified_at IS NULL)
        OR (source_action_id IS NOT NULL AND state = 'COMPLETED' AND (
            (source_action_type IN ('RESOURCE', 'ROUTINE_SELF_MANAGEMENT')
                AND evidence_owner = 'CARE' AND evidence_kind = 'USER_ACKNOWLEDGEMENT'
                AND evidence_id IS NOT NULL AND length(btrim(evidence_id)) BETWEEN 1 AND 255
                AND (evidence_revision IS NULL OR length(btrim(evidence_revision)) BETWEEN 1 AND 128)
                AND evidence_occurred_at IS NOT NULL AND evidence_verified_at IS NOT NULL)
            OR (source_action_type = 'RESOURCE' AND evidence_owner = 'CONTENT'
                AND evidence_kind IN ('RESOURCE_PRACTICE_SESSION', 'RESOURCE_COMPLETION')
                AND evidence_id IS NOT NULL AND length(btrim(evidence_id)) BETWEEN 1 AND 255
                AND evidence_revision IS NOT NULL AND length(btrim(evidence_revision)) BETWEEN 1 AND 128
                AND evidence_occurred_at IS NOT NULL AND evidence_verified_at IS NOT NULL)
            OR (source_action_type = 'SELF_REPORT_REVIEW'
                AND evidence_owner = 'CARE' AND evidence_kind = 'SELF_REPORT_REVIEW'
                AND evidence_id IS NOT NULL AND length(btrim(evidence_id)) BETWEEN 1 AND 255
                AND (evidence_revision IS NULL OR length(btrim(evidence_revision)) BETWEEN 1 AND 128)
                AND evidence_occurred_at IS NOT NULL AND evidence_verified_at IS NOT NULL)
            OR (source_action_type = 'JOURNAL' AND evidence_owner = 'JOURNAL_AI'
                AND evidence_kind = 'JOURNAL_ENTRY' AND evidence_id IS NOT NULL
                AND length(btrim(evidence_id)) BETWEEN 1 AND 255
                AND (evidence_revision IS NULL OR length(btrim(evidence_revision)) BETWEEN 1 AND 128)
                AND evidence_occurred_at IS NOT NULL AND evidence_verified_at IS NOT NULL)
            OR (source_action_type = 'EMOTION_CHECK_IN' AND evidence_owner = 'JOURNAL_AI'
                AND evidence_kind = 'EMOTION_CHECK_IN' AND evidence_id IS NOT NULL
                AND length(btrim(evidence_id)) BETWEEN 1 AND 255
                AND (evidence_revision IS NULL OR length(btrim(evidence_revision)) BETWEEN 1 AND 128)
                AND evidence_occurred_at IS NOT NULL AND evidence_verified_at IS NOT NULL)
            OR (source_action_type = 'PROFESSIONAL_SUPPORT' AND evidence_owner = 'CONSULTATION'
                AND evidence_kind = 'APPOINTMENT_FULFILMENT' AND evidence_id IS NOT NULL
                AND length(btrim(evidence_id)) BETWEEN 1 AND 255
                AND (evidence_revision IS NULL OR length(btrim(evidence_revision)) BETWEEN 1 AND 128)
                AND evidence_occurred_at IS NOT NULL AND evidence_verified_at IS NOT NULL)
            OR (source_action_type = 'REASSESSMENT_SCREENING' AND evidence_owner = 'CARE'
                AND evidence_kind = 'ASSESSMENT_HISTORY' AND evidence_id IS NOT NULL
                AND length(btrim(evidence_id)) BETWEEN 1 AND 255
                AND (evidence_revision IS NULL OR length(btrim(evidence_revision)) BETWEEN 1 AND 128)
                AND evidence_occurred_at IS NOT NULL AND evidence_verified_at IS NOT NULL)
        ))) IS TRUE
        AND (evidence_verified_at IS NULL OR evidence_verified_at >= evidence_occurred_at)
    );

DROP INDEX ux_support_plan_activity_occurrence_evidence_fact;
CREATE UNIQUE INDEX ux_support_plan_activity_occurrence_evidence_fact
    ON support_plan_activity_occurrence (
        user_id, evidence_owner, evidence_kind, evidence_id, coalesce(evidence_revision, '')
    ) WHERE evidence_owner IS NOT NULL;
