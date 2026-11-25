--liquibase formatted sql

--changeset mentalbridge:identity-007-retention-execution-stale-status runInTransaction:true
ALTER TABLE retention_execution_record
    DROP CONSTRAINT ck_retention_exec_status;

ALTER TABLE retention_execution_record
    ADD CONSTRAINT ck_retention_exec_status
    CHECK (job_status IN ('SUCCEEDED', 'FAILED', 'RUNNING', 'STALE', 'UNAVAILABLE'));
