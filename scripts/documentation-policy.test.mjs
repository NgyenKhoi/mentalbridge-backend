import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, unlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import {
  documentationFailures,
  documentationRouters,
  logicalModelFailures,
  logicalModelWarnings,
  markdownLinkFailures,
  postgresOwners,
  postgresPairFailures,
} from './documentation-policy.mjs';

const warnings = `/*\n${logicalModelWarnings.join('\n')}\n*/\n`;
const ownerSql = (owner) => `${warnings}CREATE TABLE ${owner.namespace}.parent (id uuid PRIMARY KEY);\n`
  + `CREATE TABLE ${owner.namespace}.child (parent_id uuid REFERENCES ${owner.namespace}.parent(id));\n`;
const routerSql = `${warnings}/*\n${postgresOwners.map((owner) => `${owner.service}.sql`).join('\n')}\n*/\n`;

function fixture(t) {
  const root = mkdtempSync(path.join(tmpdir(), 'mentalbridge-documentation-policy-'));
  const write = (file, text) => {
    mkdirSync(path.dirname(path.join(root, file)), { recursive: true });
    writeFileSync(path.join(root, file), text);
  };
  t.after(() => {
    assert.equal(path.dirname(root), path.resolve(tmpdir()));
    assert.ok(path.basename(root).startsWith('mentalbridge-documentation-policy-'));
    rmSync(root, { recursive: true, force: true });
  });
  for (const owner of postgresOwners) {
    write(owner.dictionary, `# ${owner.service}\n[Logical model](../../domain-model/relational/${owner.service}.sql)\n`);
    write(owner.model, ownerSql(owner));
  }
  write(documentationRouters.relational, routerSql);
  write(documentationRouters.dictionary, postgresOwners.map((owner) => `[${owner.service}](postgresql/${owner.service}.md)`).join('\n'));
  write('docs/database/postgresql/shared-concepts.md', '# Shared concepts\n');
  write(documentationRouters.traceability, '[Baseline](requirements-traceability/baseline.md)\n[Decisions](requirements-traceability/decisions.md)\n[MB-718](requirements-traceability/stories/mb-718.md)\n');
  write('docs/requirements-traceability/baseline.md', '# Baseline\n');
  write('docs/requirements-traceability/decisions.md', '# Decisions\n');
  write('docs/requirements-traceability/stories/mb-718.md', '# MB-718\n');
  return { root, write };
}

test('all five PostgreSQL owners accept only their matching dictionary and logical model', () => {
  for (const owner of postgresOwners) {
    const migration = `${owner.migrationPrefix}new-owner-change.sql`;
    assert.deepEqual(postgresPairFailures([migration, owner.dictionary, owner.model], [owner.service]), []);
    const other = postgresOwners.find((entry) => entry !== owner);
    const failures = postgresPairFailures([migration, other.dictionary, other.model], [owner.service]);
    assert.equal(failures.length, 2);
    assert.ok(failures.every((failure) => failure.startsWith(owner.service)));
  }
});

test('touching shared routers cannot satisfy migration pairing', () => {
  const owner = postgresOwners[2];
  const failures = postgresPairFailures([
    `${owner.migrationPrefix}new-change.sql`,
    documentationRouters.dictionary,
    documentationRouters.relational,
  ], [owner.service]);
  assert.equal(failures.length, 2);
  assert.ok(failures.some((failure) => failure.includes(owner.dictionary)));
  assert.ok(failures.some((failure) => failure.includes(owner.model)));
});

test('a multiple-owner migration change requires every affected owner pair', () => {
  const [identity, care] = postgresOwners;
  const changed = [
    `${identity.migrationPrefix}001.yaml`, `${care.migrationPrefix}002.xml`,
    identity.dictionary, identity.model,
  ];
  const failures = postgresPairFailures(changed, [identity.service, care.service]);
  assert.equal(failures.length, 2);
  assert.ok(failures.every((failure) => failure.startsWith(care.service)));
});

