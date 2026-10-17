-- Up Migration
-- Migration: 8_add_safety_directory_area_alias
-- Service: content-notification-service
-- Policy: MB-VN-SAFETY-DIRECTORY-001
--
-- Introduces a reviewed, versioned area vocabulary independent of directory-entry
-- coverage rows. Manual location text is resolved exclusively through this table,
-- so resolution is deterministic regardless of how many directory entries cover a
-- province or district. Each row maps a normalised alias text to a single
-- (province_code, district_code) pair. The canonical flag marks the preferred
-- display label for each pair.

CREATE TABLE safety_directory_area_alias (
  id              uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
  alias_text      varchar(120)  NOT NULL CHECK (btrim(alias_text) <> ''),
  province_code   varchar(32)   NOT NULL CHECK (btrim(province_code) <> ''),
  district_code   varchar(32),
  canonical       boolean       NOT NULL DEFAULT false,
  seed_key        varchar(128)  UNIQUE,
  created_at      timestamptz   NOT NULL DEFAULT now(),
  CONSTRAINT ck_area_alias_district_requires_province CHECK (
    district_code IS NULL OR btrim(district_code) <> ''
  )
);

-- Case-insensitive unique index: each alias_text resolves to exactly one area pair.
CREATE UNIQUE INDEX uq_area_alias_text_normalised
  ON safety_directory_area_alias (lower(btrim(alias_text)));

-- Lookup index used by resolveManualLocation.
CREATE INDEX ix_area_alias_lookup
  ON safety_directory_area_alias (province_code, district_code);
