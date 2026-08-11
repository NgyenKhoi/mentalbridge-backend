# Git and GitHub Collaboration

The repository templates in `.github/` are mandatory. Agents may prepare suggested issue/PR text, branches, commits, or pushes only when the user has authorized that Git/GitHub action. Never create external issues or PRs merely because implementation is complete.

## Before switching or creating a branch

1. Run `git status --short --branch` and inspect tracked, staged, and untracked files.
2. Treat existing changes as user/team work. Do not discard, overwrite, clean, stash, or commit unrelated files.
3. Fetch/update the base branch only when authorized and network access is available.
4. Create the work branch from the intended base commit. If the worktree is dirty and switching could mix/conflict changes, stop and ask rather than forcing checkout.
5. Use `git switch`; do not use destructive checkout/reset/clean commands to make the worktree appear clean.

Branch names use lowercase kebab-case:

```text
<type>/<scope>-<short-description>
```

Allowed types mirror the PR template: `feat`, `fix`, `refactor`, `chore`, `docs`, `test`. Preferred scopes are module/domain names such as `identity`, `care`, `consultation`, `journal-ai`, `realtime`, `content-notification`, `phobert`, `database`, `platform`, `ci`, or `docs`.

Examples:

```text
feat/realtime-message-receipts
fix/consultation-double-booking
docs/platform-agent-workflow
```

## Issues

Use the matching repository template exactly:

- bug: `.github/ISSUE_TEMPLATE/bug_report.md`;
- feature/TODO: `.github/ISSUE_TEMPLATE/feature_request.md`.

Before creating an issue, search for an existing equivalent. Fill every applicable section with business behavior, edge cases, affected modules, priority, acceptance criteria, and test expectations. Never paste sensitive production content, secrets, tokens, raw journals/chats, assessment answers, or unredacted logs.

Feature issue titles follow the template: `feat(<scope>): <lowercase short description>`. Bug issue titles retain the `[BUG]` prefix from the bug template and identify the affected scope in the body. Do not fabricate issue numbers, assignees, labels, estimates, or reviewer approvals.

## Commits

Stage only reviewed files belonging to the issue. Inspect `git diff` and `git diff --cached` before committing. One commit should represent one coherent reason to change and must not accidentally include unrelated binary/source documents, generated artifacts, credentials, or teammate work. Before publishing requirement binaries, review them for personal contact information and confirm the target repository has the intended visibility.

Commit subjects follow Conventional Commits and the types accepted by the PR template:

```text
<type>(<scope>): <lowercase imperative description>
```

Use `feat`, `fix`, `refactor`, `chore`, `docs`, or `test`. Keep the subject concise; explain rationale, compatibility/migration effects, and non-obvious consequences in the body. Reference the issue in a footer such as `Refs #42` when known. Do not claim tests passed unless they were run successfully.

## Pull requests

PR titles follow the exact repository convention:

```text
type(scope): lowercase short description (#issue)
```

Build the PR body from `.github/pull_request_template.md`; do not replace it with an abbreviated summary. Complete:

- change type and detailed behavior/reason;
- `Closes #<issue>` or the precise relationship to the issue/TODO;
- exact test commands, environments, and results;
- screenshots or safe logs only when useful;
- migration, REST/event/WebSocket compatibility, privacy, and operational impact where relevant;
- every checklist item truthfully. Leave an item unchecked and explain the blocker instead of falsely marking it done.

Before opening/updating the PR, synchronize safely with the target branch, resolve conflicts deliberately, rerun affected quality gates, and review the final base-to-head diff. Do not force-push a shared branch unless the user explicitly authorizes it and the team impact is understood.

## Push and post-push verification

Push only the intended branch and only when authorized. Confirm the remote and upstream; never assume `origin` or the target repository. After pushing, verify the remote commit/branch and report the commit SHA. When a PR exists, verify its checks and report failures accurately; pushing is not completion when required CI is red.
