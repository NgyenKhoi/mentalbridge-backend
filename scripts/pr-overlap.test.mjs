import assert from 'node:assert/strict';
import { test } from 'node:test';
import { buildReport, localFiles, matchesPattern, openPullRequests, parseCodeowners, pullRequestFiles, upsertComment } from './pr-overlap.mjs';

test('parses rooted CODEOWNERS patterns and ignores comments', () => {
  const text = '# shared\n/.github/ @lead\n\n/mentalbridge/app/globals.css @lead\n/mobile/src/*.ts @lead\n';
  assert.deepEqual(parseCodeowners(text), ['.github/', 'mentalbridge/app/globals.css', 'mobile/src/*.ts']);
});

test('matches directories, exact files and globs', () => {
  assert.ok(matchesPattern('.github/workflows/a.yml', '.github/'));
  assert.ok(matchesPattern('mentalbridge/app/globals.css', 'mentalbridge/app/globals.css'));
  assert.ok(!matchesPattern('mentalbridge/app/globals.css.map', 'mentalbridge/app/globals.css'));
  assert.ok(matchesPattern('mobile/src/a.ts', 'mobile/src/*.ts'));
  assert.ok(!matchesPattern('mobile/src/x/a.ts', 'mobile/src/*.ts'));
  assert.ok(matchesPattern('mobile/src/x/a.ts', 'mobile/**/a.ts'));
});

