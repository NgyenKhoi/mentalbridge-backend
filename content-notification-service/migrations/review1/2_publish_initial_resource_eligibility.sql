-- Up Migration
-- Migration: 2_publish_initial_resource_eligibility
-- Service: content-notification-service
-- Database: mentalbridge_content_notification
-- Story: MB-337 - Initial reviewed resource eligibility matrix

INSERT INTO resource (
  id, category, locale, title, summary, content_body, status,
  reviewed_by, reviewed_at, effective_at, version
)
VALUES
  (
    '00000000-0000-4000-8000-000000000102', 'ARTICLE', 'vi-VN',
    'Hiểu về dấu hiệu trầm cảm (dữ liệu demo)',
    'Tài liệu giáo dục tâm lý giúp nhận biết dấu hiệu và giới hạn của tự hỗ trợ.',
    'Các dấu hiệu có thể ảnh hưởng khác nhau đến mỗi người. Nội dung này giúp bạn quan sát trải nghiệm của mình và không thay thế đánh giá chuyên môn.',
    'PUBLISHED', '00000000-0000-4000-8000-000000000001',
    TIMESTAMPTZ '2026-09-15 00:00:00+00', TIMESTAMPTZ '2026-09-15 00:00:00+00', 0
  ),
  (
    '00000000-0000-4000-8000-000000000103', 'ARTICLE', 'vi-VN',
    'Hiểu về lo âu (dữ liệu demo)',
    'Tài liệu giáo dục tâm lý giúp nhận biết phản ứng lo âu và giới hạn của tự hỗ trợ.',
    'Lo âu có thể xuất hiện qua suy nghĩ, cảm xúc và phản ứng cơ thể. Nội dung này không thay thế đánh giá hoặc hỗ trợ chuyên môn.',
    'PUBLISHED', '00000000-0000-4000-8000-000000000001',
    TIMESTAMPTZ '2026-09-15 00:00:00+00', TIMESTAMPTZ '2026-09-15 00:00:00+00', 0
  ),
  (
    '00000000-0000-4000-8000-000000000104', 'VIDEO', 'vi-VN',
    'Bắt đầu một hoạt động nhỏ (dữ liệu demo)',
    'Bài thực hành kích hoạt hành vi với một bước nhỏ, cụ thể và vừa sức.',
    'Chọn một hoạt động đơn giản có ý nghĩa với bạn, xác định bước đầu tiên và dừng lại nếu hoạt động làm bạn khó chịu hơn.',
    'PUBLISHED', '00000000-0000-4000-8000-000000000001',
    TIMESTAMPTZ '2026-09-15 00:00:00+00', TIMESTAMPTZ '2026-09-15 00:00:00+00', 0
  ),
  (
    '00000000-0000-4000-8000-000000000105', 'ARTICLE', 'vi-VN',
    'Chuẩn bị cho giấc ngủ (dữ liệu demo)',
    'Gợi ý thói quen thư giãn trước giờ ngủ, chỉ dùng như nội dung bổ trợ.',
    'Thử giữ một khung giờ thư giãn ổn định và giảm kích thích trước khi ngủ. Nội dung này không phải điều trị rối loạn giấc ngủ.',
    'PUBLISHED', '00000000-0000-4000-8000-000000000001',
    TIMESTAMPTZ '2026-09-15 00:00:00+00', TIMESTAMPTZ '2026-09-15 00:00:00+00', 0
  ),
  (
    '00000000-0000-4000-8000-000000000106', 'JOURNALING', 'vi-VN',
    'Chuẩn bị trao đổi với chuyên gia (dữ liệu demo)',
    'Các câu hỏi gợi ý để người dùng chuẩn bị cho một cuộc trao đổi chuyên môn.',
    'Bạn có thể ghi lại điều đang gây khó khăn, điều đã thử và câu hỏi muốn trao đổi. Tài nguyên này không đặt lịch và không cung cấp hướng dẫn an toàn khẩn cấp.',
    'PUBLISHED', '00000000-0000-4000-8000-000000000001',
    TIMESTAMPTZ '2026-09-15 00:00:00+00', TIMESTAMPTZ '2026-09-15 00:00:00+00', 0
  )
ON CONFLICT (id) DO NOTHING;

