-- Up Migration
-- Migration: 9_add_resource_source_provenance
-- Service: content-notification-service
-- Database: mentalbridge_content_notification
-- Story: MB-556 - Reviewed resource catalogue and consumable resource detail

ALTER TABLE resource
  ADD COLUMN source_organization varchar(200),
  ADD COLUMN source_title varchar(500),
  ADD COLUMN source_url varchar(2048),
  ADD COLUMN source_review_note text,
  ADD COLUMN catalogue_visibility varchar(16) NOT NULL DEFAULT 'LISTED';

ALTER TABLE resource
  ADD CONSTRAINT ck_resource_catalogue_visibility
    CHECK (catalogue_visibility IN ('LISTED', 'DIRECT_ONLY')),
  ADD CONSTRAINT ck_resource_source_url_http
    CHECK (source_url IS NULL OR source_url ~ '^https?://'),
  ADD CONSTRAINT ck_resource_published_source
    CHECK (
      status <> 'PUBLISHED'
      OR id IN (
        '00000000-0000-4000-8000-000000000101'::uuid,
        '00000000-0000-4000-8000-000000000102'::uuid,
        '00000000-0000-4000-8000-000000000103'::uuid,
        '00000000-0000-4000-8000-000000000104'::uuid,
        '00000000-0000-4000-8000-000000000105'::uuid,
        '00000000-0000-4000-8000-000000000106'::uuid
      )
      OR (
        source_organization IS NOT NULL
        AND btrim(source_organization) <> ''
        AND source_title IS NOT NULL
        AND btrim(source_title) <> ''
        AND source_url IS NOT NULL
        AND source_review_note IS NOT NULL
        AND btrim(source_review_note) <> ''
      )
    ),
  ADD CONSTRAINT ck_resource_video_external_url
    CHECK (
      category <> 'VIDEO'
      OR id = '00000000-0000-4000-8000-000000000104'::uuid
      OR (
        external_url IS NOT NULL
        AND external_url ~ '^https://(www\.)?(youtube\.com|youtu\.be)/'
      )
    );
