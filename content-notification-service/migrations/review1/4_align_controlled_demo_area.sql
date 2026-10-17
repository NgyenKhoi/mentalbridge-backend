-- Up Migration
-- Controlled synthetic fixture verification only.
-- Never repair reviewed seed drift silently.

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM safety_directory_coverage
    WHERE entry_id = '00000000-0000-4000-8000-000000000701'
      AND ordinal = 0
      AND coverage_level = 'PROVINCE'
      AND province_code = '79'
      AND province_name = 'Hồ Chí Minh'
      AND district_code IS NULL
      AND district_name IS NULL
  ) THEN
    RAISE EXCEPTION 'controlled safety directory area differs from the reviewed release';
  END IF;
END $$;
