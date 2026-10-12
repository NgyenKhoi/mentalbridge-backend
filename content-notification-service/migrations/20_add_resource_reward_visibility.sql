-- Up Migration
-- Migration: 20_add_resource_reward_visibility
-- Service: content-notification-service
-- Database: mentalbridge_content_notification
-- Story: MB-659 - Authoritative non-punitive resource rewards

CREATE TABLE resource_reward_preference (
  owner_id          uuid         PRIMARY KEY,
  visibility_state varchar(16)  NOT NULL DEFAULT 'VISIBLE'
                   CHECK (visibility_state IN ('VISIBLE', 'HIDDEN')),
  version           bigint       NOT NULL DEFAULT 0 CHECK (version >= 0),
  updated_at        timestamptz  NOT NULL DEFAULT now()
);
