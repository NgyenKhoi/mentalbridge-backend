import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { missingOriginalLines, verifyPreservation } from './verify-documentation-preservation.mjs';

test('a missing occurrence cannot be masked by another identical field row', () => {
  const row = '| `version` | Optimistic version. |';
  const missing = missingOriginalLines(`${row}\n${row}`, 'docs/original.md', [
    { file: 'docs/owner.md', content: row },
  ]);
  assert.deepEqual(missing, [row]);
});

test('moved links preserve their original destination including spaced paths and fragments', () => {
  const original = '[Policy](policies/rules.md#current)\r\n[Source](<../Source (1).docx>)\r\n';
  const destinations = [{
    file: 'docs/owners/care.md',
    content: '[Policy](../policies/rules.md#current)\n[Source](<../../Source (1).docx>)\n',
  }];
  assert.deepEqual(missingOriginalLines(original, 'docs/original.md', destinations), []);
});

test('changed link destinations or factual text fail preservation', () => {
  const source = 'docs/original.md';
  const original = '[Policy](policies/rules.md#current)\nOwner is Care.\n';
  const missing = missingOriginalLines(original, source, [{
    file: 'docs/owners/care.md',
    content: '[Policy](../policies/rules.md#historical)\nOwner is Content.\n',
  }]);
  assert.equal(missing.length, 2);
});

test('a refreshed Git baseline preserves the new MB-676 record and its rebased evidence link', (t) => {
  const root = mkdtempSync(path.join(tmpdir(), 'mentalbridge-preservation-refresh-'));
  t.after(() => {
    assert.equal(path.dirname(root), path.resolve(tmpdir()));
    assert.ok(path.basename(root).startsWith('mentalbridge-preservation-refresh-'));
    rmSync(root, { recursive: true, force: true });
  });
  const write = (file, content) => {
    mkdirSync(path.dirname(path.join(root, file)), { recursive: true });
    writeFileSync(path.join(root, file), content);
  };
  const gitEnvironment = Object.fromEntries(Object.entries(process.env)
    .filter(([name]) => !name.startsWith('GIT_')));
  const git = (...args) => execFileSync('git', [
    '-c', 'user.name=Documentation Fixture',
    '-c', 'user.email=documentation-fixture@example.invalid',
    '-c', 'commit.gpgSign=false',
    '-c', 'core.hooksPath=.git/hooks',
    '-c', 'init.templateDir=',
    ...args,
  ], {
    cwd: root,
    encoding: 'utf8',
    env: {
      ...gitEnvironment,
      GIT_CONFIG_NOSYSTEM: '1',
      GIT_CONFIG_GLOBAL: path.join(root, 'no-global-git-config'),
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  }).trim();
  const sqlSource = 'docs/domain-model/relational/postgresql-logical-schema.sql';
  const sql = '/* Read-only documentation. */\nCREATE TABLE identity.account (id uuid PRIMARY KEY);\n';
  const dictionarySource = 'docs/database/postgresql-field-data-dictionary.md';
  const dictionary = '# PostgreSQL dictionary\n\n| `id` | Immutable account identifier. |\n';
  const traceabilitySource = 'docs/requirements-traceability.md';
  const existingRecord = 'Existing delivery record.\n';
  const mb676 = [
    'MB-676 (2026-10-22) adds the USER reminder overview under the existing MB-562,',
    'MB-564, MB-514 and MB-517 boundaries. OpenAPI v1.10.0 exposes factual daily',
    'activity, current preferences and bounded eligibility estimates without a new',
    'scheduler or persisted model. MB-678 consumes it through the mobile reminder',
    'feature. See [delivery evidence](mb-676-reminder-overview.md).',
  ].join('\n');

  write(sqlSource, sql);
  write(dictionarySource, dictionary);
  write(traceabilitySource, `# Requirements Traceability\n\n${existingRecord}`);
  git('init', '--quiet');
  git('add', '--', sqlSource, dictionarySource, traceabilitySource);
  git('commit', '--quiet', '-m', 'fixture: initial documentation baseline');
  const staleBaseline = git('rev-parse', 'HEAD');

  write(traceabilitySource, `# Requirements Traceability\n\n${mb676}\n\n${existingRecord}`);
  write('docs/mb-676-reminder-overview.md', '# MB-676 delivery evidence\n');
  git('add', '--', traceabilitySource, 'docs/mb-676-reminder-overview.md');
  git('commit', '--quiet', '-m', 'fixture: add reminder overview delivery record');
  const refreshedBaseline = git('rev-parse', 'HEAD');

  write(sqlSource, '/* Compatibility index. */\n');
  write('docs/domain-model/relational/identity-service.sql', sql);
  write(dictionarySource, '# Dictionary index\n');
  write('docs/database/postgresql/identity-service.md', dictionary);
  write(traceabilitySource, '# Requirements Traceability\n');
  write('docs/requirements-traceability/stories/mb-749.md', existingRecord);

  assert.equal(verifyPreservation(root, staleBaseline).baseline, staleBaseline);
  assert.throws(() => verifyPreservation(root, refreshedBaseline), /lost 5 original lines/);

  const movedRecord = mb676.replace('(mb-676-reminder-overview.md)', '(../../mb-676-reminder-overview.md)');
  const story = 'docs/requirements-traceability/stories/mb-676.md';
  write(story, `# MB-676\n\n${movedRecord}\n`);
  const result = verifyPreservation(root, refreshedBaseline);
  assert.equal(result.baseline, refreshedBaseline);
  assert.equal(result.traceability.originalNonblankLines, 7);

  write(story, `# MB-676\n\n${movedRecord.replace('../../mb-676-reminder-overview.md', '../../different-evidence.md')}\n`);
  assert.throws(() => verifyPreservation(root, refreshedBaseline), /lost 1 original lines/);
});
