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
const resourceEligibility = requiredMigration('6_add_resource_eligibility_v1.sql');
const review1Seed = await readFile(
  new URL('../migrations/review1/1_seed_review1_controlled_resource.sql', import.meta.url),
  'utf8',
);
const initialEligibility = await readFile(
  new URL('../migrations/review1/2_publish_initial_resource_eligibility.sql', import.meta.url),
  'utf8',
);
const controlledDemoFixture = JSON.parse(
  await readFile(
    new URL(
      '../../contracts/fixtures/content/resource-eligibility-v1-controlled-demo.json',
      import.meta.url,
    ),
    'utf8',
  ),
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
for (const table of [
  'resource_eligibility_publication',
  'resource_eligibility_declaration',
  'resource_eligibility_withdrawal',
  'resource_eligibility_command_record',
]) {
  assert.match(resourceEligibility, new RegExp(`CREATE TABLE ${table}\\b`));
}
assert.match(resourceEligibility, /ck_resource_eligibility_domain_instrument\b/);
assert.match(resourceEligibility, /resource_eligibility_publication_immutable\b/);
assert.match(resourceEligibility, /uq_resource_eligibility_exact_version\b/);
assert.doesNotMatch(resourceEligibility, /ix_resource_eligibility_resolution\b/);
assert.match(review1Seed, /^-- Up Migration/m);
assert.match(review1Seed, /INSERT INTO resource\b/);
assert.match(review1Seed, /Bài thực hành thở chậm \(dữ liệu demo\)/);
assert.match(review1Seed, /ON CONFLICT \(id\) DO NOTHING/);
assert.doesNotMatch(review1Seed, /CREATE DATABASE|CREATE SCHEMA/i);
assert.match(initialEligibility, /^-- Up Migration/m);
assert.match(initialEligibility, /INSERT INTO resource_eligibility_publication\b/);
assert.match(initialEligibility, /INSERT INTO resource_eligibility_declaration\b/);
assert.match(
  initialEligibility,
  /ON CONFLICT \(resource_id, content_version, policy_version\) DO NOTHING/,
);
assert.match(
  initialEligibility,
  /controlled demo resource inventory differs from the reviewed eligibility ledger/,
);
assert.match(initialEligibility, /published demo eligibility differs from the reviewed matrix/);
assert.doesNotMatch(initialEligibility, /CREATE DATABASE|CREATE SCHEMA/i);
assert.equal(controlledDemoFixture.policyVersion, 'content-eligibility-v1');
assert.equal(controlledDemoFixture.locale, 'vi-VN');
assert.equal(controlledDemoFixture.reviewEvidence.storyKey, 'MB-337');
assert.deepEqual(controlledDemoFixture.reviewEvidence.subtaskKeys, [
  'MB-350',
  'MB-351',
  'MB-352',
  'MB-353',
]);
assert.match(controlledDemoFixture.reviewEvidence.reviewerId, /^[0-9a-f-]{36}$/);
assert.equal(
  controlledDemoFixture.reviewEvidence.dataBoundary,
  'SYNTHETIC_CONTROLLED_DEMO_NO_PRODUCTION_PERSONAL_DATA',
);
assert.equal(controlledDemoFixture.reviewEvidence.items.length, 6);
assert.equal(
  new Set(controlledDemoFixture.reviewEvidence.items.map(({ resourceId }) => resourceId)).size,
  6,
);
for (const item of controlledDemoFixture.reviewEvidence.items) {
  assert.equal(item.contentVersion, '0');
  assert.equal(item.locale, 'vi-VN');
  assert.equal(item.publicationState, 'PUBLISHED');
  assert.ok(item.sourceProvenance.length > 0);
  assert.deepEqual(item.decisions.map(({ targetDomain }) => targetDomain).sort(), [
    'ANXIETY_SYMPTOMS',
    'DEPRESSIVE_SYMPTOMS',
  ]);
  for (const decision of item.decisions) {
    assert.ok(decision.rationale.length > 0);
    if (decision.decision === 'ELIGIBLE') {
      assert.ok(['PRIMARY', 'ADJUNCT'].includes(decision.role));
      assert.ok(decision.screeningLevels.length > 0);
      assert.ok(decision.supportTiers.length > 0);
    } else {
      assert.equal(decision.decision, 'INELIGIBLE');
      assert.equal(decision.role, null);
      assert.deepEqual(decision.screeningLevels, []);
      assert.deepEqual(decision.supportTiers, []);
    }
  }
}
assert.equal(controlledDemoFixture.request.requests.length, 10);
assert.equal(controlledDemoFixture.expectedResults.length, 10);
assert.deepEqual(
  controlledDemoFixture.expectedResults.map(({ outcome }) => outcome),
  [
    'ELIGIBLE',
    'ELIGIBLE',
    'ELIGIBLE',
    'ELIGIBLE',
    'INELIGIBLE',
    'INELIGIBLE',
    'STALE',
    'INELIGIBLE',
    'INELIGIBLE',
    'INELIGIBLE',
  ],
);
assert.deepEqual(
  controlledDemoFixture.expectedResults.slice(-2).map(({ reasonCode }) => reasonCode),
  ['DOMAIN_OR_PATHWAY_NOT_ELIGIBLE', 'DOMAIN_OR_PATHWAY_NOT_ELIGIBLE'],
);
console.log(
  'Validated every Content/Notification owner migration and the controlled eligibility matrix',
);
