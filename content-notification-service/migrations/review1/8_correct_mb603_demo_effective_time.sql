-- Up Migration
-- Migration: 8_correct_mb603_demo_effective_time
-- Service:   content-notification-service
-- Database:  mentalbridge_content_notification
-- Story:     MB-603 - make the local 2026-09-30 demo catalogue immediately available

UPDATE resource
SET source_retrieved_at = TIMESTAMPTZ '2026-09-29 00:00:00+00'
WHERE id BETWEEN '00000000-0000-4000-8000-000000000201'::uuid
             AND '00000000-0000-4000-8000-000000000223'::uuid
  AND content_version_label = 'mb-603-curated-v1';

UPDATE resource
SET reviewed_at = TIMESTAMPTZ '2026-09-29 00:00:00+00',
    effective_at = TIMESTAMPTZ '2026-09-29 00:00:00+00'
WHERE id BETWEEN '00000000-0000-4000-8000-000000000216'::uuid
             AND '00000000-0000-4000-8000-000000000223'::uuid
  AND content_version_label = 'mb-603-curated-v1';
