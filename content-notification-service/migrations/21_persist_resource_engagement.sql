-- Up Migration
-- Migration: 21_persist_resource_engagement
-- Service:   content-notification-service
-- Story:     MB-660 - persistent resource bookmarks and truthful bingo state

CREATE TABLE resource_bookmark (
  owner_id         uuid        NOT NULL,
  resource_id      uuid        NOT NULL,
  resource_version bigint      NOT NULL CHECK (resource_version >= 0),
  version          bigint      NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (owner_id, resource_id),
  FOREIGN KEY (resource_id, resource_version)
    REFERENCES resource_publication_snapshot(resource_id, content_version)
);

CREATE INDEX ix_resource_bookmark_owner_recent
  ON resource_bookmark (owner_id, created_at DESC, resource_id);

ALTER TABLE resource_weekly_bingo
  ADD COLUMN version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();

ALTER TABLE resource_weekly_bingo_item
  ADD COLUMN resource_version bigint,
  ADD COLUMN self_confirmed_at timestamptz;

-- Boards created before this migration did not retain a content version. Bind
-- those rows only to the latest publication that existed when the board was
-- created. The mutable resource row may already point at a later draft or
-- publication and therefore is not historical evidence.
UPDATE resource_weekly_bingo_item item
SET resource_version = (
  SELECT publication.content_version
  FROM resource_publication_snapshot publication
  JOIN resource_weekly_bingo board ON board.id = item.board_id
  WHERE publication.resource_id = item.resource_id
    AND publication.published_at <= board.created_at
  ORDER BY publication.published_at DESC, publication.content_version DESC
  LIMIT 1
);

-- If no publication snapshot predates the board, provenance is unrecoverable.
-- Remove that legacy item instead of inventing a version. The board itself is
-- retained and can truthfully be returned as an empty board.
DELETE FROM resource_weekly_bingo_item
WHERE resource_version IS NULL;

ALTER TABLE resource_weekly_bingo_item
  ALTER COLUMN resource_version SET NOT NULL,
  ADD CONSTRAINT fk_resource_weekly_bingo_item_publication
    FOREIGN KEY (resource_id, resource_version)
    REFERENCES resource_publication_snapshot(resource_id, content_version);