DO $$
BEGIN
  IF EXISTS (
    WITH expected(id, category, title) AS (
      VALUES
        ('00000000-0000-4000-8000-000000000101'::uuid, 'BREATHING', 'Bài thực hành thở chậm (dữ liệu demo)'),
        ('00000000-0000-4000-8000-000000000102'::uuid, 'ARTICLE', 'Hiểu về dấu hiệu trầm cảm (dữ liệu demo)'),
        ('00000000-0000-4000-8000-000000000103'::uuid, 'ARTICLE', 'Hiểu về lo âu (dữ liệu demo)'),
        ('00000000-0000-4000-8000-000000000104'::uuid, 'VIDEO', 'Bắt đầu một hoạt động nhỏ (dữ liệu demo)'),
        ('00000000-0000-4000-8000-000000000105'::uuid, 'ARTICLE', 'Chuẩn bị cho giấc ngủ (dữ liệu demo)'),
        ('00000000-0000-4000-8000-000000000106'::uuid, 'JOURNALING', 'Chuẩn bị trao đổi với chuyên gia (dữ liệu demo)')
    )
    SELECT 1
    FROM expected e
    LEFT JOIN resource r ON r.id = e.id
    WHERE r.id IS NULL OR r.category <> e.category OR r.title <> e.title OR r.locale <> 'vi-VN'
       OR r.status <> 'PUBLISHED'
       OR r.reviewed_by <> '00000000-0000-4000-8000-000000000001'::uuid
       OR r.reviewed_at IS NULL OR r.version <> 0
  ) THEN
    RAISE EXCEPTION 'controlled demo resource inventory differs from the reviewed eligibility ledger';
  END IF;
END;
$$;

INSERT INTO resource_eligibility_publication (
  id, resource_id, content_version, policy_version, locale, effective_at,
  expires_at, published_by, published_at
)
SELECT
  publication_id, resource_id, 0, 'content-eligibility-v1', 'vi-VN',
  TIMESTAMPTZ '2026-09-15 00:00:00+00', NULL,
  '00000000-0000-4000-8000-000000000001'::uuid,
  TIMESTAMPTZ '2026-09-15 00:00:00+00'
FROM (
  VALUES
    ('00000000-0000-4000-9000-000000000101'::uuid, '00000000-0000-4000-8000-000000000101'::uuid),
    ('00000000-0000-4000-9000-000000000102'::uuid, '00000000-0000-4000-8000-000000000102'::uuid),
    ('00000000-0000-4000-9000-000000000103'::uuid, '00000000-0000-4000-8000-000000000103'::uuid),
    ('00000000-0000-4000-9000-000000000104'::uuid, '00000000-0000-4000-8000-000000000104'::uuid),
    ('00000000-0000-4000-9000-000000000105'::uuid, '00000000-0000-4000-8000-000000000105'::uuid),
    ('00000000-0000-4000-9000-000000000106'::uuid, '00000000-0000-4000-8000-000000000106'::uuid)
) AS reviewed(publication_id, resource_id)
ON CONFLICT (resource_id, content_version, policy_version) DO NOTHING;

