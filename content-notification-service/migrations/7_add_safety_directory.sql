-- Up Migration
-- Migration: 7_add_safety_directory
-- Service: content-notification-service
-- Policy: MB-VN-SAFETY-DIRECTORY-001

CREATE TABLE safety_directory_entry (
  id                  uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
  name                varchar(200)  NOT NULL CHECK (btrim(name) <> ''),
  entry_type          varchar(16)   NOT NULL CHECK (entry_type IN ('FACILITY', 'HOTLINE')),
  phone               varchar(64)   NOT NULL CHECK (btrim(phone) <> ''),
  address             varchar(500),
  active              boolean       NOT NULL DEFAULT false,
  source_name         varchar(200)  NOT NULL CHECK (btrim(source_name) <> ''),
  source_reference    varchar(2000) NOT NULL CHECK (btrim(source_reference) <> ''),
  source_retrieved_at timestamptz   NOT NULL,
  source_checksum     char(64)      CHECK (source_checksum ~ '^[0-9a-f]{64}$'),
  reviewed_by         uuid,
  reviewed_at         timestamptz,
  verified_by         uuid,
  verified_at         timestamptz,
  seed_key            varchar(128)  UNIQUE,
  created_at          timestamptz   NOT NULL DEFAULT now(),
  updated_at          timestamptz   NOT NULL DEFAULT now(),
  record_version      bigint        NOT NULL DEFAULT 0 CHECK (record_version >= 0),
  CONSTRAINT ck_safety_directory_address CHECK (
    (entry_type = 'FACILITY' AND address IS NOT NULL AND btrim(address) <> '') OR
    (entry_type = 'HOTLINE' AND (address IS NULL OR btrim(address) <> ''))
  ),
  CONSTRAINT ck_safety_directory_review_pair CHECK (
    (reviewed_by IS NULL) = (reviewed_at IS NULL)
  ),
  CONSTRAINT ck_safety_directory_verification_pair CHECK (
    (verified_by IS NULL) = (verified_at IS NULL)
  ),
  CONSTRAINT ck_safety_directory_active_review CHECK (
    NOT active OR (reviewed_at IS NOT NULL AND verified_at IS NOT NULL)
  )
);

CREATE TABLE safety_directory_coverage (
  entry_id       uuid         NOT NULL REFERENCES safety_directory_entry(id) ON DELETE CASCADE,
  ordinal        smallint     NOT NULL CHECK (ordinal >= 0),
  coverage_level varchar(16)  NOT NULL CHECK (coverage_level IN ('NATIONWIDE', 'PROVINCE', 'DISTRICT')),
  province_code  varchar(32),
  province_name  varchar(120),
  district_code  varchar(32),
  district_name  varchar(120),
  PRIMARY KEY (entry_id, ordinal),
  CONSTRAINT ck_safety_directory_coverage_shape CHECK (
    (coverage_level = 'NATIONWIDE' AND province_code IS NULL AND province_name IS NULL
      AND district_code IS NULL AND district_name IS NULL) OR
    (coverage_level = 'PROVINCE' AND province_code IS NOT NULL AND btrim(province_code) <> ''
      AND province_name IS NOT NULL AND btrim(province_name) <> ''
      AND district_code IS NULL AND district_name IS NULL) OR
    (coverage_level = 'DISTRICT' AND province_code IS NOT NULL AND btrim(province_code) <> ''
      AND province_name IS NOT NULL AND btrim(province_name) <> ''
      AND district_code IS NOT NULL AND btrim(district_code) <> ''
      AND district_name IS NOT NULL AND btrim(district_name) <> '')
  )
);

CREATE TABLE safety_directory_review_history (
  id              uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
  entry_id        uuid         NOT NULL REFERENCES safety_directory_entry(id),
  record_version  bigint       NOT NULL CHECK (record_version >= 0),
  action          varchar(16)  NOT NULL CHECK (action IN ('REVIEWED', 'DEACTIVATED')),
  actor_id        uuid         NOT NULL,
  source_reference varchar(2000) NOT NULL,
  occurred_at     timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE safety_directory_command_record (
  actor_id            uuid         NOT NULL,
  operation           varchar(32)  NOT NULL CHECK (operation = 'CREATE_DIRECTORY_ENTRY'),
  idempotency_key     varchar(128) NOT NULL,
  request_fingerprint char(64)     NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
  entry_id            uuid         NOT NULL REFERENCES safety_directory_entry(id),
  created_at          timestamptz  NOT NULL DEFAULT now(),
  PRIMARY KEY (actor_id, operation, idempotency_key)
);

CREATE INDEX ix_safety_directory_lookup
  ON safety_directory_entry (active, verified_at DESC, id)
  WHERE active;

CREATE INDEX ix_safety_directory_coverage_area
  ON safety_directory_coverage (province_code, district_code, coverage_level, entry_id);

CREATE INDEX ix_safety_directory_review_history
  ON safety_directory_review_history (entry_id, occurred_at DESC, id DESC);
