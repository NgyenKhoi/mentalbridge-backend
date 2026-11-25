-- Up Migration
-- Controlled synthetic fixture only. It contains no production contact or personal data.

INSERT INTO safety_directory_entry (
  id, name, entry_type, phone, address, active, source_name, source_reference,
  source_retrieved_at, reviewed_by, reviewed_at, verified_by, verified_at, seed_key
)
VALUES (
  '00000000-0000-4000-8000-000000000701',
  'Cơ sở hỗ trợ kiểm thử (dữ liệu demo)',
  'FACILITY',
  'DEMO-NOT-DIALABLE',
  'Địa chỉ tổng hợp chỉ dùng cho kiểm thử',
  true,
  'MentalBridge controlled demo review',
  'urn:mentalbridge:controlled-demo:safety-directory:v1',
  now(),
  '00000000-0000-4000-8000-000000000001',
  now(),
  '00000000-0000-4000-8000-000000000001',
  now(),
  'controlled-demo-safety-directory-v1'
)
ON CONFLICT (seed_key) DO NOTHING;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM safety_directory_entry
    WHERE seed_key = 'controlled-demo-safety-directory-v1'
      AND id = '00000000-0000-4000-8000-000000000701'
      AND name = 'Cơ sở hỗ trợ kiểm thử (dữ liệu demo)'
      AND entry_type = 'FACILITY'
      AND phone = 'DEMO-NOT-DIALABLE'
      AND address = 'Địa chỉ tổng hợp chỉ dùng cho kiểm thử'
      AND active
      AND source_name = 'MentalBridge controlled demo review'
      AND source_reference = 'urn:mentalbridge:controlled-demo:safety-directory:v1'
      AND reviewed_by = '00000000-0000-4000-8000-000000000001'
      AND verified_by = '00000000-0000-4000-8000-000000000001'
  ) THEN
    RAISE EXCEPTION 'controlled safety directory seed differs from the reviewed release';
  END IF;
END $$;

INSERT INTO safety_directory_coverage (
  entry_id, ordinal, coverage_level, province_code, province_name, district_code, district_name
)
SELECT id, 0, 'PROVINCE', '79', 'Hồ Chí Minh', NULL, NULL
FROM safety_directory_entry
WHERE seed_key = 'controlled-demo-safety-directory-v1'
ON CONFLICT (entry_id, ordinal) DO NOTHING;

DO $$
BEGIN
  IF (SELECT count(*) FROM safety_directory_coverage
      WHERE entry_id = '00000000-0000-4000-8000-000000000701') <> 1
    OR NOT EXISTS (
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
    RAISE EXCEPTION 'controlled safety directory coverage differs from the reviewed release';
  END IF;
END $$;

INSERT INTO safety_directory_review_history (
  id, entry_id, record_version, action, actor_id, source_reference
)
SELECT
  '00000000-0000-4000-8000-000000000702', id, record_version, 'REVIEWED',
  '00000000-0000-4000-8000-000000000001', source_reference
FROM safety_directory_entry
WHERE seed_key = 'controlled-demo-safety-directory-v1'
ON CONFLICT (id) DO NOTHING;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM safety_directory_review_history
    WHERE id = '00000000-0000-4000-8000-000000000702'
      AND entry_id = '00000000-0000-4000-8000-000000000701'
      AND action = 'REVIEWED'
      AND actor_id = '00000000-0000-4000-8000-000000000001'
      AND source_reference = 'urn:mentalbridge:controlled-demo:safety-directory:v1'
  ) THEN
    RAISE EXCEPTION 'controlled safety directory review evidence differs from the reviewed release';
  END IF;
END $$;
