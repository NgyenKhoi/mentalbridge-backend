-- Up Migration
-- Migration: 6_add_resource_eligibility_v1
-- Service: content-notification-service
-- Database: mentalbridge_content_notification
-- Story: 5103 - Resource Eligibility v1

CREATE TABLE resource_eligibility_publication (
  id                 uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
  resource_id        uuid         NOT NULL REFERENCES resource(id),
  content_version    bigint       NOT NULL CHECK (content_version >= 0),
  policy_version     varchar(64)  NOT NULL CHECK (policy_version = 'content-eligibility-v1'),
  locale             varchar(16)  NOT NULL CHECK (locale ~ '^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$'),
  effective_at       timestamptz  NOT NULL,
  expires_at         timestamptz,
  published_by       uuid         NOT NULL,
  published_at       timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT uq_resource_eligibility_exact_version UNIQUE (resource_id, content_version, policy_version),
  CONSTRAINT ck_resource_eligibility_window CHECK (expires_at IS NULL OR expires_at > effective_at)
);

CREATE TABLE resource_eligibility_declaration (
  publication_id     uuid         NOT NULL REFERENCES resource_eligibility_publication(id),
  target_domain      varchar(40)  NOT NULL
                     CHECK (target_domain IN ('DEPRESSIVE_SYMPTOMS', 'ANXIETY_SYMPTOMS')),
  eligibility_role   varchar(16)  NOT NULL CHECK (eligibility_role IN ('PRIMARY', 'ADJUNCT')),
  instrument         varchar(16)  NOT NULL CHECK (instrument IN ('PHQ_9', 'GAD_7')),
  screening_levels   text[]       NOT NULL CHECK (cardinality(screening_levels) > 0),
  support_tiers      text[]       NOT NULL CHECK (cardinality(support_tiers) > 0),
  PRIMARY KEY (publication_id, target_domain, instrument),
  CONSTRAINT ck_resource_eligibility_domain_instrument CHECK (
    (target_domain = 'DEPRESSIVE_SYMPTOMS' AND instrument = 'PHQ_9') OR
    (target_domain = 'ANXIETY_SYMPTOMS' AND instrument = 'GAD_7')
  ),
  CONSTRAINT ck_resource_eligibility_screening_levels CHECK (
    screening_levels <@ ARRAY['MINIMAL','MILD','MODERATE','MODERATELY_SEVERE','SEVERE']::text[] AND
    CASE WHEN instrument = 'GAD_7'
      THEN NOT screening_levels @> ARRAY['MODERATELY_SEVERE']::text[]
      ELSE true
    END
  ),
  CONSTRAINT ck_resource_eligibility_support_tiers CHECK (
    support_tiers <@ ARRAY[
      'SELF_GUIDED_SUPPORT',
      'PROFESSIONAL_SUPPORT_RECOMMENDED',
      'SAFETY_FOLLOW_UP_RECOMMENDED'
    ]::text[]
  )
);

CREATE TABLE resource_eligibility_withdrawal (
  publication_id    uuid         PRIMARY KEY REFERENCES resource_eligibility_publication(id),
  reason_code       varchar(32)  NOT NULL
                    CHECK (reason_code IN ('CONTENT_WITHDRAWN', 'POLICY_WITHDRAWN', 'SUPERSEDED')),
  withdrawn_by      uuid         NOT NULL,
  withdrawn_at      timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE resource_eligibility_command_record (
  actor_id            uuid         NOT NULL,
  operation           varchar(40)  NOT NULL
                      CHECK (operation IN ('PUBLISH_ELIGIBILITY', 'WITHDRAW_ELIGIBILITY')),
  idempotency_key     varchar(128) NOT NULL,
  request_fingerprint char(64)     NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
  publication_id      uuid         NOT NULL REFERENCES resource_eligibility_publication(id),
  response_snapshot   jsonb        NOT NULL,
  created_at          timestamptz  NOT NULL DEFAULT now(),
  PRIMARY KEY (actor_id, operation, idempotency_key)
);

CREATE INDEX ix_resource_eligibility_resolution
  ON resource_eligibility_publication (resource_id, content_version, policy_version);

CREATE INDEX ix_resource_eligibility_declaration_match
  ON resource_eligibility_declaration (target_domain, instrument, eligibility_role, publication_id);

ALTER TABLE resource_audit_event DROP CONSTRAINT ck_resource_audit_action;
ALTER TABLE resource_audit_event ADD CONSTRAINT ck_resource_audit_action
  CHECK (action IN (
    'RESOURCE_CREATED',
    'RESOURCE_UPDATED',
    'RESOURCE_DELETED',
    'RESOURCE_ARCHIVED',
    'RESOURCE_PUBLISH_BLOCKED',
    'ELIGIBILITY_PUBLISHED',
    'ELIGIBILITY_WITHDRAWN'
  ));

CREATE FUNCTION reject_resource_eligibility_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'resource eligibility records are append-only' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER resource_eligibility_publication_immutable
  BEFORE UPDATE OR DELETE ON resource_eligibility_publication
  FOR EACH ROW EXECUTE FUNCTION reject_resource_eligibility_mutation();

CREATE TRIGGER resource_eligibility_declaration_immutable
  BEFORE UPDATE OR DELETE ON resource_eligibility_declaration
  FOR EACH ROW EXECUTE FUNCTION reject_resource_eligibility_mutation();

CREATE TRIGGER resource_eligibility_withdrawal_immutable
  BEFORE UPDATE OR DELETE ON resource_eligibility_withdrawal
  FOR EACH ROW EXECUTE FUNCTION reject_resource_eligibility_mutation();

CREATE TRIGGER resource_eligibility_command_record_immutable
  BEFORE UPDATE OR DELETE ON resource_eligibility_command_record
  FOR EACH ROW EXECUTE FUNCTION reject_resource_eligibility_mutation();
