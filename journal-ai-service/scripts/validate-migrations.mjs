import assert from "node:assert/strict";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const migration = require("../migrations/001_journal_entries_baseline.cjs");

assert.equal(migration.collectionName, "journal_entries");
assert.equal(typeof migration.up, "function");
assert.equal(typeof migration.down, "function");
assert.ok(migration.validator.$jsonSchema.required.includes("revisions"));
assert.ok(migration.validator.$jsonSchema.required.includes("cursor"));

const properties = migration.validator.$jsonSchema.properties;
assert.ok(properties.deleted);
assert.ok(properties.deletedAt);
assert.ok(properties.tombstoneReason);
assert.equal(
  migration.validator.$jsonSchema.properties.revisions.items.properties
    .contentPreview,
  undefined,
);

console.log(
  "Validated Mongo migration: migrations/001_journal_entries_baseline.cjs",
);
