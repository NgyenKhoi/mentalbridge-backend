-- Up Migration
-- Legacy idempotency field retained for migration compatibility; command replay uses migration 5.
ALTER TABLE resource
  ADD COLUMN idempotency_key VARCHAR(128);

-- Create unique index to enforce idempotency
CREATE UNIQUE INDEX idx_resource_idempotency_key
  ON resource (idempotency_key)
  WHERE idempotency_key IS NOT NULL;