INSERT INTO resource_eligibility_declaration (
  publication_id, target_domain, eligibility_role, instrument,
  screening_levels, support_tiers
)
VALUES
  ('00000000-0000-4000-9000-000000000101', 'DEPRESSIVE_SYMPTOMS', 'ADJUNCT', 'PHQ_9', ARRAY['MINIMAL','MILD','MODERATE','MODERATELY_SEVERE','SEVERE'], ARRAY['SELF_GUIDED_SUPPORT','PROFESSIONAL_SUPPORT_RECOMMENDED','SAFETY_FOLLOW_UP_RECOMMENDED']),
  ('00000000-0000-4000-9000-000000000101', 'ANXIETY_SYMPTOMS', 'PRIMARY', 'GAD_7', ARRAY['MINIMAL','MILD','MODERATE','SEVERE'], ARRAY['SELF_GUIDED_SUPPORT','PROFESSIONAL_SUPPORT_RECOMMENDED','SAFETY_FOLLOW_UP_RECOMMENDED']),
  ('00000000-0000-4000-9000-000000000102', 'DEPRESSIVE_SYMPTOMS', 'PRIMARY', 'PHQ_9', ARRAY['MINIMAL','MILD','MODERATE','MODERATELY_SEVERE','SEVERE'], ARRAY['SELF_GUIDED_SUPPORT','PROFESSIONAL_SUPPORT_RECOMMENDED','SAFETY_FOLLOW_UP_RECOMMENDED']),
  ('00000000-0000-4000-9000-000000000103', 'ANXIETY_SYMPTOMS', 'PRIMARY', 'GAD_7', ARRAY['MINIMAL','MILD','MODERATE','SEVERE'], ARRAY['SELF_GUIDED_SUPPORT','PROFESSIONAL_SUPPORT_RECOMMENDED','SAFETY_FOLLOW_UP_RECOMMENDED']),
  ('00000000-0000-4000-9000-000000000104', 'DEPRESSIVE_SYMPTOMS', 'PRIMARY', 'PHQ_9', ARRAY['MINIMAL','MILD','MODERATE','MODERATELY_SEVERE','SEVERE'], ARRAY['SELF_GUIDED_SUPPORT','PROFESSIONAL_SUPPORT_RECOMMENDED','SAFETY_FOLLOW_UP_RECOMMENDED']),
  ('00000000-0000-4000-9000-000000000104', 'ANXIETY_SYMPTOMS', 'ADJUNCT', 'GAD_7', ARRAY['MINIMAL','MILD','MODERATE','SEVERE'], ARRAY['SELF_GUIDED_SUPPORT','PROFESSIONAL_SUPPORT_RECOMMENDED','SAFETY_FOLLOW_UP_RECOMMENDED']),
  ('00000000-0000-4000-9000-000000000105', 'DEPRESSIVE_SYMPTOMS', 'ADJUNCT', 'PHQ_9', ARRAY['MINIMAL','MILD','MODERATE','MODERATELY_SEVERE','SEVERE'], ARRAY['SELF_GUIDED_SUPPORT','PROFESSIONAL_SUPPORT_RECOMMENDED','SAFETY_FOLLOW_UP_RECOMMENDED']),
  ('00000000-0000-4000-9000-000000000105', 'ANXIETY_SYMPTOMS', 'ADJUNCT', 'GAD_7', ARRAY['MINIMAL','MILD','MODERATE','SEVERE'], ARRAY['SELF_GUIDED_SUPPORT','PROFESSIONAL_SUPPORT_RECOMMENDED','SAFETY_FOLLOW_UP_RECOMMENDED']),
  ('00000000-0000-4000-9000-000000000106', 'DEPRESSIVE_SYMPTOMS', 'ADJUNCT', 'PHQ_9', ARRAY['MINIMAL','MILD','MODERATE','MODERATELY_SEVERE','SEVERE'], ARRAY['SELF_GUIDED_SUPPORT','PROFESSIONAL_SUPPORT_RECOMMENDED','SAFETY_FOLLOW_UP_RECOMMENDED']),
  ('00000000-0000-4000-9000-000000000106', 'ANXIETY_SYMPTOMS', 'ADJUNCT', 'GAD_7', ARRAY['MINIMAL','MILD','MODERATE','SEVERE'], ARRAY['SELF_GUIDED_SUPPORT','PROFESSIONAL_SUPPORT_RECOMMENDED','SAFETY_FOLLOW_UP_RECOMMENDED'])
ON CONFLICT (publication_id, target_domain, instrument) DO NOTHING;

