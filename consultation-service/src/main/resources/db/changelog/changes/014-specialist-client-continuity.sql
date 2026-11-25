--liquibase formatted sql

--changeset mentalbridge:consultation-014-specialist-client-continuity runInTransaction:true
CREATE TABLE specialist_client_continuity_audit (
    id uuid PRIMARY KEY,
    specialist_account_id uuid NOT NULL,
    action varchar(16) NOT NULL,
    outcome varchar(16) NOT NULL,
    reason_code varchar(64) NOT NULL,
    relationship_count integer NOT NULL,
    occurred_at timestamptz NOT NULL,
    CONSTRAINT ck_specialist_client_continuity_action CHECK (action = 'LIST'),
    CONSTRAINT ck_specialist_client_continuity_outcome CHECK (outcome IN ('ALLOWED', 'DENIED')),
    CONSTRAINT ck_specialist_client_continuity_count CHECK (relationship_count >= 0)
);

CREATE INDEX ix_specialist_client_continuity_audit_actor_time
    ON specialist_client_continuity_audit (specialist_account_id, occurred_at DESC);
