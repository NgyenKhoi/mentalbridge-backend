import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { logicalModelFailures, markdownLinkFailures, postgresOwners } from './documentation-policy.mjs';
import { missingOriginalLines } from './verify-documentation-preservation.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const manifest = JSON.parse(readFileSync(path.join(root, 'docs/reconciliation/mb-718/manifest.json'), 'utf8'));
const git = (...args) => execFileSync('git', args, { cwd: root, encoding: 'utf8', maxBuffer: 4 * 1024 * 1024 }).replaceAll('\r\n', '\n');
const read = (directory, file) => readFileSync(path.join(directory, file), 'utf8').replaceAll('\r\n', '\n');
const statements = (sql) => sql.replace(/\/\*[\s\S]*?\*\//g, '').replace(/--[^\n]*/g, '')
  .split(';').map((statement) => statement.trim()).filter(Boolean).sort();
const sources = {
  dictionary: 'docs/database/postgresql-field-data-dictionary.md',
  sql: 'docs/domain-model/relational/postgresql-logical-schema.sql',
  trace: 'docs/requirements-traceability.md',
};
const traceFiles = git('ls-files', 'docs/requirements-traceability/').trim().split('\n').filter(Boolean);
const dictionaries = [sources.dictionary, ...postgresOwners.map((owner) => owner.dictionary), 'docs/database/postgresql/shared-concepts.md'];
const models = [sources.sql, ...postgresOwners.map((owner) => owner.model)];
const traceability = [sources.trace, ...traceFiles];
const baselineFiles = [...new Set([...dictionaries, ...models, ...traceability])];
const reports = [];

for (const entry of manifest.entries) {
  assert.equal(entry.status, 'PENDING_OWNER_REBASE');
  assert.equal(git('rev-parse', '--verify', `${entry.head}^{commit}`).trim(), entry.head);
  assert.equal(git('merge-base', manifest.base, entry.head).trim(), entry.mergeBase);
  const patchPath = path.resolve(root, entry.patch);
  assert.ok(patchPath.startsWith(path.join(root, 'docs', 'reconciliation', 'mb-718') + path.sep));
  const patchFiles = git('apply', '--numstat', patchPath).trim().split('\n').filter(Boolean).map((line) => line.split('\t')[2]);
  assert.deepEqual([...patchFiles].sort(), [...entry.destinations].sort(), `PR #${entry.pr} patch paths differ from its manifest`);
  for (const file of patchFiles) {
    assert.ok(postgresOwners.some((owner) => [owner.dictionary, owner.model].includes(file))
      || /^docs\/requirements-traceability\/stories\/mb-\d+\.md$/.test(file), `Unexpected patch path ${file}`);
  }

  const scratch = mkdtempSync(path.join(tmpdir(), 'mentalbridge-reconciliation-'));
  try {
    for (const file of baselineFiles) cpSync(path.join(root, file), path.join(scratch, file), { recursive: true });
    execFileSync('git', ['apply', '--check', patchPath], { cwd: scratch, encoding: 'utf8' });
    execFileSync('git', ['apply', patchPath], { cwd: scratch, encoding: 'utf8' });
    const expected = {};
    for (const [kind, file] of Object.entries(sources)) {
      const versions = [manifest.base, entry.mergeBase, entry.head].map((revision, index) => {
        const target = path.join(scratch, `${kind}-${index}`);
        writeFileSync(target, git('show', `${revision}:${file}`));
        return target;
      });
      expected[kind] = execFileSync('git', ['merge-file', '-p', ...versions], { encoding: 'utf8', maxBuffer: 4 * 1024 * 1024 });
    }

    const actualStatements = statements(postgresOwners.map((owner) => read(scratch, owner.model)).join('\n'));
    assert.deepEqual(actualStatements, statements(expected.sql), `PR #${entry.pr} logical SQL differs from its merged source`);
    const destinations = {
      dictionary: dictionaries,
      sql: models,
      trace: [...new Set([...traceability, ...entry.destinations.filter((file) => file.startsWith('docs/requirements-traceability/'))])],
    };
    const preservedLines = {};
    for (const [kind, files] of Object.entries(destinations)) {
      const missing = missingOriginalLines(expected[kind], sources[kind], files.map((file) => ({ file, content: read(scratch, file) })));
      assert.deepEqual(missing, [], `PR #${entry.pr} lost ${kind} source content`);
      preservedLines[kind] = expected[kind].split('\n').filter((line) => line.trim()).length;
    }
    for (const owner of postgresOwners) {
      assert.deepEqual(logicalModelFailures(owner.model, read(scratch, owner.model), owner), []);
    }
    for (const file of entry.evidencePaths) {
      git('cat-file', '-e', `${entry.head}:${file}`);
      assert.ok(entry.destinations.some((destination) => {
        if (!destination.endsWith('.md')) return false;
        return read(scratch, destination).includes(path.posix.relative(path.posix.dirname(destination), file));
      }), `PR #${entry.pr} evidence target is not routed from a destination`);
    }
    let checkedLinks = 0;
    for (const file of entry.destinations.filter((destination) => destination.endsWith('.md'))) {
      const content = read(scratch, file);
      for (const match of content.matchAll(/!?\[[^\]]*\]\((<[^>]+>|[^\s)]+)(?:\s+["'][^)]*)?\)/g)) {
        const href = match[1].replace(/^<|>$/g, '');
        if (/^(?:[a-z][a-z\d+.-]*:|\/\/)/i.test(href)) continue;
        const target = decodeURIComponent(href.split('#')[0].split('?')[0]);
        const relative = target ? path.posix.normalize(path.posix.join(path.posix.dirname(file), target)) : file;
        assert.ok(!relative.startsWith('../') && !path.isAbsolute(relative), `Link escapes repository: ${href}`);
        const temporaryTarget = path.join(scratch, relative);
        if (!existsSync(temporaryTarget)) {
          const currentTarget = path.join(root, relative);
          mkdirSync(path.dirname(temporaryTarget), { recursive: true });
          if (existsSync(currentTarget) && statSync(currentTarget).isDirectory()) mkdirSync(temporaryTarget, { recursive: true });
          else if (existsSync(currentTarget)) cpSync(currentTarget, temporaryTarget);
          else writeFileSync(temporaryTarget, git('show', `${entry.head}:${relative}`));
        }
        checkedLinks++;
      }
      assert.deepEqual(markdownLinkFailures(scratch, file, content), [], `PR #${entry.pr} moved-document links changed or broke`);
    }
    reports.push({ pr: entry.pr, head: entry.head, patchApplies: true, sqlStatements: actualStatements.length, preservedLines, checkedLinks, status: entry.status });
  } finally {
    assert.equal(path.dirname(scratch), path.resolve(tmpdir()));
    assert.ok(path.basename(scratch).startsWith('mentalbridge-reconciliation-'));
    if (existsSync(scratch)) rmSync(scratch, { recursive: true, force: true });
  }
}

console.log(JSON.stringify({ base: manifest.base, reports }, null, 2));
