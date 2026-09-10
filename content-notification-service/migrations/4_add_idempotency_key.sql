-- Add idempotency key column for create operation idempotency
ALTER TABLE resource
  ADD COLUMN idempotency_key VARCHAR(128);

-- Create unique index to enforce idempotency
CREATE UNIQUE INDEX idx_resource_idempotency_key
  ON resource (idempotency_key)
  WHERE idempotency_key IS NOT NULL;
