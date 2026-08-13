---
name: mentalbridge-verification-delivery
description: Enforce MentalBridge testing, review, Git, GitHub, and completion workflow. Use before reporting any change complete and before branch creation/switching, staging, committing, pushing, creating or updating issues/pull requests, rebasing, merging, force-pushing, or verifying remote delivery.
---

# MentalBridge Verification and Delivery

First apply `mentalbridge-repository-workflow`. Immediately before Git/GitHub writes, re-read the Git collaboration guide, review/testing guide, and current `.github` templates.

## Verify

1. Run the smallest relevant checks during development, then every affected module quality gate.
2. Cover success, authorization, validation, consistency/concurrency, timeout, retry/duplicate, dependency failure, and safety paths as applicable.
3. Use required real containerized infrastructure; mocks do not replace compatibility/concurrency tests.
4. Run formatting, static analysis, migration validation, contract checks, tests, and builds for affected modules.
5. Inspect the complete diff. Never weaken or skip checks to make them pass.
6. Record exact command, exit code, first actionable error, impact, and state changes for failures. Required failures block completion.

## Deliver

1. Obtain user authorization before branch/switch, commit, push, issue, PR, rebase, merge, or force-push actions.
2. Inspect branch, status, remote/upstream, intended base, all file states, and complete diff. Preserve unrelated work.
3. Search for an equivalent issue and use exact templates. Use `<type>/<scope>-<short-description>` branches and `<type>(<scope>): <lowercase imperative description>` commits.
4. Stage only reviewed task files. Run `git diff --check`; inspect unstaged and cached diffs; exclude secrets, unrelated binaries, generated junk, and teammate changes.
5. Before push, synchronize safely when required, rerun gates, inspect base-to-head diff, and push only the intended branch. Never force-push shared work without explicit authorization.
6. After push, verify remote SHA/branch and PR checks. Report failures truthfully.
7. Create/update PRs only when authorized, using the exact template, title, real issue relationship, tests, and migration/contract/privacy/operational impact.

Handoff with branch, remote SHA, issue/PR, changes, compatibility, exact checks, untested integrations, blockers, and post-push verification.
