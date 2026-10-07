--liquibase formatted sql

--changeset mentalbridge:identity-009-audit-browse-support
ALTER TABLE security_audit_event
    ADD COLUMN source_service varchar(32) NOT NULL DEFAULT 'IDENTITY',
    ADD COLUMN domain varchar(64) NOT NULL DEFAULT 'ACCOUNT_ADMINISTRATION',
    ADD COLUMN actor_type varchar(16) NOT NULL DEFAULT 'ADMIN',
    ADD COLUMN actor_reference_hash char(64);

ALTER TABLE security_audit_event
    ADD CONSTRAINT ck_security_audit_actor_type CHECK (actor_type IN ('ADMIN', 'SYSTEM')),
    ADD CONSTRAINT ck_security_audit_actor_hash CHECK (
        actor_reference_hash IS NULL OR actor_reference_hash ~ '^[0-9a-f]{64}$'
    );

UPDATE security_audit_event
SET actor_type = CASE WHEN actor_id IS NOT NULL THEN 'ADMIN' ELSE 'SYSTEM' END,
    actor_reference_hash = CASE WHEN actor_id IS NOT NULL THEN encode(digest(actor_id::text, 'sha256'), 'hex') ELSE NULL END;

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

CREATE INDEX ix_security_audit_actor_reference
    ON security_audit_event (actor_reference_hash, occurred_at DESC, id DESC);

CREATE INDEX ix_security_audit_actor_type
    ON security_audit_event (actor_type, occurred_at DESC, id DESC);
