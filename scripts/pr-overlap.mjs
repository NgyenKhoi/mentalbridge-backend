#!/usr/bin/env node
// MB-706: report files this change shares with other open PRs into dev, plus
// integrator-controlled shared files (.github/CODEOWNERS) and PR-size warnings.
// Advisory only: it never fails a build because of what it finds.
//
//   node scripts/pr-overlap.mjs                   local: origin/dev...HEAD vs open PRs
//   node scripts/pr-overlap.mjs --pr 123          CI: PR #123 vs the other open PRs
//   node scripts/pr-overlap.mjs --pr 123 --comment  also create/update the PR comment
//
// Needs `git` and an authenticated `gh` (GH_TOKEN in CI).
// Same script lives in mentalbridge-backend and mentalbridge-frontend; keep them identical.
import { execFileSync } from 'node:child_process';
import { appendFileSync, existsSync, readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const MARKER = '<!-- pr-overlap -->';
const MAX_FILES = 30;
const GENERATED = /(\.generated\.ts|(^|\/)package-lock\.json)$/;
const escapeHtml = (value) => String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/[\r\n]/g, ' ');
const code = (value) => {
  const content = String(value).replace(/[\r\n]/g, ' ');
  const fence = '`'.repeat(1 + Math.max(0, ...Array.from(content.matchAll(/`+/g), (match) => match[0].length)));
  return `${fence} ${content} ${fence}`;
};
const text = (value) => escapeHtml(value).replace(/([\\`*_{}\[\]()#+.!|@])/g, '\\$1');

// shortcut: supports CODEOWNERS patterns rooted with "/" (dir/, file, simple * and **);
// unrooted gitignore-style patterns are treated as rooted. Upgrade if CODEOWNERS needs them.
export function parseCodeowners(text) {
  return text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith('#'))
    .map((line) => line.split(/\s+/)[0].replace(/^\//, ''));
}

export function matchesPattern(file, pattern) {
  if (pattern.endsWith('/')) return file.startsWith(pattern);
  if (pattern.includes('*')) {
    const source = pattern
      .split('**')
      .map((part) => part.split('*').map((s) => s.replace(/[.+?^${}()|[\]\\]/g, '\\$&')).join('[^/]*'))
      .join('.*');
    return new RegExp(`^${source}$`).test(file);
  }
  return file === pattern || file.startsWith(`${pattern}/`);
}

export function buildReport({ files, others, sharedPatterns }) {
  const own = files.filter((f) => !GENERATED.test(f));
  const ownSet = new Set(own);
  const overlaps = others
    .map((pr) => ({ ...pr, shared: pr.files.filter((f) => ownSet.has(f)) }))
    .filter((pr) => pr.shared.length > 0);
  const sharedTouched = files.filter((f) => sharedPatterns.some((p) => matchesPattern(f, p)));
  const warnings = [];
  if (own.length > MAX_FILES) {
    warnings.push(`${own.length} files changed (excluding generated); split PRs above ~${MAX_FILES}.`);
  }
  if (files.some((f) => f.startsWith('mentalbridge/')) && files.some((f) => f.startsWith('mobile/'))) {
    warnings.push('Web (`mentalbridge/`) and mobile (`mobile/`) change in one PR; open separate PRs.');
  }
  if (files.some((f) => /(^|\/)\.stitch\//.test(f))) {
    warnings.push('`.stitch/` captures are in the PR; keep them out of the repository.');
  }

  const lines = [MARKER, '### Changed-file overlap (MB-706)', ''];
  if (overlaps.length === 0) {
    lines.push('No file is shared with another open PR into `dev`.');
  } else {
    lines.push('Files also changed by other open PRs into `dev`. Agree the merge order with their owners before merging:', '');
    for (const pr of overlaps) {
      lines.push(`- #${pr.number} by @${pr.author} (${text(pr.title)}): ${pr.shared.length} file(s)`);
      for (const f of pr.shared.slice(0, 15)) lines.push(`  - ${code(f)}`);
      if (pr.shared.length > 15) lines.push(`  - ...and ${pr.shared.length - 15} more`);
    }
  }
  if (sharedTouched.length > 0) {
    lines.push('', 'Integrator-controlled shared files (`.github/CODEOWNERS`); change them in a narrow PR with owner approval:', '');
    for (const f of sharedTouched.slice(0, 20)) lines.push(`- ${code(f)}`);
    if (sharedTouched.length > 20) lines.push(`- ...and ${sharedTouched.length - 20} more`);
  }
  if (warnings.length > 0) {
    lines.push('', 'Warnings:', '', ...warnings.map((w) => `- ${w}`));
  }
  lines.push('', '_Advisory check; rules in MB-706 and the repository contributing guide. Local run: `node scripts/pr-overlap.mjs`._');
  const report = lines.join('\n');
  const markdown = report.length <= 50000 ? report : `${report.slice(0, report.lastIndexOf('\n', 49000))}\n\n_Report truncated; inspect the open PRs for the remaining file lists._`;
  return { markdown, hasFindings: overlaps.length + sharedTouched.length + warnings.length > 0 };
}

const run = (cmd, args, input) => execFileSync(cmd, args, { encoding: 'utf8', input, maxBuffer: 64 * 1024 * 1024 });

export function pullRequestFiles(prNumber, command = run) {
  return JSON.parse(command('gh', ['api', '--paginate', '--slurp', `repos/{owner}/{repo}/pulls/${prNumber}/files?per_page=100`])).flat().map((file) => file.filename);
}

export function openPullRequests(command = run) {
  const prs = JSON.parse(command('gh', ['api', '--paginate', '--slurp', 'repos/{owner}/{repo}/pulls?state=open&base=dev&per_page=100'])).flat();
  return prs.map((pr) => ({
    number: pr.number,
    title: pr.title,
    author: pr.user?.login ?? 'unknown',
    headRefName: pr.head.ref,
    files: pullRequestFiles(pr.number, command),
  }));
}

// Post only when there is something to say, but always refresh an earlier comment so it never goes stale.
export function upsertComment(prNumber, body, createIfMissing, command = run) {
  const actor = command('gh', ['api', 'graphql', '-f', 'query={ viewer { login } }', '--jq', '.data.viewer.login']).trim();
  const comments = JSON.parse(command('gh', ['api', '--paginate', '--slurp', `repos/{owner}/{repo}/issues/${prNumber}/comments?per_page=100`])).flat();
  const existing = comments.filter((c) => c.user?.login === actor && c.body?.startsWith(MARKER));
  if (existing.length) {
    command('gh', ['api', '-X', 'PATCH', `repos/{owner}/{repo}/issues/comments/${existing[0].id}`, '--input', '-'], JSON.stringify({ body }));
    for (const duplicate of existing.slice(1)) command('gh', ['api', '-X', 'DELETE', `repos/{owner}/{repo}/issues/comments/${duplicate.id}`]);
  } else if (createIfMissing) {
    command('gh', ['api', '-X', 'POST', `repos/{owner}/{repo}/issues/${prNumber}/comments`, '--input', '-'], JSON.stringify({ body }));
  }
}

export function localFiles(command = run) {
  return command('git', ['diff', '--name-only', '-z', 'origin/dev...HEAD']).split('\0').filter(Boolean);
}

function main(argv) {
  const prIndex = argv.indexOf('--pr');
  const prNumber = prIndex >= 0 ? Number(argv[prIndex + 1]) : null;
  if (prIndex >= 0 && (!Number.isSafeInteger(prNumber) || prNumber <= 0)) throw new Error('--pr needs a positive pull request number');
  if (argv.includes('--comment') && !prNumber) throw new Error('--comment requires --pr');

  const all = openPullRequests();
  let files;
  let others;
  if (prNumber) {
    // The files API, not `gh pr diff`: the diff endpoint refuses PRs above 20,000 lines.
    files = pullRequestFiles(prNumber);
    others = all.filter((pr) => pr.number !== prNumber);
  } else {
    const branch = run('git', ['rev-parse', '--abbrev-ref', 'HEAD']).trim();
    files = localFiles();
    others = all.filter((pr) => pr.headRefName !== branch);
  }

  const sharedPatterns = existsSync('.github/CODEOWNERS') ? parseCodeowners(readFileSync('.github/CODEOWNERS', 'utf8')) : [];
  const { markdown, hasFindings } = buildReport({ files, others, sharedPatterns });
  console.log(markdown);
  if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, `${markdown}\n`);
  if (prNumber && argv.includes('--comment')) upsertComment(prNumber, markdown, hasFindings);
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) main(process.argv.slice(2));
