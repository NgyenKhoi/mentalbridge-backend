# Git and GitHub Collaboration

The repository templates in `.github/` are mandatory. Agents may prepare suggested issue/PR text, branches, commits, or pushes only when the user has authorized that Git/GitHub action. Never create external issues or PRs merely because implementation is complete.

## Before switching or creating a branch

1. Run `git status --short --branch` and inspect tracked, staged, and untracked files.
2. Treat existing changes as user/team work. Do not discard, overwrite, clean, stash, or commit unrelated files.
3. Fetch/update the base branch only when authorized and network access is available.
4. Create the work branch from the intended base commit. If the worktree is dirty and switching could mix/conflict changes, stop and ask rather than forcing checkout.
5. Use `git switch`; do not use destructive checkout/reset/clean commands to make the worktree appear clean.

## Before coding or resuming a feature

Fetch the remote and measure the feature branch against the intended base before editing:

```text
git fetch origin
git rev-list --left-right --count origin/dev...HEAD
```

The first count is commits present only on `origin/dev`; a value greater than zero means the feature is behind. Integrate the current base before coding: rebase a branch owned by one developer, or merge `origin/dev` into a branch shared by multiple developers. Resolve conflicts deliberately and run the smallest baseline gates before adding new changes. Do not knowingly build a feature on a stale architectural, contract, migration, or security baseline.

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

Plan commit slices before substantial implementation whenever a task spans multiple concerns. Each slice identifies one reviewable reason to change, its owning module or boundary, the expected artifacts, and the focused check that must pass. Commit a completed slice after that check passes instead of accumulating the entire task for one final commit. If the person or agent performing the work is not authorized to commit, keep the slice file groups distinct and hand off the proposed commit sequence.

Keep behavior with its tests, a migration with its data dictionary and mapping tests, and generated output with its source contract. Every commit must build or validate independently and must not depend on a later commit to repair a known broken state. Separate different service owners, behavior changes, refactors, formatting, dependency upgrades, contracts, migrations, and operational configuration unless they are one indivisible compatibility change. Cross-service evolution normally uses separate compatible commits: additive provider contract, consumer adoption, then provider cleanup.

Stage only reviewed files belonging to one slice. Inspect `git diff --cached --stat`, `git diff --cached`, and the remaining unstaged/untracked state before committing. Re-slice if the staged change has more than one independently reversible reason, mixes unrelated scopes, needs `and` to describe its subject, or cannot be reviewed without understanding a later commit. A commit must not accidentally include unrelated binary/source documents, generated artifacts, credentials, or teammate work. Before publishing requirement binaries, review them for personal contact information and confirm the target repository has the intended visibility.

Commit subjects follow Conventional Commits and the types accepted by the PR template:

```text
<type>(<scope>): <lowercase imperative description>
```

Use `feat`, `fix`, `refactor`, `chore`, `docs`, or `test`. Keep the subject concise; explain rationale, compatibility/migration effects, and non-obvious consequences in the body. Reference the issue in a footer such as `Refs #42` when known. Do not claim tests passed unless they were run successfully.

## Pull requests

PR titles follow the repository convention:

```text
type(scope): lowercase short description
```

Append `(#issue)` only when a real tracked issue exists; never fabricate one. Build the PR body from `.github/pull_request_template.md`; do not replace it with an abbreviated summary. Complete:

- change type and detailed behavior/reason;
- `Closes #<issue>` or the precise relationship to the issue/TODO;
- exact test commands, environments, and results;
- screenshots or safe logs only when useful;
- migration, REST/event/WebSocket compatibility, privacy, and operational impact where relevant;
- every checklist item truthfully. Leave an item unchecked and explain the blocker instead of falsely marking it done.

Before opening/updating the PR, synchronize safely with the target branch, resolve conflicts deliberately, rerun affected quality gates, and review the final base-to-head diff. Do not force-push a shared branch unless the user explicitly authorizes it and the team impact is understood.

### Synchronizing a stale feature branch

Do not copy files from `dev` manually or merge a stale PR first and repair it afterward. Fetch the remote and integrate the current target branch before continuing feature work:

- For a branch owned by one developer, rebase onto `origin/dev`, resolve each conflict deliberately, rerun the affected gates, then update the remote only with `git push --force-with-lease` after explicit authorization.
- For a branch used by multiple developers, merge `origin/dev` into the feature branch and push normally so collaborators' commit IDs are not rewritten.
- If the branch is far behind but contains only a small number of feature commits, rebasing is usually the clearest review history. Being many commits behind is not itself a reason to discard or manually recreate the feature.
- Synchronize at the start of work and again before requesting review. During active parallel development, fetch regularly so architectural and contract changes are discovered before implementation grows around stale assumptions.

## Push and post-push verification

Immediately before commit/push or creating/updating a PR, fetch and run the divergence check again. If `origin/dev` advanced while implementation was in progress, integrate it with the same ownership-safe rebase/merge rule, resolve conflicts, rerun every affected gate, and inspect the refreshed `origin/dev...HEAD` diff. Do not rely on the check performed at the start of coding.

Push only the intended branch and only when authorized. Confirm the remote and upstream; never assume `origin` or the target repository. After pushing, verify the remote commit/branch and report the commit SHA. When a PR exists, verify its checks and report failures accurately; pushing is not completion when required CI is red.
