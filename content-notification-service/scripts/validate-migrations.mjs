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
const safetyDirectory = requiredMigration('7_add_safety_directory.sql');
const safetyDirectoryAreaAlias = requiredMigration('8_add_safety_directory_area_alias.sql');
const resourceSourceProvenance = requiredMigration('9_add_resource_source_provenance.sql');
const review1Seed = await readFile(
  new URL('../migrations/review1/1_seed_review1_controlled_resource.sql', import.meta.url),
  'utf8',
);
const initialEligibility = await readFile(
  new URL('../migrations/review1/2_publish_initial_resource_eligibility.sql', import.meta.url),
  'utf8',
);
const safetyDirectoryDemo = await readFile(
  new URL('../migrations/review1/3_seed_safety_directory_controlled_demo.sql', import.meta.url),
  'utf8',
);
const safetyDirectoryAreaAliases = await readFile(
  new URL('../migrations/review1/5_seed_safety_directory_area_aliases.sql', import.meta.url),
  'utf8',
);
const reviewedResourceCatalogue = await readFile(
  new URL('../migrations/review1/6_seed_mb556_reviewed_resource_catalogue.sql', import.meta.url),
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
for (const table of [
  'safety_directory_entry',
  'safety_directory_coverage',
  'safety_directory_review_history',
  'safety_directory_command_record',
]) {
  assert.match(safetyDirectory, new RegExp(`CREATE TABLE ${table}\\b`));
}
assert.match(safetyDirectory, /ix_safety_directory_lookup/);
assert.doesNotMatch(safetyDirectory, /CREATE TABLE (?:IF NOT EXISTS )?hotline\b/i);
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
assert.match(safetyDirectoryDemo, /DEMO-NOT-DIALABLE/);
assert.match(safetyDirectoryDemo, /controlled-demo-safety-directory-v1/);
assert.match(safetyDirectoryDemo, /ON CONFLICT \(seed_key\) DO NOTHING/);
assert.match(safetyDirectoryDemo, /differs from the reviewed release/);
assert.doesNotMatch(safetyDirectoryDemo, /CREATE DATABASE|CREATE SCHEMA/i);
assert.match(safetyDirectoryAreaAlias, /CREATE TABLE safety_directory_area_alias\b/);
assert.match(safetyDirectoryAreaAlias, /uq_area_alias_text_normalised\b/);
assert.match(safetyDirectoryAreaAlias, /lower\(btrim\(alias_text\)\)/);
assert.match(safetyDirectoryAreaAlias, /ix_area_alias_lookup\b/);
assert.doesNotMatch(safetyDirectoryAreaAlias, /safety_directory_coverage/);
for (const column of [
  'source_organization',
  'source_title',
  'source_url',
  'source_review_note',
  'catalogue_visibility',
]) {
  assert.match(resourceSourceProvenance, new RegExp(`ADD COLUMN ${column}\\b`));
}
assert.match(resourceSourceProvenance, /ck_resource_published_source\b/);
assert.match(resourceSourceProvenance, /ck_resource_video_external_url\b/);
assert.match(resourceSourceProvenance, /youtube\\\.com\|youtu\\\.be/);
assert.match(safetyDirectoryAreaAliases, /area-alias-ha-noi-canonical/);
assert.match(safetyDirectoryAreaAliases, /area-alias-da-nang-canonical/);
assert.match(safetyDirectoryAreaAliases, /area-alias-hcm-canonical/);
assert.match(safetyDirectoryAreaAliases, /ON CONFLICT \(seed_key\) DO NOTHING/);
assert.match(safetyDirectoryAreaAliases, /differs from reviewed release/);
assert.doesNotMatch(safetyDirectoryAreaAliases, /CREATE DATABASE|CREATE SCHEMA/i);
assert.match(reviewedResourceCatalogue, /^-- Up Migration/m);
assert.match(reviewedResourceCatalogue, /Story: MB-556/);
assert.match(reviewedResourceCatalogue, /catalogue_visibility = 'DIRECT_ONLY'/);
assert.match(reviewedResourceCatalogue, /INSERT INTO resource\b/);
assert.match(reviewedResourceCatalogue, /INSERT INTO resource_eligibility_publication\b/);
assert.match(reviewedResourceCatalogue, /INSERT INTO resource_eligibility_declaration\b/);
assert.match(reviewedResourceCatalogue, /https:\/\/www\.youtube\.com\/watch\?v=wfDTp2GogaQ/);
assert.match(reviewedResourceCatalogue, /https:\/\/www\.youtube\.com\/watch\?v=tfkhkFwCtxs/);
assert.match(reviewedResourceCatalogue, /https:\/\/www\.youtube\.com\/watch\?v=9GURt2pvdAg/);
assert.match(reviewedResourceCatalogue, /<> 15/);
assert.match(reviewedResourceCatalogue, /<> 27/);
assert.doesNotMatch(reviewedResourceCatalogue, /CREATE DATABASE|CREATE SCHEMA/i);
const areaAliasValues = safetyDirectoryAreaAliases.match(
  /INSERT INTO safety_directory_area_alias[\s\S]*?\bVALUES\s*([\s\S]*?)\s*ON CONFLICT \(seed_key\)/,
)?.[1];
assert.ok(areaAliasValues, 'Controlled area alias seed must contain an INSERT value list');
const areaAliases = [...areaAliasValues.matchAll(/\('[0-9a-f-]{36}',\s*'((?:''|[^'])*)',/gi)].map(
  ([, alias]) => alias.replaceAll("''", "'"),
);
const normalisedAreaAliases = areaAliases.map((alias) => alias.trim().toLowerCase());
assert.equal(
  new Set(normalisedAreaAliases).size,
  normalisedAreaAliases.length,
  'Controlled area aliases must be unique after lower(btrim(alias_text))',
);
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
