import assert from "node:assert/strict";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const migration = require("../migrations/001_journal_entries_baseline.cjs");
const commandsMigration = require("../migrations/002_journal_mutation_commands.cjs");
const replayMigration = require("../migrations/003_journal_replay_snapshots_and_cursor_index.cjs");
const moodMigration = require("../migrations/004_journal_revision_mood.cjs");
const analysisMigration = require("../migrations/005_exact_revision_analysis.cjs");

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
assert.equal(analysisMigration.jobCollection, "analysis_jobs");
assert.equal(analysisMigration.resultCollection, "journal_analysis_results");
assert.equal(typeof analysisMigration.up, "function");
assert.equal(typeof analysisMigration.down, "function");
assert.ok(
  analysisMigration.jobValidator.$jsonSchema.required.includes("keyHash"),
);
assert.ok(
  analysisMigration.jobValidator.$jsonSchema.required.includes("nextAttemptAt"),
);
assert.equal(
  analysisMigration.resultValidator.$jsonSchema.properties.provider.enum[0],
  "DETERMINISTIC_FAKE",
);
assert.equal(typeof commandsMigration.up, "function");
assert.equal(typeof commandsMigration.down, "function");
assert.ok(
  commandsMigration.validator.$jsonSchema.required.includes("commands"),
);
assert.equal(
  commandsMigration.validator.$jsonSchema.properties.commands.maxItems,
  32,
);
assert.equal(typeof replayMigration.up, "function");
assert.equal(typeof replayMigration.down, "function");
assert.equal(
  replayMigration.validator.$jsonSchema.properties.commands.maxItems,
  201,
);
assert.ok(
  replayMigration.validator.$jsonSchema.properties.commands.items.properties
    .response,
);
assert.equal(replayMigration.cursorIndex.deleted, 1);
assert.equal(typeof moodMigration.up, "function");
assert.equal(typeof moodMigration.down, "function");
assert.ok(
  moodMigration.validator.$jsonSchema.properties.revisions.items.properties
    .mood,
);
assert.equal(
  moodMigration.validator.$jsonSchema.properties.revisions.items.required.includes(
    "mood",
  ),
  false,
);

console.log(
  "Validated Mongo migrations: 001_journal_entries_baseline.cjs through 005_exact_revision_analysis.cjs",
);
