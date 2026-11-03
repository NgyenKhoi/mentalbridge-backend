import assert from 'node:assert/strict';
import { test } from 'node:test';
import { decide, evaluate, OPT_OUT_LABEL } from './codex-auto-merge.mjs';

const BOT = 'chatgpt-codex-connector[bot]';
const HEAD = 'a'.repeat(40);
const CLEAN = "Codex Review: Didn't find any major issues.";
const cleanReview = { user: { login: BOT }, commit_id: HEAD, state: 'COMMENTED', body: CLEAN };
const ready = (overrides = {}) => ({
  pr: { base: { ref: 'dev' }, draft: false, labels: [], body: '- [x] fetched\n- [x] gate passed', head: { sha: HEAD }, mergeable_state: 'clean' },
  bot: BOT,
  reviews: [cleanReview],
  comments: [],
  threads: [],
  ...overrides,
});
const withPr = (changes) => ready({ pr: { ...ready().pr, ...changes } });

test('merges a mergeable PR whose exact head Codex reviewed clean', () => {
  assert.deepEqual(decide(ready()), { merge: true, reason: 'Codex review clean and branch rules satisfied' });
});

test('accepts a clean comment that names the head, or an exact-head review whose findings are all resolved', () => {
  const comment = { user: { login: BOT }, body: `${CLEAN} Reviewed commit: ${HEAD.slice(0, 7)}` };
  assert.equal(decide(ready({ reviews: [], comments: [comment] })).merge, true);
  const withFindings = { ...cleanReview, body: 'Here are some automated review suggestions.' };
  const resolved = [{ isResolved: true, author: 'chatgpt-codex-connector', commit: HEAD }];
  assert.equal(decide(ready({ reviews: [withFindings], threads: resolved })).merge, true);
  assert.equal(decide(ready({ reviews: [withFindings], threads: [] })).merge, false);
});

test('a clean signal must name the current head; timestamps and reactions never stand in for it', () => {
  const reviewOfOldHead = { ...cleanReview, commit_id: 'b'.repeat(40) };
  assert.match(decide(ready({ reviews: [reviewOfOldHead] })).reason, /waiting for a clean Codex review of aaaaaaa/);
  const cleanCommentWithoutSha = { user: { login: BOT }, body: CLEAN, created_at: '2026-11-04T02:00:00Z' };
  assert.equal(decide(ready({ reviews: [], comments: [cleanCommentWithoutSha] })).merge, false);
  const cleanCommentForOldHead = { user: { login: BOT }, body: `${CLEAN} Reviewed commit: ${'b'.repeat(7)}` };
  assert.equal(decide(ready({ reviews: [], comments: [cleanCommentForOldHead] })).merge, false);
  const humanReview = { ...cleanReview, user: { login: 'NgyenKhoi' } };
  assert.equal(decide(ready({ reviews: [humanReview] })).merge, false);
});

test('a Codex review that requested changes or was dismissed is not clean', () => {
  for (const state of ['CHANGES_REQUESTED', 'DISMISSED']) {
    assert.equal(decide(ready({ reviews: [{ ...cleanReview, state }] })).merge, false, state);
  }
});

test('a Codex usage-limit comment naming the head is not a clean review', () => {
  const limit = { user: { login: BOT }, body: `You have reached your Codex usage limits. ${HEAD.slice(0, 7)}` };
  assert.equal(decide(ready({ reviews: [], comments: [limit] })).merge, false);
});

test('blocks on unresolved Codex threads, matching the GraphQL login without [bot]', () => {
  const threads = [{ isResolved: false, author: 'chatgpt-codex-connector' }];
  assert.equal(decide(ready({ threads })).reason, 'unresolved Codex review comments');
  assert.equal(decide(ready({ threads: [{ isResolved: true, author: 'chatgpt-codex-connector' }] })).merge, true);
  assert.equal(decide(ready({ threads: [{ isResolved: false, author: 'NgyenKhoi' }] })).merge, true);
});

test('requires a fully ticked PR-template checklist', () => {
  assert.equal(decide(withPr({ body: '- [x] fetched\n- [ ] gate passed' })).reason, 'Definition of Done checklist has unchecked items');
  assert.equal(decide(withPr({ body: 'no template' })).reason, 'PR template checklist missing');
  assert.equal(decide(withPr({ body: null })).merge, false);
});

