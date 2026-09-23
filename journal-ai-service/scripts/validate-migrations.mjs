import assert from "node:assert/strict";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const migration = require("../migrations/001_journal_entries_baseline.cjs");
const commandsMigration = require("../migrations/002_journal_mutation_commands.cjs");
const replayMigration = require("../migrations/003_journal_replay_snapshots_and_cursor_index.cjs");
const moodMigration = require("../migrations/004_journal_revision_mood.cjs");
const analysisMigration = require("../migrations/005_exact_revision_analysis.cjs");
const emotionCheckInMigration = require("../migrations/006_daily_emotion_check_ins.cjs");
const routingMigration = require("../migrations/007_entitlement_aware_model_routing.cjs");
const benchmarkMigration = require("../migrations/008_ai_benchmark_metadata.cjs");
const longitudinalMigration = require("../migrations/009_longitudinal_context_analysis.cjs");
const companionChatMigration = require("../migrations/010_ai_companion_chat_quotas.cjs");

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
assert.equal(emotionCheckInMigration.collectionName, "emotion_check_ins");
assert.equal(typeof emotionCheckInMigration.up, "function");
assert.equal(typeof emotionCheckInMigration.down, "function");
assert.equal(
  emotionCheckInMigration.validator.$jsonSchema.properties.revisions.maxItems,
  32,
);
assert.equal(
  emotionCheckInMigration.validator.$jsonSchema.properties.commands.maxItems,
  33,
);
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
assert.equal(typeof routingMigration.up, "function");
assert.equal(typeof routingMigration.down, "function");
assert.ok(routingMigration.jobValidator.$jsonSchema.properties.route);
assert.deepEqual(
  routingMigration.resultValidator.$jsonSchema.properties.provider.enum,
  ["DETERMINISTIC_FAKE", "GEMINI", "OPENAI"],
);
assert.equal(benchmarkMigration.datasetCollection, "benchmark_datasets");
assert.equal(benchmarkMigration.runCollection, "benchmark_runs");
assert.equal(benchmarkMigration.caseResultCollection, "benchmark_case_results");
assert.equal(typeof benchmarkMigration.up, "function");
assert.equal(typeof benchmarkMigration.down, "function");
assert.equal(longitudinalMigration.jobCollection, "longitudinal_analysis_jobs");
assert.equal(
  longitudinalMigration.resultCollection,
  "journal_longitudinal_analysis_results",
);
assert.equal(typeof longitudinalMigration.up, "function");
assert.equal(typeof longitudinalMigration.down, "function");
assert.equal(
  longitudinalMigration.jobValidator.$jsonSchema.properties.route.properties
    .workload.enum[0],
  "LONGITUDINAL",
);
assert.equal(
  longitudinalMigration.resultValidator.$jsonSchema.properties.result.properties
    .changesComparedWithPreviousPeriod.items.properties.direction.enum[3],
  "INSUFFICIENT_DATA",
);
assert.deepEqual(
  benchmarkMigration.caseResultValidator.$jsonSchema.properties.provider.enum,
  ["GEMINI", "OPENAI"],
);
assert.ok(
  benchmarkMigration.runValidator.$jsonSchema.properties.candidates.items.required.includes(
    "inputCostMicroUsdPerMillionTokens",
  ),
);
assert.equal(
  benchmarkMigration.runValidator.$jsonSchema.properties.candidates.minItems,
  1,
);
assert.equal(
  benchmarkMigration.runValidator.$jsonSchema.properties.candidates.maxItems,
  2,
);
assert.equal(
  companionChatMigration.conversationCollection,
  "ai_companion_conversations",
);
assert.equal(
  companionChatMigration.quotaCollection,
  "ai_companion_quota_ledgers",
);
assert.equal(typeof companionChatMigration.up, "function");
assert.equal(typeof companionChatMigration.down, "function");
assert.equal(
  companionChatMigration.conversationValidator.$jsonSchema.properties.messages
    .items.properties.content.properties.algorithm.enum[0],
  "AES-256-GCM",
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
  "Validated Mongo migrations: 001_journal_entries_baseline.cjs through 010_ai_companion_chat_quotas.cjs",
);
