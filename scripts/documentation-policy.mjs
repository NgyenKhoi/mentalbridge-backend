import { existsSync, readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const postgresOwners = [
  ['identity-service', 'identity'],
  ['care-service', 'care'],
  ['consultation-service', 'consultation'],
  ['community-service', 'community'],
  ['content-notification-service', 'content'],
].map(([service, namespace]) => ({
  service,
  namespace,
  dictionary: `docs/database/postgresql/${service}.md`,
  model: `docs/domain-model/relational/${service}.sql`,
  migrationPrefix: service === 'content-notification-service'
    ? `${service}/migrations/`
    : `${service}/src/main/resources/db/changelog/`,
}));

export const documentationRouters = {
  dictionary: 'docs/database/postgresql-field-data-dictionary.md',
  relational: 'docs/domain-model/relational/postgresql-logical-schema.sql',
  traceability: 'docs/requirements-traceability.md',
};

export const logicalModelWarnings = [
  'MENTALBRIDGE CANONICAL LOGICAL DATA MODEL',
  'THIS FILE IS READ-ONLY AND NON-EXECUTABLE',
  'must never be used to provision, initialize',
  "Each service's owner-specific migration history",
];

export function postgresPairFailures(changedFiles = [], materialPostgresServices = []) {
  const changed = new Set(changedFiles);
  const material = new Set(materialPostgresServices);
  const failures = [];
  for (const owner of postgresOwners) {
    const migrationChanged = changedFiles.some((file) => file.startsWith(owner.migrationPrefix)
      && /\.(?:sql|ya?ml|xml|js|cjs|mjs|ts)$/.test(file));
    if (migrationChanged && !changed.has(owner.dictionary)) {
      failures.push(`${owner.service} PostgreSQL migration changed without its field dictionary: ${owner.dictionary}`);
    }
    if (material.has(owner.service) && !changed.has(owner.model)) {
      failures.push(`${owner.service} material PostgreSQL migration changed without its canonical relational model: ${owner.model}`);
    }
  }
  return failures;
}

function sqlCode(text, maskLiterals = false) {
  return text.replace(/[Ee]'(?:\\[\s\S]|''|[^'\\])*'|'(?:''|[^'])*'|"(?:""|[^"])*"|\$([A-Za-z_][A-Za-z_0-9]*)?\$[\s\S]*?\$\1\$|\/\*[\s\S]*?\*\/|--[^\r\n]*/g,
    (token) => {
      if (token.startsWith('/*') || token.startsWith('--')) return ' ';
      return maskLiterals && !token.startsWith('"') ? "''" : token;
    });
}

