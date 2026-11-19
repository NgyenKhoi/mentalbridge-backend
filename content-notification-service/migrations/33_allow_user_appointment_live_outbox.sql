-- Up Migration
-- Migration: 33_allow_user_appointment_live_outbox
-- Service: content-notification-service
-- Story: MB-891

ALTER TABLE specialist_notification_live_outbox DROP CONSTRAINT ck_specialist_live_role;
ALTER TABLE specialist_notification_live_outbox
  ADD CONSTRAINT ck_specialist_live_role CHECK (recipient_role IN ('SPECIALIST', 'USER'));
