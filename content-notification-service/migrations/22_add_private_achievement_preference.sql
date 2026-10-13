-- Up Migration
-- Migration: 22_add_private_achievement_preference
-- Service: content-notification-service
-- Database: mentalbridge_content_notification
-- Story: MB-661 - Private multi-domain achievements

CREATE TABLE achievement_preference (
  owner_id          uuid         PRIMARY KEY,
  visibility_state varchar(16)  NOT NULL DEFAULT 'VISIBLE'
                   CHECK (visibility_state IN ('VISIBLE', 'HIDDEN')),
  version           bigint       NOT NULL DEFAULT 0 CHECK (version >= 0),
  updated_at        timestamptz  NOT NULL DEFAULT now()
);
