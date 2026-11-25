import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

function normalizedLines(content, file) {
  return content.replaceAll('\r\n', '\n').split('\n').map((line) => line.replace(
    /(!?\[[^\]]*\]\()(<[^>]+>|[^\s)]+)([^)]*\))/g,
    (match, before, destination, after) => {
      const target = destination.startsWith('<') ? destination.slice(1, -1) : destination;
      if (/^(?:[a-z][a-z\d+.-]*:|#|\/)/i.test(target)) return match;
      const [pathname, fragment] = target.split('#');
      const resolved = path.posix.normalize(path.posix.join(path.posix.dirname(file), pathname));
      return before + resolved + (fragment ? `#${fragment}` : '') + after;
    },
  ).trimEnd()).filter((line) => line.trim());
}

export function missingOriginalLines(original, source, destinations) {
  const available = new Map();
  for (const { file, content } of destinations) {
    for (const line of normalizedLines(content, file)) available.set(line, (available.get(line) ?? 0) + 1);
  }
  return normalizedLines(original, source).filter((line) => {
    const count = available.get(line) ?? 0;
    if (!count) return true;
    available.set(line, count - 1);
    return false;
  });
}

export function verifyPreservation(repositoryRoot, baseline) {
  const commit = execFileSync('git', ['rev-parse', '--verify', '--end-of-options', `${baseline}^{commit}`], {
    cwd: repositoryRoot, encoding: 'utf8',
  }).trim();
  const read = (file) => readFileSync(path.join(repositoryRoot, file), 'utf8').replaceAll('\r\n', '\n');
  const original = (file) => execFileSync('git', ['show', `${commit}:${file}`], {
    cwd: repositoryRoot, encoding: 'utf8',
  }).replaceAll('\r\n', '\n');
  const filesIn = (directory, extension) => readdirSync(path.join(repositoryRoot, directory), { withFileTypes: true })
    .flatMap((entry) => entry.isDirectory() ? filesIn(`${directory}/${entry.name}`, extension)
      : entry.name.endsWith(extension) ? [`${directory}/${entry.name}`] : []);
  const preserve = (source, files) => {
    const content = original(source);
    const missing = missingOriginalLines(content, source, files.map((file) => ({ file, content: read(file) })));
    assert.equal(missing.length, 0, `${source} lost ${missing.length} original lines:\n${missing.slice(0, 8).join('\n')}`);
    return { originalNonblankLines: normalizedLines(content, source).length, destinationFiles: files.length };
  };
  const sqlSource = 'docs/domain-model/relational/postgresql-logical-schema.sql';
  const sqlFiles = filesIn('docs/domain-model/relational', '.sql').filter((file) => file !== sqlSource);
  const statements = (text) => text.replace(/\/\*[\s\S]*?\*\//g, '').replace(/--[^\n]*/g, '')
    .split(';').map((statement) => statement.trim()).filter(Boolean).sort();
  const originalStatements = statements(original(sqlSource));
  assert.deepEqual(statements(sqlFiles.map(read).join('\n')), originalStatements, 'Logical SQL statements changed');
  return {
    baseline: commit,
    sql: {
      ...preserve(sqlSource, [sqlSource, ...sqlFiles]),
      statementsPreserved: originalStatements.length,
      tablesPreserved: originalStatements.filter((statement) => /^CREATE TABLE /.test(statement)).length,
    },
    dictionary: preserve('docs/database/postgresql-field-data-dictionary.md', [
      'docs/database/postgresql-field-data-dictionary.md', ...filesIn('docs/database/postgresql', '.md'),
    ]),
    traceability: preserve('docs/requirements-traceability.md', [
      'docs/requirements-traceability.md', ...filesIn('docs/requirements-traceability', '.md'),
    ]),
  };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  const baseIndex = process.argv.indexOf('--base');
  if (baseIndex < 0 || !process.argv[baseIndex + 1]) {
    throw new Error('Usage: node scripts/verify-documentation-preservation.mjs --base <pre-split-commit>');
  }
  const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  console.log(JSON.stringify(verifyPreservation(repositoryRoot, process.argv[baseIndex + 1]), null, 2));
}
