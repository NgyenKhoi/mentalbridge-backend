-- Up Migration
-- Controlled demo area vocabulary for safety_directory_area_alias.
-- Covers the three provinces shown in the frontend province selector:
--   01  Hà Nội
--   48  Đà Nẵng
--   79  Hồ Chí Minh
-- Each province has a canonical alias (canonical = true) plus common alternate
-- spellings and abbreviations used by users. District-level aliases are seeded
-- for the controlled-demo entry (province 79 / Hồ Chí Minh) as a representative
-- example. No production address or personal data is included.
-- All alias_text values are unique after lower(btrim(...)).

INSERT INTO safety_directory_area_alias
  (id, alias_text, province_code, district_code, canonical, seed_key)
VALUES
  -- ── Hà Nội (province_code = '01') ─────────────────────────────────────────
  ('00000000-0000-4000-8000-000000000801', 'Hà Nội',        '01', NULL, true,  'area-alias-ha-noi-canonical'),
  ('00000000-0000-4000-8000-000000000802', 'Ha Noi',         '01', NULL, false, 'area-alias-ha-noi-ascii'),
  ('00000000-0000-4000-8000-000000000803', 'HN',             '01', NULL, false, 'area-alias-ha-noi-abbrev'),
  ('00000000-0000-4000-8000-000000000804', 'hanoi',          '01', NULL, false, 'area-alias-ha-noi-lower'),

  -- ── Đà Nẵng (province_code = '48') ────────────────────────────────────────
  ('00000000-0000-4000-8000-000000000811', 'Đà Nẵng',        '48', NULL, true,  'area-alias-da-nang-canonical'),
  ('00000000-0000-4000-8000-000000000812', 'Da Nang',         '48', NULL, false, 'area-alias-da-nang-ascii'),
  ('00000000-0000-4000-8000-000000000813', 'DN',              '48', NULL, false, 'area-alias-da-nang-abbrev'),
  ('00000000-0000-4000-8000-000000000814', 'danang',          '48', NULL, false, 'area-alias-da-nang-lower'),

  -- ── Hồ Chí Minh (province_code = '79') ────────────────────────────────────
  ('00000000-0000-4000-8000-000000000821', 'Hồ Chí Minh',    '79', NULL, true,  'area-alias-hcm-canonical'),
  ('00000000-0000-4000-8000-000000000822', 'Ho Chi Minh',     '79', NULL, false, 'area-alias-hcm-ascii'),
  ('00000000-0000-4000-8000-000000000823', 'HCM',             '79', NULL, false, 'area-alias-hcm-abbrev'),
  ('00000000-0000-4000-8000-000000000824', 'TPHCM',           '79', NULL, false, 'area-alias-hcm-tphcm'),
  ('00000000-0000-4000-8000-000000000825', 'Sài Gòn',         '79', NULL, false, 'area-alias-hcm-saigon'),
  ('00000000-0000-4000-8000-000000000826', 'Sai Gon',         '79', NULL, false, 'area-alias-hcm-saigon-ascii')
ON CONFLICT (seed_key) DO NOTHING;

-- ── Integrity assertions ────────────────────────────────────────────────────
DO $$
DECLARE
  v_count int;
BEGIN
  SELECT count(*) INTO v_count
  FROM safety_directory_area_alias
  WHERE seed_key IN (
    'area-alias-ha-noi-canonical',
    'area-alias-da-nang-canonical',
    'area-alias-hcm-canonical'
  )
    AND canonical = true;

  IF v_count <> 3 THEN
    RAISE EXCEPTION
      'controlled area alias seed: expected 3 canonical rows, found %', v_count;
  END IF;

  -- Each province must have exactly one canonical alias
  IF EXISTS (
    SELECT province_code
    FROM safety_directory_area_alias
    WHERE district_code IS NULL AND canonical = true
    GROUP BY province_code
    HAVING count(*) <> 1
  ) THEN
    RAISE EXCEPTION
      'controlled area alias seed: duplicate canonical alias for at least one province';
  END IF;

  -- Hà Nội
  IF NOT EXISTS (
    SELECT 1 FROM safety_directory_area_alias
    WHERE seed_key = 'area-alias-ha-noi-canonical'
      AND province_code = '01' AND district_code IS NULL AND canonical = true
  ) THEN
    RAISE EXCEPTION 'controlled area alias seed: Hà Nội canonical row differs from reviewed release';
  END IF;

  -- Đà Nẵng
  IF NOT EXISTS (
    SELECT 1 FROM safety_directory_area_alias
    WHERE seed_key = 'area-alias-da-nang-canonical'
      AND province_code = '48' AND district_code IS NULL AND canonical = true
  ) THEN
    RAISE EXCEPTION 'controlled area alias seed: Đà Nẵng canonical row differs from reviewed release';
  END IF;

  -- Hồ Chí Minh
  IF NOT EXISTS (
    SELECT 1 FROM safety_directory_area_alias
    WHERE seed_key = 'area-alias-hcm-canonical'
      AND province_code = '79' AND district_code IS NULL AND canonical = true
  ) THEN
    RAISE EXCEPTION 'controlled area alias seed: Hồ Chí Minh canonical row differs from reviewed release';
  END IF;
END $$;
