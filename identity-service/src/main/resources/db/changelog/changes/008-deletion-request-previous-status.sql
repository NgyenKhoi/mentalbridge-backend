--liquibase formatted sql

--changeset mentalbridge:identity-008-deletion-request-previous-status runInTransaction:true
ALTER TABLE deletion_request
    ADD COLUMN previous_status varchar(32);

ALTER TABLE deletion_request
    ADD CONSTRAINT ck_deletion_request_previous_status
    CHECK (previous_status IS NULL OR previous_status IN ('PENDING', 'IN_PROGRESS', 'FAILED'));
