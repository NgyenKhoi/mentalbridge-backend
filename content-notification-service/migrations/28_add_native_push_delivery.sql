-- Up Migration
-- Migration: 28_add_native_push_delivery
-- Service: content-notification-service
-- Story: MB-675 - secure device registration and native push delivery

CREATE TABLE push_device_registration (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id uuid NOT NULL,
  installation_id uuid NOT NULL,
  platform varchar(16) NOT NULL,
  provider varchar(16) NOT NULL,
  provider_token_ciphertext text NOT NULL,
  provider_token_iv varchar(32) NOT NULL,
  provider_token_auth_tag varchar(32) NOT NULL,
  provider_token_hash char(64) NOT NULL,
  permission_status varchar(16) NOT NULL,
  status varchar(16) NOT NULL,
  app_version varchar(32),
  last_registered_at timestamptz NOT NULL,
  revoked_at timestamptz,
  invalidated_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  CONSTRAINT uq_push_device_owner_installation UNIQUE (owner_id, installation_id),
  CONSTRAINT ck_push_device_platform CHECK (platform IN ('ANDROID', 'IOS')),
  CONSTRAINT ck_push_device_provider CHECK (provider = 'EXPO'),
  CONSTRAINT ck_push_device_permission CHECK (permission_status IN ('GRANTED', 'DENIED')),
  CONSTRAINT ck_push_device_status CHECK (status IN ('ACTIVE', 'DISABLED', 'REVOKED', 'INVALID')),
  CONSTRAINT ck_push_device_lifecycle CHECK (
    (status = 'REVOKED') = (revoked_at IS NOT NULL)
    AND (status = 'INVALID') = (invalidated_at IS NOT NULL)
  )
);

CREATE UNIQUE INDEX uq_push_device_active_token
  ON push_device_registration (provider, provider_token_hash)
  WHERE status IN ('ACTIVE', 'DISABLED');

CREATE INDEX ix_push_device_owner_active
  ON push_device_registration (owner_id, updated_at DESC)
  WHERE status = 'ACTIVE';

CREATE TABLE push_delivery_attempt (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  notification_id uuid NOT NULL REFERENCES notification(id),
  device_registration_id uuid NOT NULL REFERENCES push_device_registration(id),
  state varchar(20) NOT NULL DEFAULT 'PENDING',
  attempt_count smallint NOT NULL DEFAULT 0,
  available_at timestamptz NOT NULL DEFAULT now(),
  claimed_at timestamptz,
  provider_ticket_id varchar(160),
  failure_code varchar(48),
  last_attempt_at timestamptz,
  accepted_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_push_delivery_notification_device UNIQUE (notification_id, device_registration_id),
  CONSTRAINT ck_push_delivery_state CHECK (state IN ('PENDING', 'PROCESSING', 'ACCEPTED', 'FAILED', 'SUPPRESSED', 'UNKNOWN')),
  CONSTRAINT ck_push_delivery_attempts CHECK (attempt_count BETWEEN 0 AND 3),
  CONSTRAINT ck_push_delivery_acceptance CHECK (
    (state = 'ACCEPTED') = (accepted_at IS NOT NULL AND provider_ticket_id IS NOT NULL)
  )
);

CREATE INDEX ix_push_delivery_due
  ON push_delivery_attempt (available_at, id)
  WHERE state IN ('PENDING', 'FAILED');
