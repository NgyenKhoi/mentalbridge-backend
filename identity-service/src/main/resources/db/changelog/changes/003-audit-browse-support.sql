--liquibase formatted sql

--changeset mentalbridge:identity-003-audit-browse-support
UPDATE security_audit_event
SET subject_reference_hash = encode(digest(account_id::text, 'sha256'), 'hex')
WHERE subject_reference_hash IS NULL
  AND account_id IS NOT NULL;

CREATE INDEX ix_security_audit_filter
    ON security_audit_event (action, outcome, occurred_at DESC, id DESC);

CREATE INDEX ix_security_audit_subject_reference
    ON security_audit_event (subject_reference_hash, occurred_at DESC, id DESC);
