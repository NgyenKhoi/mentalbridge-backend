-- Up Migration
-- Migration: 10_align_mb603_timed_practice_duration
-- Service:   content-notification-service
-- Database:  mentalbridge_content_notification
-- Story:     MB-603 - keep displayed duration aligned with timed interaction data

UPDATE resource
SET interaction_config = jsonb_set(interaction_config, '{cycles}', '33'::jsonb, false),
    updated_at = now(),
    version = version + 1
WHERE id = '00000000-0000-4000-8000-000000000201'::uuid
  AND interaction_type = 'BREATHING_PACER';

UPDATE resource
SET interaction_config = jsonb_set(
      interaction_config,
      '{steps}',
      '[
        {"id":"hands","label":"Bàn tay","seconds":120},
        {"id":"shoulders","label":"Vai","seconds":120},
        {"id":"face","label":"Khuôn mặt","seconds":120},
        {"id":"body","label":"Bụng và thân người","seconds":120},
        {"id":"legs","label":"Chân","seconds":120}
      ]'::jsonb,
      false
    ),
    updated_at = now(),
    version = version + 1
WHERE id = '00000000-0000-4000-8000-000000000216'::uuid
  AND interaction_type = 'PROGRESSIVE_RELAXATION';

DO $$
BEGIN
  IF (SELECT ((interaction_config ->> 'inhaleSeconds')::integer
             + (interaction_config ->> 'exhaleSeconds')::integer
             + (interaction_config ->> 'holdSeconds')::integer)
            * (interaction_config ->> 'cycles')::integer
      FROM resource
      WHERE id = '00000000-0000-4000-8000-000000000201'::uuid) NOT BETWEEN 270 AND 330 THEN
    RAISE EXCEPTION 'Breathing practice duration does not match its five-minute label';
  END IF;

  IF (SELECT sum((step ->> 'seconds')::integer)
      FROM resource,
           jsonb_array_elements(interaction_config -> 'steps') AS step
      WHERE id = '00000000-0000-4000-8000-000000000216'::uuid) <> 600 THEN
    RAISE EXCEPTION 'Progressive relaxation duration does not match its ten-minute label';
  END IF;
END;
$$;
