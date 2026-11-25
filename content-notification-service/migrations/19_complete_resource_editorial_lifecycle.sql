-- Up Migration
-- Migration: 19_complete_resource_editorial_lifecycle
-- Service: content-notification-service
-- Database: mentalbridge_content_notification
-- Story: MB-589 - Complete reviewed resource editorial lifecycle

CREATE TABLE resource_review_decision (
  id                uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
  resource_id       uuid         NOT NULL REFERENCES resource(id) ON DELETE CASCADE,
  content_version   bigint       NOT NULL CHECK (content_version >= 0),
  decision          varchar(16)  NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED')),
  reason_code       varchar(64)  NOT NULL CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  authority_version varchar(64)  NOT NULL,
  reviewed_by       uuid         NOT NULL,
  reviewed_at       timestamptz  NOT NULL DEFAULT now(),
  UNIQUE (resource_id, content_version)
);

CREATE TABLE resource_publication_snapshot (
  resource_id       uuid         NOT NULL REFERENCES resource(id),
  content_version   bigint       NOT NULL CHECK (content_version >= 0),
  review_decision_id uuid        REFERENCES resource_review_decision(id),
  snapshot          jsonb        NOT NULL,
  published_by      uuid         NOT NULL,
  published_at      timestamptz  NOT NULL DEFAULT now(),
  PRIMARY KEY (resource_id, content_version),
  CONSTRAINT ck_resource_publication_snapshot_object
    CHECK (jsonb_typeof(snapshot) = 'object')
);

CREATE TABLE resource_publication_withdrawal (
  resource_id       uuid         NOT NULL,
  content_version   bigint       NOT NULL,
  reason_code       varchar(32)  NOT NULL
                    CHECK (reason_code IN ('ARCHIVED', 'SUPERSEDED', 'SOURCE_WITHDRAWN')),
  withdrawn_by      uuid         NOT NULL,
  withdrawn_at      timestamptz  NOT NULL DEFAULT now(),
  PRIMARY KEY (resource_id, content_version),
  FOREIGN KEY (resource_id, content_version)
    REFERENCES resource_publication_snapshot(resource_id, content_version)
);

INSERT INTO resource_publication_snapshot
  (resource_id, content_version, snapshot, published_by, published_at)
SELECT id, version, to_jsonb(resource), reviewed_by, COALESCE(reviewed_at, updated_at)
FROM resource
WHERE status IN ('PUBLISHED', 'ARCHIVED')
  AND reviewed_by IS NOT NULL
ON CONFLICT (resource_id, content_version) DO NOTHING;

INSERT INTO resource_publication_withdrawal
  (resource_id, content_version, reason_code, withdrawn_by, withdrawn_at)
SELECT id, version, 'ARCHIVED', reviewed_by, updated_at
FROM resource
WHERE status = 'ARCHIVED'
  AND reviewed_by IS NOT NULL
ON CONFLICT (resource_id, content_version) DO NOTHING;

ALTER TABLE resource_audit_event DROP CONSTRAINT ck_resource_audit_action;
ALTER TABLE resource_audit_event ADD CONSTRAINT ck_resource_audit_action
  CHECK (action IN (
    'RESOURCE_CREATED',
    'RESOURCE_UPDATED',
    'RESOURCE_DELETED',
    'RESOURCE_ARCHIVED',
    'RESOURCE_PUBLISH_BLOCKED',
    'RESOURCE_REVIEW_APPROVED',
    'RESOURCE_REVIEW_REJECTED',
    'RESOURCE_PUBLISHED',
    'RESOURCE_VERSION_CREATED',
    'ELIGIBILITY_PUBLISHED',
    'ELIGIBILITY_WITHDRAWN'
  ));

CREATE FUNCTION reject_resource_editorial_history_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'resource editorial history is append-only' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER resource_review_decision_immutable
  BEFORE UPDATE OR DELETE ON resource_review_decision
  FOR EACH ROW EXECUTE FUNCTION reject_resource_editorial_history_mutation();

CREATE TRIGGER resource_publication_snapshot_immutable
  BEFORE UPDATE OR DELETE ON resource_publication_snapshot
  FOR EACH ROW EXECUTE FUNCTION reject_resource_editorial_history_mutation();

CREATE TRIGGER resource_publication_withdrawal_immutable
  BEFORE UPDATE OR DELETE ON resource_publication_withdrawal
  FOR EACH ROW EXECUTE FUNCTION reject_resource_editorial_history_mutation();

CREATE INDEX ix_resource_publication_history
  ON resource_publication_snapshot (resource_id, published_at DESC);