test('index-only migration needs its dictionary but no material model update', () => {
  const owner = postgresOwners[4];
  const migration = `${owner.migrationPrefix}index-tuning.cjs`;
  assert.deepEqual(postgresPairFailures([migration, owner.dictionary]), []);
  assert.equal(postgresPairFailures([migration]).length, 1);
  assert.deepEqual(postgresPairFailures(['unrelated-service/migrations/001.sql']), []);
});

test('owner model accepts only locally defined physical foreign keys', () => {
  const owner = postgresOwners[0];
  assert.deepEqual(logicalModelFailures(owner.model, ownerSql(owner), owner), []);
  const crossOwner = ownerSql(owner).replace('REFERENCES identity.parent', 'REFERENCES care.parent');
  assert.match(logicalModelFailures(owner.model, crossOwner, owner).join('\n'), /foreign key outside its owner model: care.parent/);
  const absentTable = ownerSql(owner).replace('REFERENCES identity.parent', 'REFERENCES external_account');
  assert.match(logicalModelFailures(owner.model, absentTable, owner).join('\n'), /foreign key outside its owner model: external_account/);
  const wrongTable = ownerSql(owner).replace('CREATE TABLE identity.child', 'CREATE TABLE care.child');
  assert.match(logicalModelFailures(owner.model, wrongTable, owner).join('\n'), /defines a table outside identity-service/);
});

test('ALTER TABLE cannot introduce a foreign key from another owner', () => {
  const owner = postgresOwners[0];
  const localAlter = `${ownerSql(owner)}ALTER TABLE identity.child ADD FOREIGN KEY (parent_id) REFERENCES identity.parent(id);`;
  assert.deepEqual(logicalModelFailures(owner.model, localAlter, owner), []);
  for (const table of ['care.profile', 'IF EXISTS care.profile', 'ONLY care.profile', 'IF EXISTS ONLY "care"."profile"']) {
    const crossOwner = `${ownerSql(owner)}ALTER TABLE ${table} ADD FOREIGN KEY (parent_id) REFERENCES identity.parent(id);`;
    assert.match(logicalModelFailures(owner.model, crossOwner, owner).join('\n'), /alters a table outside identity-service: care.profile/);
  }
});

test('ordinary, escaped and dollar-quoted SQL literals are not table or foreign key declarations', () => {
  const owner = postgresOwners[0];
  const literals = [
    "'REFERENCES care.profile'",
    "'It''s text: CREATE TABLE care.profile; REFERENCES care.profile'",
    "E'It\\'s text: CREATE TABLE care.profile; REFERENCES care.profile'",
    '$$CREATE TABLE care.profile; REFERENCES care.profile;$$',
    '$example$CREATE TABLE care.profile; REFERENCES care.profile;$example$',
    '$$Text\nSOURCE care-service.sql;\n\\i care-service.sql\n$$',
  ];
  for (const literal of literals) {
    const sql = `${ownerSql(owner)}CREATE TABLE identity.explanation (content text DEFAULT ${literal});`;
    assert.deepEqual(logicalModelFailures(owner.model, sql, owner), [], literal);
  }
});

test('an owner source field is valid while executable SQL source directives fail', () => {
  const owner = postgresOwners[2];
  const withSourceField = `${ownerSql(owner)}CREATE TABLE consultation.evidence (\n  source varchar(64) NOT NULL\n);\n`;
  assert.deepEqual(logicalModelFailures(owner.model, withSourceField, owner), []);
  for (const directive of ['SOURCE identity-service.sql;', "SOURCE 'identity-service.sql';", 'SOURCE "identity-service.sql";', '\\i identity-service.sql', "\\i 'identity-service.sql'", '\\ir identity-service.sql']) {
    assert.match(logicalModelFailures(owner.model, `${withSourceField}${directive}`, owner).join('\n'), /executable source directives/);
  }
});

