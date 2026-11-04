#!/usr/bin/env node
// Merge open PRs into dev once Codex reviewed the current head clean and GitHub reports them mergeable.
// Branch rules still decide (required quality-gate, up to date with dev, code-owner review); this only
// presses merge for a PR that already satisfies them and the PR-template Definition of Done.
//
//   node scripts/codex-auto-merge.mjs            CI: evaluate every open PR into dev and merge eligible ones
//   node scripts/codex-auto-merge.mjs --dry-run  local: print the decisions only
//
// Needs an authenticated `gh` (GH_TOKEN in CI). CODEX_REVIEWER_LOGIN overrides the Codex bot login.
// Same script lives in mentalbridge-backend and mentalbridge-frontend; keep them identical.
import { execFileSync } from 'node:child_process';
import { appendFileSync, readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

export const OPT_OUT_LABEL = 'no-auto-merge';
const DEFAULT_BOT = 'chatgpt-codex-connector[bot]';
const CLEAN_TEXT = /didn['’]t find any major issues/i;
// Validate every required item from the repository's real PR template.
const REQUIRED_TEMPLATE_ITEMS = readFileSync(
  new URL('../.github/pull_request_template.md', import.meta.url), 'utf8',
).split(/\r?\n/).filter((line) => /^\s*-\s+\[ \]\s+/.test(line))
  .map((line) => line.replace(/^\s*-\s+\[ \]\s+/, '').trim());
if (REQUIRED_TEMPLATE_ITEMS.length < 10) throw new Error('PR template checklist missing or too short');

// GraphQL reports app logins without the "[bot]" suffix that REST uses.
const login = (value) => String(value ?? '').replace(/\[bot\]$/, '').toLowerCase();
// GitHub task-list items: any bullet or ordered marker, optionally inside a blockquote or nested.
const taskItem = (box) => new RegExp(`^[ \\t>]*(?:[-+*]|\\d{1,9}[.)])[ \\t]+\\[${box}\\](?=\\s|$)`, 'm');
const CHECKED_ITEM = taskItem('[xX]');
// Fail closed: any one-character box other than x/X (space, tab, anything else) is not done.
const UNCHECKED_ITEM = taskItem('[^xX\\]\\n]');

const completeTemplate = (body) => {
  const checked = new Set(body.split(/\r?\n/)
    .filter((line) => CHECKED_ITEM.test(line))
    .map((line) => line.replace(/^[ \t>]*(?:[-+*]|\d{1,9}[.)])[ \t]+\[[xX]\]\s+/, '').trim()));
  return REQUIRED_TEMPLATE_ITEMS.every((item) => checked.has(item));
};

// Codex emits a machine-readable receipt scoped to the exact reviewed commit.
export function codexSummary(body) {
  const match = String(body ?? '').match(/<!-- codex-review-state:v1 (\{[^\n]+\}) -->/);
  if (!match) return null;
  try { return JSON.parse(match[1]).code_review ?? null; } catch { return null; }
}

// Clean evidence must name the exact head: reactions and timestamps cannot tell which push Codex reviewed.
export function decide({ pr, bot, reviews, comments, threads, overlap }) {
  const isBot = (user) => login(user) === login(bot);
  const head = pr.head.sha;
  const skip = (reason) => ({ merge: false, reason });

  if (pr.base.ref !== 'dev') return skip('base is not dev');
  if (pr.draft) return skip('draft');
  if (pr.labels.some((label) => label.name === OPT_OUT_LABEL)) return skip(`labelled ${OPT_OUT_LABEL}`);

  const body = pr.body ?? '';
  if (!CHECKED_ITEM.test(body)) return skip('PR template checklist missing');
  if (UNCHECKED_ITEM.test(body)) return skip('Definition of Done checklist has unchecked items');

  if (!completeTemplate(body)) return skip('required PR template checklist items missing');
  if (!overlap?.checked) return skip('current file overlap not verified');
  if (overlap.conflictingPulls.length > 0)
    return skip('shares files with current open PRs: ' + overlap.conflictingPulls.join(', '));

  const codexThreads = threads.filter((t) => isBot(t.author));
  if (codexThreads.some((t) => !t.isResolved)) return skip('unresolved Codex review comments');

  // Never let an older clean review override a pending Codex change request.
  if (reviews.some((r) => isBot(r.user?.login) && r.state === 'CHANGES_REQUESTED'))
    return skip('outstanding Codex changes request');
  const headReviews = reviews.filter((r) => isBot(r.user?.login) && r.commit_id === head &&
    ['COMMENTED', 'APPROVED'].includes(r.state));
  const namesHead = (value) => (String(value ?? '').match(/\b[0-9a-f]{7,40}\b/gi) ?? [])
    .some((sha) => head.startsWith(sha.toLowerCase()));
  const summary = comments.filter((c) => isBot(c.user?.login))
    .map((c) => codexSummary(c.body)).find((value) => value?.commit_sha === head);
  if (summary && (summary.status !== 'completed' || summary.has_any_findings !== false ||
      summary.has_unresolved_findings !== false))
    return skip('Codex review reports findings or is incomplete');
  const clean = Boolean(summary) ||
    headReviews.some((r) => CLEAN_TEXT.test(r.body ?? '')) ||
    comments.some((c) => isBot(c.user?.login) && CLEAN_TEXT.test(c.body ?? '') && namesHead(c.body));
  if (!clean) return skip(`waiting for a clean Codex review of ${head.slice(0, 7)}`);

  switch (pr.mergeable_state) {
    case 'clean':
    case 'has_hooks':
      return { merge: true, reason: 'Codex review clean and branch rules satisfied' };
    case 'behind':
      return skip('behind dev; rebase first');
    case 'dirty':
      return skip('merge conflict');
    case 'blocked':
      return skip('blocked by branch rules (required check or code-owner review)');
    default:
      return skip(`mergeability ${pr.mergeable_state ?? 'not computed yet'}`);
  }
}

const run = (cmd, args, input) => execFileSync(cmd, args, { encoding: 'utf8', input, maxBuffer: 64 * 1024 * 1024 });
const api = (path, command) => JSON.parse(command('gh', ['api', '--paginate', '--slurp', path])).flat();

export function reviewThreads(number, command = run) {
  const query = `query($owner: String!, $repo: String!, $number: Int!, $endCursor: String) {
    repository(owner: $owner, name: $repo) { pullRequest(number: $number) {
      reviewThreads(first: 100, after: $endCursor) {
        nodes { isResolved comments(first: 1) { nodes { author { login } originalCommit { oid } } } }
        pageInfo { hasNextPage endCursor } } } } }`;
  const pages = JSON.parse(command('gh', ['api', 'graphql', '--paginate', '--slurp', '-F', 'owner={owner}', '-F', 'repo={repo}', '-F', `number=${number}`, '-f', `query=${query}`]));
  const connections = pages.map((page) => page.data.repository.pullRequest.reviewThreads);
  if (connections.at(-1)?.pageInfo.hasNextPage) throw new Error(`review threads of #${number} are incomplete`);
  return connections
    .flatMap((connection) => connection.nodes)
    .map((t) => ({ isResolved: t.isResolved, author: t.comments.nodes[0]?.author?.login, commit: t.comments.nodes[0]?.originalCommit?.oid }));
}

// Re-evaluate the current open PR file inventory; old overlap comments may be stale.
export function currentFileOverlap(number, command = run) {
  const open = api('repos/{owner}/{repo}/pulls?state=open&base=dev&per_page=100', command);
  if (!open.some((pr) => pr.number === number))
    throw new Error('candidate missing from current open dev PRs');
  const files = (id) => api(`repos/{owner}/{repo}/pulls/${id}/files?per_page=100`, command).map((f) => f.filename);
  const mine = files(number);
  if (!mine.length || mine.some((p) => typeof p !== 'string'))
    throw new Error('candidate file list missing');
  const seen = new Set(mine);
  const conflictingPulls = [];
  for (const other of open) {
    if (other.number === number || other.base?.ref !== 'dev') continue;
    if (files(other.number).some((path) => seen.has(path))) conflictingPulls.push(other.number);
  }
  return { checked: true, conflictingPulls };
}
export function evaluate(number, bot, command = run) {
  const pr = JSON.parse(command('gh', ['api', `repos/{owner}/{repo}/pulls/${number}`]));
  return {
    pr,
    decision: decide({
      pr,
      bot,
      reviews: api(`repos/{owner}/{repo}/pulls/${number}/reviews?per_page=100`, command),
      comments: api(`repos/{owner}/{repo}/issues/${number}/comments?per_page=100`, command),
      threads: reviewThreads(number, command),
      overlap: currentFileOverlap(number, command),
    }),
  };
}

function main(argv) {
  const dryRun = argv.includes('--dry-run');
  if (!dryRun && process.env.CODEX_AUTO_MERGE_ENABLED !== 'true') {
    console.log('Codex auto-merge disabled; enable only after owner verification.');
    return;
  }
  const bot = process.env.CODEX_REVIEWER_LOGIN || DEFAULT_BOT;
  const open = api('repos/{owner}/{repo}/pulls?state=open&base=dev&per_page=100', run);
  const lines = ['### Codex auto-merge', ''];
  let failed = false;
  for (const { number } of open) {
    let pr;
    let decision;
    try {
      ({ pr, decision } = evaluate(number, bot));
    } catch (error) {
      lines.push(`- #${number} skipped: could not evaluate (${String(error.message).split('\n')[0]})`);
      failed = true;
      continue;
    }
    let outcome = decision.merge ? (dryRun ? 'would merge' : 'merged') : 'skipped';
    if (decision.merge && !dryRun) {
      try {
        // --match-head-commit refuses the merge if a push landed after the evaluation.
        run('gh', ['pr', 'merge', String(number), '--merge', '--match-head-commit', pr.head.sha]);
      } catch (error) {
        outcome = `merge failed: ${String(error.stderr || error.message).trim().split('\n')[0]}`;
        failed = true;
      }
    }
    lines.push(`- #${number} ${outcome}: ${decision.reason}`);
  }
  if (open.length === 0) lines.push('No open PR into `dev`.');
  const report = lines.join('\n');
  console.log(report);
  if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, `${report}\n`);
  if (failed) process.exitCode = 1;
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) main(process.argv.slice(2));