test('reports overlap with other PRs but not on generated files', () => {
  const { markdown, hasFindings } = buildReport({
    files: ['mentalbridge/features/plan/A.tsx', 'mentalbridge/contracts/care.generated.ts'],
    others: [
      { number: 7, title: 'other', author: 'quyen', files: ['mentalbridge/features/plan/A.tsx'] },
      { number: 8, title: 'sync only', author: 'thao', files: ['mentalbridge/contracts/care.generated.ts'] },
    ],
    sharedPatterns: [],
  });
  assert.ok(hasFindings);
  assert.match(markdown, /#7 by @quyen/);
  assert.doesNotMatch(markdown, /#8/);
});

test('flags shared files and PR-shape warnings', () => {
  const files = [
    'mentalbridge/app/globals.css',
    'mobile/src/app/index.tsx',
    'mentalbridge/.stitch/capture.png',
    ...Array.from({ length: 30 }, (_, i) => `mentalbridge/features/x/F${i}.tsx`),
  ];
  const { markdown } = buildReport({ files, others: [], sharedPatterns: ['mentalbridge/app/globals.css'] });
  assert.match(markdown, /mentalbridge\/app\/globals\.css/);
  assert.match(markdown, /33 files changed/);
  assert.match(markdown, /separate PRs/);
  assert.match(markdown, /\.stitch\//);
});

test('clean change has no findings', () => {
  const { markdown, hasFindings } = buildReport({ files: ['README.md'], others: [], sharedPatterns: ['.github/'] });
  assert.equal(hasFindings, false);
  assert.match(markdown, /No file is shared/);
});

test('loads every open PR and every changed-file page', () => {
  const prs = Array.from({ length: 102 }, (_, i) => ({ number: i + 1, title: `PR ${i + 1}`, user: { login: 'owner' }, head: { ref: `branch-${i + 1}` } }));
  const paths = Array.from({ length: 101 }, (_, i) => ({ filename: `feature/file-${i}.ts` }));
  const fileRequests = [];
  const command = (cmd, args) => {
    assert.equal(cmd, 'gh');
    assert.ok(args.includes('--paginate'));
    assert.ok(args.includes('--slurp'));
    const endpoint = args.find((arg) => arg.startsWith('repos/'));
    assert.match(endpoint, /per_page=100/);
    if (/\/pulls\?/.test(endpoint)) {
      assert.match(endpoint, /state=open/);
      assert.match(endpoint, /base=dev/);
      return JSON.stringify([prs.slice(0, 100), prs.slice(100)]);
    }
    assert.match(endpoint, /\/pulls\/\d+\/files\?/);
    fileRequests.push(endpoint);
    return JSON.stringify([paths.slice(0, 100), paths.slice(100)]);
  };
  const all = openPullRequests(command);
  assert.equal(all.length, 102);
  assert.equal(fileRequests.length, 102);
  assert.equal(all.at(-1).number, 102);
  assert.equal(all.at(-1).author, 'owner');
  assert.equal(all.at(-1).headRefName, 'branch-102');
  assert.equal(all.at(-1).files.length, 101);
  const report = buildReport({ files: ['feature/file-100.ts'], others: all, sharedPatterns: [] });
  assert.equal(report.hasFindings, true);
  assert.match(report.markdown, /#102 by @owner/);
});

test('zero open PRs does not request any files', () => {
  const requests = [];
  const all = openPullRequests((cmd, args) => {
    requests.push([cmd, args]);
    return '[[]]';
  });
  assert.deepEqual(all, []);
  assert.equal(requests.length, 1);
});

test('API and local filenames preserve Unicode, whitespace and newlines', () => {
  const paths = ['feature/đánh-giá.ts', ' leading and trailing .ts ', 'feature/line\nbreak.ts', 'feature/`<tag>.ts'];
  assert.deepEqual(pullRequestFiles(7, () => JSON.stringify([paths.map((filename) => ({ filename }))])), paths);
  assert.deepEqual(localFiles((cmd, args) => {
    assert.equal(cmd, 'git');
    assert.ok(args.includes('-z'));
    assert.ok(args.includes('--name-only'));
    return `${paths.join('\0')}\0`;
  }), paths);
});

function commentApi(initial = [], actor = 'github-actions[bot]') {
  const comments = structuredClone(initial);
  const mutations = [];
  const command = (cmd, args, input) => {
    assert.equal(cmd, 'gh');
    if (args.includes('graphql')) {
      return args.includes('--jq') ? actor : JSON.stringify({ data: { viewer: { login: actor } } });
    }
    const endpoint = args.find((arg) => arg.startsWith('repos/'));
    const methodIndex = args.indexOf('-X');
    if (methodIndex === -1) {
      assert.match(endpoint, /\/issues\/7\/comments/);
      assert.ok(args.includes('--paginate'));
      assert.ok(args.includes('--slurp'));
      return JSON.stringify([comments.slice(0, 100), comments.slice(100)]);
    }
    const method = args[methodIndex + 1];
    const id = Number(endpoint.split('/').at(-1));
    mutations.push({ method, endpoint, input });
    if (method === 'DELETE') {
      const index = comments.findIndex((comment) => comment.id === id);
      assert.notEqual(index, -1);
      comments.splice(index, 1);
      return '';
    }
    assert.ok(args.includes('--input'));
    assert.equal(args[args.indexOf('--input') + 1], '-');
    assert.ok(!args.some((arg) => arg.startsWith('body=')));
    const { body } = JSON.parse(input);
    if (method === 'POST') {
      comments.push({ id: 1000 + comments.length, body, user: { login: actor } });
    } else {
      assert.equal(method, 'PATCH');
      const existing = comments.find((comment) => comment.id === id);
      assert.ok(existing);
      existing.body = body;
    }
    return '{}';
  };
  return { command, comments, mutations };
}

test('comment upsert ignores a copied human marker and repeated runs create only one bot comment', () => {
  const marker = '<!-- pr-overlap -->';
  const spoof = { id: 3, body: `${marker}\nHuman discussion`, user: { login: 'contributor' } };
  const api = commentApi([spoof]);
  const body = `${marker}\nPR title: $(touch never-run)\nPath: \`<script>\``;
  upsertComment(7, body, true, api.command);
  upsertComment(7, `${body}\nUpdated`, true, api.command);
  assert.deepEqual(api.mutations.map(({ method }) => method), ['POST', 'PATCH']);
  assert.deepEqual(api.comments[0], spoof);
  assert.equal(api.comments.length, 2);
  assert.equal(api.comments[1].body, `${body}\nUpdated`);
  assert.equal(JSON.parse(api.mutations[0].input).body, body);
});

test('clean report skips creation but refreshes an existing report on later comment pages', () => {
  const empty = commentApi();
  upsertComment(7, '<!-- pr-overlap -->\nNo overlap', false, empty.command);
  assert.deepEqual(empty.mutations, []);

  const comments = Array.from({ length: 100 }, (_, i) => ({ id: i + 1, body: 'Discussion', user: { login: 'contributor' } }));
  comments.push({ id: 101, body: '<!-- pr-overlap -->\nOld findings', user: { login: 'github-actions[bot]' } });
  const existing = commentApi(comments);
  upsertComment(7, '<!-- pr-overlap -->\nNo overlap', false, existing.command);
  assert.deepEqual(existing.mutations.map(({ method }) => method), ['PATCH']);
  assert.match(existing.mutations[0].endpoint, /\/comments\/101$/);
  assert.equal(existing.comments.at(-1).body, '<!-- pr-overlap -->\nNo overlap');
});

test('comment upsert deduplicates only comments owned by the authenticated actor', () => {
  const comments = [
    { id: 1, body: '<!-- pr-overlap -->\nOld', user: { login: 'local-owner' } },
    { id: 2, body: '<!-- pr-overlap -->\nDuplicate', user: { login: 'local-owner' } },
    { id: 3, body: '<!-- pr-overlap -->\nHuman copy', user: { login: 'other-user' } },
  ];
  const api = commentApi(comments, 'local-owner');
  upsertComment(7, '<!-- pr-overlap -->\nCurrent', true, api.command);
  assert.equal(api.mutations.filter(({ method }) => method === 'PATCH').length, 1);
  assert.equal(api.mutations.filter(({ method }) => method === 'DELETE').length, 1);
  assert.equal(api.mutations.filter(({ method }) => method === 'POST').length, 0);
  assert.deepEqual(api.comments.map(({ id }) => id), [1, 3]);
  assert.equal(api.comments[0].body, '<!-- pr-overlap -->\nCurrent');
  assert.equal(api.comments[1].body, comments[2].body);
});

test('untrusted PR titles and filenames remain escaped report text', () => {
  const path = 'feature/`<img src=x onerror=alert(1)>\nnext.ts';
  const title = '<script>alert(1)</script>\n- [pretend](https://example.invalid)';
  const { markdown } = buildReport({ files: [path], others: [{ number: 9, title, author: 'owner', files: [path] }], sharedPatterns: [] });
  assert.doesNotMatch(markdown, /<script>/);
  assert.doesNotMatch(markdown, /\n- \[pretend\]/);
  assert.doesNotMatch(markdown, /\[pretend\]\(https:\/\/example\.invalid\)/);
  assert.ok(markdown.includes(`  - \`\` ${path.replace(/\n/g, ' ')} \`\``));

  const markdownPath = 'feature/[evil](https://example.com) *emphasis* `ticks`.ts';
  const report = buildReport({ files: [markdownPath], others: [{ number: 9, title: 'Safe title', author: 'owner', files: [markdownPath] }], sharedPatterns: [] });
  assert.ok(report.markdown.includes(`  - \`\` ${markdownPath} \`\``));
});

test('many overlapping PRs fit in one bounded comment with an explicit truncation notice', () => {
  const files = Array.from({ length: 20 }, (_, i) => `feature/${'long-directory/'.repeat(12)}file-${i}.ts`);
  const others = Array.from({ length: 150 }, (_, i) => ({ number: i + 1, title: `PR ${i + 1}`, author: 'owner', files }));
  const { markdown, hasFindings } = buildReport({ files, others, sharedPatterns: [] });
  assert.equal(hasFindings, true);
  assert.ok(markdown.length <= 50_000, `Report contains ${markdown.length} characters`);
  assert.match(markdown, /truncat|limit/i);
  assert.ok(markdown.startsWith('<!-- pr-overlap -->'));
});