test('every owner file and the compatibility router retain non-execution warnings', () => {
  const owner = postgresOwners[0];
  for (const warning of logicalModelWarnings) {
    assert.match(logicalModelFailures(owner.model, ownerSql(owner).replace(warning, ''), owner).join('\n'), /missing required warning/);
    assert.match(logicalModelFailures(documentationRouters.relational, routerSql.replace(warning, '')).join('\n'), /missing required warning/);
  }
});

test('SQL compatibility router is comments only, never a runnable include or schema', () => {
  assert.deepEqual(logicalModelFailures(documentationRouters.relational, `${routerSql}/* CREATE TABLE and \\i are documentation */\n`), []);
  for (const statement of ['CREATE TABLE identity.test (id uuid);', 'ALTER TABLE identity.test ADD value text;', '\\i identity-service.sql', '\\ir care-service.sql', 'SOURCE care-service.sql;']) {
    assert.match(logicalModelFailures(documentationRouters.relational, `${routerSql}${statement}`).join('\n'), /comment-only index/);
  }
  assert.match(logicalModelFailures(documentationRouters.relational, routerSql.replace('care-service.sql', '')).join('\n'), /missing owner model entry/);
});

test('complete routed documentation and owner model sources pass', (t) => {
  const { root } = fixture(t);
  assert.deepEqual(documentationFailures(root), []);
});

test('missing split sources fail even if their router was edited to remove a link', (t) => {
  const { root, write } = fixture(t);
  unlinkSync(path.join(root, postgresOwners[2].dictionary));
  write(documentationRouters.dictionary, '# Dictionary index\n');
  assert.match(documentationFailures(root).join('\n'), /Missing split documentation source: docs\/database\/postgresql\/consultation-service.md/);
});

test('missing outbound target and stale incoming anchors to moved documents fail', (t) => {
  const { root, write } = fixture(t);
  write('docs/requirements-traceability/stories/mb-718.md', '# MB-718\n[Evidence](../../missing-evidence.md)\n');
  write('care-service/README.md', '# Care\n[Old section](../docs/requirements-traceability.md#removed-story-section)\n');
  const failures = documentationFailures(root);
  assert.match(failures.join('\n'), /mb-718.md has a missing local link target: ..\/..\/missing-evidence.md/);
  assert.match(failures.join('\n'), /care-service\/README.md has a missing local link anchor/);
});

test('new working-tree Markdown files are checked for inbound links to split docs', (t) => {
  const { root, write } = fixture(t);
  write('new-story.md', '[Broken owner link](docs/database/postgresql/absent-service.md)\n');
  assert.match(documentationFailures(root).join('\n'), /new-story.md has a missing local link target/);
});

test('link validation resolves rebased targets, duplicate headings and explicit legacy anchors', (t) => {
  const { root, write } = fixture(t);
  write('docs/database/postgresql/shared-concepts.md', '# Shared concepts\n## `Purpose`\n## `Purpose`\n<a id="legacy-section"></a>\n');
  const source = postgresOwners[0].dictionary;
  const text = '[One](shared-concepts.md#purpose)\n[Two](shared-concepts.md#purpose-1)\n[Legacy](shared-concepts.md#legacy-section)\n[Remote](https://example.invalid/never-fetched)\n';
  assert.deepEqual(markdownLinkFailures(root, source, text), []);
  assert.deepEqual(markdownLinkFailures(root, 'unrelated.md', '[Legacy unrelated](missing.md#old)'), []);
  assert.deepEqual(markdownLinkFailures(root, source, '```md\n[Example](not-a-source.md)\n```\n'), []);
});

test('a missing historical story remains detectable through the trace router', (t) => {
  const { root } = fixture(t);
  unlinkSync(path.join(root, 'docs/requirements-traceability/stories/mb-718.md'));
  assert.match(documentationFailures(root).join('\n'), /requirements-traceability.md has a missing local link target/);
  assert.ok(readFileSync(path.join(root, documentationRouters.traceability), 'utf8').includes('mb-718.md'));
});
