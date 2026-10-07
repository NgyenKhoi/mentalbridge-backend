--liquibase formatted sql

--changeset mentalbridge:identity-009-audit-browse-support
ALTER TABLE security_audit_event
    ADD COLUMN source_service varchar(32) NOT NULL DEFAULT 'IDENTITY',
    ADD COLUMN domain varchar(64) NOT NULL DEFAULT 'ACCOUNT_ADMINISTRATION';

UPDATE security_audit_event
SET subject_reference_hash = encode(digest(account_id::text, 'sha256'), 'hex')
WHERE subject_reference_hash IS NULL
  AND account_id IS NOT NULL;

CREATE INDEX ix_security_audit_filter
    ON security_audit_event (action, outcome, occurred_at DESC, id DESC);

CREATE INDEX ix_security_audit_subject_reference
    ON security_audit_event (subject_reference_hash, occurred_at DESC, id DESC);

CREATE INDEX ix_security_audit_service_domain
    ON security_audit_event (source_service, domain, occurred_at DESC, id DESC);
