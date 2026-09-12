import assert from 'node:assert/strict';
import { readdir, readFile } from 'node:fs/promises';

const migrationDirectory = new URL('../migrations/', import.meta.url);
const migrationNames = (await readdir(migrationDirectory))
  .filter((name) => /^\d+_.+\.sql$/.test(name))
  .sort((left, right) => left.localeCompare(right, 'en', { numeric: true }));
const migrations = new Map(
  await Promise.all(
    migrationNames.map(async (name) => [
      name,
      await readFile(new URL(name, migrationDirectory), 'utf8'),
    ]),
  ),
);
const requiredMigration = (name) => {
  const sql = migrations.get(name);
  assert.ok(sql, `Missing owner migration ${name}`);
  return sql;
};

const baseline = requiredMigration('1_initial_schema.sql');
const hotlineRemoval = requiredMigration('2_remove_hotline_catalogue.sql');
const reviewProvenance = requiredMigration('3_add_review_provenance_fields.sql');
const legacyIdempotency = requiredMigration('4_add_idempotency_key.sql');
const commandRecords = requiredMigration('5_add_resource_command_records.sql');
const review1Seed = await readFile(
  new URL('../migrations/review1/1_seed_review1_controlled_resource.sql', import.meta.url),
  'utf8',
);

for (const [name, sql] of migrations) {
  assert.match(sql, /^-- Up Migration/m, `${name} must declare its direction`);
  assert.doesNotMatch(sql, /CREATE DATABASE|CREATE SCHEMA/i, `${name} must stay owner-scoped`);
}

for (const table of ['resource', 'notification_preference', 'notification']) {
  assert.match(baseline, new RegExp(`CREATE TABLE IF NOT EXISTS ${table}\\b`));
}

assert.match(baseline, /CREATE TABLE IF NOT EXISTS hotline\b/);
assert.match(hotlineRemoval, /^-- Up Migration/m);
assert.match(hotlineRemoval, /DROP TABLE IF EXISTS hotline\b/);
assert.match(reviewProvenance, /ck_resource_published_requires_review\b/);
assert.match(legacyIdempotency, /ADD COLUMN idempotency_key\b/);
assert.match(commandRecords, /CREATE TABLE resource_idempotency_record\b/);
assert.match(commandRecords, /PRIMARY KEY \(actor_id, operation, idempotency_key\)/);
assert.match(commandRecords, /CREATE TABLE resource_audit_event\b/);
assert.match(review1Seed, /^-- Up Migration/m);
assert.match(review1Seed, /INSERT INTO resource\b/);
assert.match(review1Seed, /Bài thực hành thở chậm \(dữ liệu demo\)/);
assert.match(review1Seed, /ON CONFLICT \(id\) DO NOTHING/);
assert.doesNotMatch(review1Seed, /CREATE DATABASE|CREATE SCHEMA/i);
console.log(
  'Validated every Content/Notification owner migration and controlled Review 1 resource',
);