export function logicalModelFailures(file, text, owner) {
  const failures = logicalModelWarnings.filter((warning) => !text.includes(warning))
    .map((warning) => `${file} is missing required warning: ${warning}`);
  const sql = sqlCode(text).trim();
  if (!owner) {
    if (sql) failures.push(`${file} must remain a comment-only index without SQL or include directives`);
    for (const entry of postgresOwners) {
      if (!text.includes(`${entry.service}.sql`)) failures.push(`${file} is missing owner model entry: ${entry.model}`);
    }
    return failures;
  }
  const structuralSql = sqlCode(text, true);
  const identifiers = (expression) => [...structuralSql.matchAll(expression)]
    .map((match) => match[1].replaceAll('"', '').toLowerCase());
  const tables = identifiers(/\bCREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([\w".]+)/gi);
  const alteredTables = identifiers(/\bALTER\s+TABLE\s+(?:IF\s+EXISTS\s+)?(?:ONLY\s+)?([\w".]+)/gi);
  if (!tables.length) failures.push(`${file} is missing its owner table definitions`);
  for (const [action, names] of [['defines', tables], ['alters', alteredTables]]) {
    for (const table of names) {
      if (!table.startsWith(`${owner.namespace}.`)) {
        failures.push(`${file} ${action} a table outside ${owner.service}: ${table}`);
      }
    }
  }
  for (const target of identifiers(/\bREFERENCES\s+([\w".]+)/gi)) {
    const qualified = target.includes('.') ? target : `${owner.namespace}.${target}`;
    if (!tables.includes(qualified)) {
      failures.push(`${file} has a physical foreign key outside its owner model: ${target}`);
    }
  }
  if (/^\s*(?:\\(?:i|ir)\b[^\r\n]*|SOURCE\s+(?:''|"[^"]+"|[^\s;]+\.sql)\s*;?)\s*$/mi.test(structuralSql)) {
    failures.push(`${file} must not include executable source directives`);
  }
  return failures;
}

function withoutFences(text) {
  return text.replace(/^\s*(`{3,}|~{3,})[^\n]*\n[\s\S]*?^\s*\1\s*$/gm, '');
}

function markdownLinks(text) {
  return [...withoutFences(text).matchAll(/!?\[[^\]]*\]\((<[^>]+>|[^\s)]+)(?:\s+["'][^)]*)?\)/g)]
    .map((match) => match[1].replace(/^<|>$/g, ''));
}

function markdownAnchors(text) {
  const anchors = new Set();
  const counts = new Map();
  for (const match of withoutFences(text).matchAll(/^ {0,3}#{1,6}\s+(.+?)\s*#*\s*$/gm)) {
    const heading = match[1].replace(/<[^>]*>/g, '').replace(/\[([^\]]+)\]\([^)]*\)/g, '$1');
    const slug = heading.toLowerCase().replace(/[^\p{L}\p{N}\p{M}_\-\s]/gu, '').replace(/\s/g, '-');
    const count = counts.get(slug) ?? 0;
    anchors.add(count ? `${slug}-${count}` : slug);
    counts.set(slug, count + 1);
  }
  for (const match of text.matchAll(/\b(?:id|name)=["']([^"']+)["']/g)) anchors.add(match[1]);
  return anchors;
}

function scopedDocument(file) {
  return Object.values(documentationRouters).includes(file)
    || /^docs\/database\/postgresql\/[^/]+\.md$/.test(file)
    || /^docs\/domain-model\/relational\/[^/]+\.(?:sql|md)$/.test(file)
    || /^docs\/requirements-traceability\/.+\.md$/.test(file);
}

export function markdownLinkFailures(repositoryRoot, source, text) {
  const failures = [];
  for (const href of markdownLinks(text)) {
    if (/^(?:[a-z][a-z\d+.-]*:|\/\/)/i.test(href)) continue;
    const [rawTarget, rawFragment] = href.split('#', 2);
    let target;
    let fragment;
    try {
      target = decodeURIComponent(rawTarget.split('?')[0]);
      fragment = rawFragment ? decodeURIComponent(rawFragment) : '';
    } catch {
      failures.push(`${source} has an invalid encoded link: ${href}`);
      continue;
    }
    const absolute = target ? path.resolve(repositoryRoot, target.startsWith('/') ? `.${target}` : path.posix.join(path.posix.dirname(source), target))
      : path.resolve(repositoryRoot, source);
    const relative = path.relative(repositoryRoot, absolute).replaceAll('\\', '/');
    if (!scopedDocument(source) && !scopedDocument(relative)) continue;
    if (!existsSync(absolute)) {
      failures.push(`${source} has a missing local link target: ${href}`);
    } else if (fragment && relative.endsWith('.md') && !markdownAnchors(readFileSync(absolute, 'utf8')).has(fragment)) {
      failures.push(`${source} has a missing local link anchor: ${href}`);
    }
  }
  return failures;
}

function markdownFiles(repositoryRoot, directory = '') {
  const ignored = new Set(['.git', '.agents', '.codex', '.story-worktrees', 'node_modules', 'target', 'dist', 'build', '.next', 'coverage']);
  const files = [];
  for (const entry of readdirSync(path.join(repositoryRoot, directory), { withFileTypes: true })) {
    const file = path.posix.join(directory, entry.name);
    if (entry.isDirectory() && !ignored.has(entry.name)) files.push(...markdownFiles(repositoryRoot, file));
    else if (entry.isFile() && file.endsWith('.md')) files.push(file);
  }
  return files;
}

export function documentationFailures(repositoryRoot, input = {}) {
  const failures = postgresPairFailures(input.changedFiles, input.materialPostgresServices);
  const required = [
    ...Object.values(documentationRouters),
    ...postgresOwners.flatMap((owner) => [owner.dictionary, owner.model]),
    'docs/database/postgresql/shared-concepts.md',
    'docs/requirements-traceability/baseline.md',
    'docs/requirements-traceability/decisions.md',
  ];
  for (const file of required) {
    if (!existsSync(path.join(repositoryRoot, file))) failures.push(`Missing split documentation source: ${file}`);
  }
  for (const [file, owner] of [[documentationRouters.relational, undefined], ...postgresOwners.map((entry) => [entry.model, entry])]) {
    if (existsSync(path.join(repositoryRoot, file))) {
      failures.push(...logicalModelFailures(file, readFileSync(path.join(repositoryRoot, file), 'utf8'), owner));
    }
  }
  for (const file of markdownFiles(repositoryRoot)) {
    failures.push(...markdownLinkFailures(repositoryRoot, file, readFileSync(path.join(repositoryRoot, file), 'utf8')));
  }
  return [...new Set(failures)];
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  let stdin = '';
  for await (const chunk of process.stdin) stdin += chunk;
  const input = stdin.trim() ? JSON.parse(stdin) : {};
  const failures = documentationFailures(path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..'), input);
  if (process.argv.includes('--json')) {
    process.stdout.write(JSON.stringify({ failures }));
  } else if (failures.length) {
    console.error(`Documentation policy failed:\n- ${failures.join('\n- ')}`);
    process.exitCode = 1;
  } else {
    console.log('Documentation ownership, model warnings and moved-source links passed.');
  }
}
