-- Up Migration
-- Migration: 3_add_review_provenance_fields
-- Service:   content-notification-service
-- Database:  mentalbridge_content_notification
-- Schema:    public (service-owned database, default schema)
-- Append-only — never edit after merge
-- Story:     MB-251 - Persist review provenance fields pending policy approval

-- Add review provenance tracking fields
-- All fields already exist in initial schema, this migration documents the review policy

COMMENT ON COLUMN resource.reviewed_by IS
  'Identity account ID from a separately approved review decision. Required while PUBLISHED.';

COMMENT ON COLUMN resource.reviewed_at IS
  'Timestamp of a separately approved review decision. Required while PUBLISHED.';

COMMENT ON COLUMN resource.effective_at IS
  'Optional future publication date. Resource becomes visible to users at this time.';

COMMENT ON COLUMN resource.expires_at IS
  'Optional expiration date. Resource automatically hidden after this time.';

COMMENT ON COLUMN resource.version IS
  'Optimistic locking version. Incremented on every update to prevent concurrent modification conflicts.';

-- Enforce review provenance rules for PUBLISHED status
ALTER TABLE resource DROP CONSTRAINT IF EXISTS ck_resource_published_requires_review;
ALTER TABLE resource ADD CONSTRAINT ck_resource_published_requires_review
  CHECK (
    (status != 'PUBLISHED') OR
    (status = 'PUBLISHED' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)
  );

-- Only DRAFT resources can be edited or deleted
-- PUBLISHED resources can only be ARCHIVED
-- ARCHIVED resources are immutable
COMMENT ON COLUMN resource.status IS
  'Resource lifecycle state: DRAFT (editable), PUBLISHED (immutable, can archive), ARCHIVED (immutable)';