test('finds unchecked task items in every GitHub list form', () => {
  for (const pending of ['1. [ ] pending', '2) [ ] pending', '-  [ ] pending', '+ [ ] pending', '-\t[ ] pending', '  * [ ] pending', '> - [ ] pending']) {
    assert.equal(decide(withPr({ body: `- [x] fetched\n${pending}` })).reason, 'Definition of Done checklist has unchecked items', pending);
  }
  assert.equal(decide(withPr({ body: '1. [x] fetched\n+ [X] gate passed' })).merge, true);
});

test('any box other than [x] counts as unchecked, including a tab inside the brackets', () => {
  for (const pending of ['- [\t] pending', '- [_] pending', '- [-] pending']) {
    assert.equal(decide(withPr({ body: `- [x] fetched\n${pending}` })).reason, 'Definition of Done checklist has unchecked items', JSON.stringify(pending));
  }
});

test('respects draft, base branch, opt-out label and file overlap', () => {
  assert.equal(decide(withPr({ draft: true })).reason, 'draft');
  assert.equal(decide(withPr({ base: { ref: 'staging' } })).reason, 'base is not dev');
  assert.equal(decide(withPr({ labels: [{ name: OPT_OUT_LABEL }] })).merge, false);
  const overlap = { user: { login: 'github-actions[bot]' }, body: '<!-- pr-overlap -->\n### Changed-file overlap (MB-706)\n\nFiles also changed by other open PRs into `dev`.' };
  assert.match(decide(ready({ comments: [overlap] })).reason, /merge order/);
  const sharedOnly = { user: { login: 'github-actions[bot]' }, body: '<!-- pr-overlap -->\nNo file is shared with another open PR into `dev`.' };
  assert.equal(decide(ready({ comments: [sharedOnly] })).merge, true);
});

test('leaves the final say to GitHub mergeability', () => {
  assert.equal(decide(withPr({ mergeable_state: 'behind' })).reason, 'behind dev; rebase first');
  assert.equal(decide(withPr({ mergeable_state: 'blocked' })).merge, false);
  assert.equal(decide(withPr({ mergeable_state: 'dirty' })).merge, false);
  assert.equal(decide(withPr({ mergeable_state: 'unstable' })).merge, false);
  assert.equal(decide(withPr({ mergeable_state: null })).reason, 'mergeability not computed yet');
});

const threadPage = (nodes, hasNextPage) => ({ data: { repository: { pullRequest: { reviewThreads: { nodes, pageInfo: { hasNextPage, endCursor: hasNextPage ? 'c1' : null } } } } } });
const fakeGh = (pr, threadPages) => {
  const calls = [];
  const command = (cmd, args) => {
    calls.push(args.join(' '));
    const path = args.find((a) => a.startsWith('repos/')) ?? '';
    if (args[1] === 'graphql') return JSON.stringify(threadPages);
    if (path.endsWith('/pulls/9')) return JSON.stringify(pr);
    if (path.includes('/reviews')) return JSON.stringify([[cleanReview]]);
    return JSON.stringify([[]]);
  };
  return { command, calls };
};

test('evaluate reads the PR, reviews, comments and paginated threads', () => {
  const { command, calls } = fakeGh(ready().pr, [threadPage([], false)]);
  assert.equal(evaluate(9, BOT, command).decision.merge, true);
  assert.ok(calls.some((c) => c.includes('graphql --paginate --slurp') && c.includes('number=9')));
});

test('evaluate reads every review-thread page before deciding', () => {
  const codex = { comments: { nodes: [{ author: { login: 'chatgpt-codex-connector' } }] } };
  const resolved = Array.from({ length: 100 }, () => ({ ...codex, isResolved: true }));
  const { command } = fakeGh(ready().pr, [threadPage(resolved, true), threadPage([{ ...codex, isResolved: false }], false)]);
  assert.equal(evaluate(9, BOT, command).decision.reason, 'unresolved Codex review comments');
});

test('evaluate refuses to decide on a truncated thread list', () => {
  const { command } = fakeGh(ready().pr, [threadPage([], true)]);
  assert.throws(() => evaluate(9, BOT, command), /incomplete/);
});
