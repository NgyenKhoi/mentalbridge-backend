--liquibase formatted sql

--changeset mentalbridge:care-037-reassessment-summary-without-journal runInTransaction:true
ALTER TABLE reassessment_summary
    DROP CONSTRAINT ck_reassessment_summary_journal_reference;

ALTER TABLE reassessment_summary
    ADD CONSTRAINT ck_reassessment_summary_journal_reference CHECK (
        (summary_version = 'reassessment-summary-v1'
            AND journal_analysis_id IS NOT NULL AND journal_job_id IS NULL)
        OR (summary_version = 'reassessment-summary-v2'
            AND (journal_job_id IS NOT NULL OR journal_analysis_id IS NULL))
    );
