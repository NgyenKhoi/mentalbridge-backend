-- Up Migration
-- Migration: 1_seed_review1_controlled_resource
-- Service:   content-notification-service
-- Database:  mentalbridge_content_notification
-- Schema:    public (service-owned database, default schema)

INSERT INTO resource (
  id,
  category,
  locale,
  title,
  summary,
  content_body,
  status,
  reviewed_by,
  reviewed_at,
  effective_at
)
VALUES (
  '00000000-0000-4000-8000-000000000101',
  'BREATHING',
  'vi-VN',
  'Bài thực hành thở chậm (dữ liệu demo)',
  'Nội dung tổng hợp dành riêng cho Review 1, không thay thế tư vấn chuyên môn.',
  'Ngồi ở tư thế thoải mái, hít vào chậm và thở ra chậm. Dừng lại nếu bạn thấy khó chịu.',
  'PUBLISHED',
  '00000000-0000-4000-8000-000000000001',
  TIMESTAMPTZ '2026-01-01 00:00:00+00',
  TIMESTAMPTZ '2026-01-01 00:00:00+00'
)
ON CONFLICT (id) DO NOTHING;