DO $$
BEGIN
  IF EXISTS (
    WITH expected(publication_id, resource_id) AS (
      VALUES
        ('00000000-0000-4000-9000-000000000101'::uuid, '00000000-0000-4000-8000-000000000101'::uuid),
        ('00000000-0000-4000-9000-000000000102'::uuid, '00000000-0000-4000-8000-000000000102'::uuid),
        ('00000000-0000-4000-9000-000000000103'::uuid, '00000000-0000-4000-8000-000000000103'::uuid),
        ('00000000-0000-4000-9000-000000000104'::uuid, '00000000-0000-4000-8000-000000000104'::uuid),
        ('00000000-0000-4000-9000-000000000105'::uuid, '00000000-0000-4000-8000-000000000105'::uuid),
        ('00000000-0000-4000-9000-000000000106'::uuid, '00000000-0000-4000-8000-000000000106'::uuid)
    )
    SELECT 1
    FROM expected e
    LEFT JOIN resource_eligibility_publication p ON p.id = e.publication_id
    WHERE p.id IS NULL OR p.resource_id <> e.resource_id OR p.content_version <> 0
       OR p.policy_version <> 'content-eligibility-v1' OR p.locale <> 'vi-VN'
       OR p.effective_at <> TIMESTAMPTZ '2026-09-15 00:00:00+00'
       OR p.expires_at IS NOT NULL
       OR p.published_by <> '00000000-0000-4000-8000-000000000001'::uuid
       OR p.published_at <> TIMESTAMPTZ '2026-09-15 00:00:00+00'
  ) THEN
    RAISE EXCEPTION 'published demo eligibility differs from the reviewed matrix';
  END IF;

  IF EXISTS (
    WITH expected(publication_id, target_domain, eligibility_role, instrument, screening_levels, support_tiers) AS (
      VALUES
        ('00000000-0000-4000-9000-000000000101'::uuid, 'DEPRESSIVE_SYMPTOMS', 'ADJUNCT', 'PHQ_9', ARRAY['MINIMAL','MILD','MODERATE','MODERATELY_SEVERE','SEVERE']::text[], ARRAY['SELF_GUIDED_SUPPORT','PROFESSIONAL_SUPPORT_RECOMMENDED','SAFETY_FOLLOW_UP_RECOMMENDED']::text[]),
        ('00000000-0000-4000-9000-000000000101'::uuid, 'ANXIETY_SYMPTOMS', 'PRIMARY', 'GAD_7', ARRAY['MINIMAL','MILD','MODERATE','SEVERE']::text[], ARRAY['SELF_GUIDED_SUPPORT','PROFESSIONAL_SUPPORT_RECOMMENDED','SAFETY_FOLLOW_UP_RECOMMENDED']::text[]),
        ('00000000-0000-4000-9000-000000000102'::uuid, 'DEPRESSIVE_SYMPTOMS', 'PRIMARY', 'PHQ_9', ARRAY['MINIMAL','MILD','MODERATE','MODERATELY_SEVERE','SEVERE']::text[], ARRAY['SELF_GUIDED_SUPPORT','PROFESSIONAL_SUPPORT_RECOMMENDED','SAFETY_FOLLOW_UP_RECOMMENDED']::text[]),
        ('00000000-0000-4000-9000-000000000103'::uuid, 'ANXIETY_SYMPTOMS', 'PRIMARY', 'GAD_7', ARRAY['MINIMAL','MILD','MODERATE','SEVERE']::text[], ARRAY['SELF_GUIDED_SUPPORT','PROFESSIONAL_SUPPORT_RECOMMENDED','SAFETY_FOLLOW_UP_RECOMMENDED']::text[]),
        ('00000000-0000-4000-9000-000000000104'::uuid, 'DEPRESSIVE_SYMPTOMS', 'PRIMARY', 'PHQ_9', ARRAY['MINIMAL','MILD','MODERATE','MODERATELY_SEVERE','SEVERE']::text[], ARRAY['SELF_GUIDED_SUPPORT','PROFESSIONAL_SUPPORT_RECOMMENDED','SAFETY_FOLLOW_UP_RECOMMENDED']::text[]),
        ('00000000-0000-4000-9000-000000000104'::uuid, 'ANXIETY_SYMPTOMS', 'ADJUNCT', 'GAD_7', ARRAY['MINIMAL','MILD','MODERATE','SEVERE']::text[], ARRAY['SELF_GUIDED_SUPPORT','PROFESSIONAL_SUPPORT_RECOMMENDED','SAFETY_FOLLOW_UP_RECOMMENDED']::text[]),
        ('00000000-0000-4000-9000-000000000105'::uuid, 'DEPRESSIVE_SYMPTOMS', 'ADJUNCT', 'PHQ_9', ARRAY['MINIMAL','MILD','MODERATE','MODERATELY_SEVERE','SEVERE']::text[], ARRAY['SELF_GUIDED_SUPPORT','PROFESSIONAL_SUPPORT_RECOMMENDED','SAFETY_FOLLOW_UP_RECOMMENDED']::text[]),
        ('00000000-0000-4000-9000-000000000105'::uuid, 'ANXIETY_SYMPTOMS', 'ADJUNCT', 'GAD_7', ARRAY['MINIMAL','MILD','MODERATE','SEVERE']::text[], ARRAY['SELF_GUIDED_SUPPORT','PROFESSIONAL_SUPPORT_RECOMMENDED','SAFETY_FOLLOW_UP_RECOMMENDED']::text[]),
        ('00000000-0000-4000-9000-000000000106'::uuid, 'DEPRESSIVE_SYMPTOMS', 'ADJUNCT', 'PHQ_9', ARRAY['MINIMAL','MILD','MODERATE','MODERATELY_SEVERE','SEVERE']::text[], ARRAY['SELF_GUIDED_SUPPORT','PROFESSIONAL_SUPPORT_RECOMMENDED','SAFETY_FOLLOW_UP_RECOMMENDED']::text[]),
        ('00000000-0000-4000-9000-000000000106'::uuid, 'ANXIETY_SYMPTOMS', 'ADJUNCT', 'GAD_7', ARRAY['MINIMAL','MILD','MODERATE','SEVERE']::text[], ARRAY['SELF_GUIDED_SUPPORT','PROFESSIONAL_SUPPORT_RECOMMENDED','SAFETY_FOLLOW_UP_RECOMMENDED']::text[])
    )
    SELECT 1
    FROM expected e
    FULL JOIN resource_eligibility_declaration d
      ON d.publication_id = e.publication_id
     AND d.target_domain = e.target_domain
     AND d.instrument = e.instrument
    WHERE COALESCE(e.publication_id, d.publication_id)::text LIKE '00000000-0000-4000-9000-00000000010_'
      AND (
        e.publication_id IS NULL OR d.publication_id IS NULL
        OR d.eligibility_role <> e.eligibility_role
        OR d.screening_levels <> e.screening_levels
        OR d.support_tiers <> e.support_tiers
      )
  ) THEN
    RAISE EXCEPTION 'published demo eligibility declarations differ from the reviewed matrix';
  END IF;
END;
$$;
