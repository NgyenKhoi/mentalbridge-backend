---
name: mentalbridge-jira-task-csv
description: Create, review, and repair MentalBridge Jira tasks and CSV imports with project-ready hierarchy, estimates, Acceptance Criteria, Definition of Done, evidence, and deterministic import validation. Use for Jira stories, subtasks, Sprint backlogs, task breakdowns, or Jira CSV files; do not use for implementation work that does not create or change task artifacts.
---

# MentalBridge Jira Task CSV

Apply `mentalbridge-repository-workflow` first. Apply `mentalbridge-architecture`, `mentalbridge-data-contracts`, and `mentalbridge-verification-delivery` when the proposed work crosses their boundaries.

## Establish the real scope

1. Inspect the current intended base, existing Jira/task CSV conventions, owning module, source-of-truth contract, implementation, persistence/migrations, tests, frontend consumer, and runtime documentation before estimating or claiming a dependency is ready.
2. Do not trust a status label such as `implemented` by itself. Check contract-to-handler request and response shapes, required headers, status/errors, storage compatibility, authorization, idempotency, concurrency, and relevant runtime tests.
3. Surface provider/consumer and UI/contract mismatches as explicit prerequisite work. Do not hide backend fixes inside a frontend integration task or persist unsupported UI fields by inference.
4. Keep stretch work as an ordered candidate pool. State the pull order and dependencies, preserve the committed Sprint Goal, and distinguish total candidate points from work that fits the remaining capacity.

## Use the MentalBridge task shape

For a feature or Sprint backlog comparable to the repository import files, use:

- Epic: objective, scope or pull policy, explicit deferrals, and a checklist Definition of Done.
- Story: objective, scope/dependencies, checklist Acceptance Criteria, checklist Definition of Done, Evidence fields, and Completion Notes.
- Subtask: one reviewable implementation or verification concern, objective, checklist Acceptance Criteria, checklist Definition of Done, and an Original Estimate.

Give Story Points only to Stories. Give Subtasks positive Original Estimates in seconds and leave their Story Points blank. Do not fabricate assignees, Jira keys, approvals, pull requests, or completion evidence. Import-local IDs may be generated only to establish parent relationships.

Split provider contract/persistence, consumer/BFF, visible UI, testing, and delivery evidence when they have independently reviewable outcomes. Keep behavior with its relevant contract and tests; do not create subtasks that are merely layers with no independently verifiable result.

## Match the current Jira Cloud hierarchy

For this project's external CSV importer, use these exact `Issue Type` values:

| CSV value | Jira level | Allowed parent |
| --- | ---: | --- |
| `Epic` | 1 | none |
| `Story` | 0 | `Epic` |
| `Subtask` | -1 | `Story` |

Use `Subtask`, not `Sub-task`. On the current Jira site, `Sub-task` is not recognized automatically and is mapped to `Task` unless corrected; this creates an invalid same-level `Story` to `Task` parent-child relationship. `Task`, `Story`, and `Bug` are all standard level 0 and must not parent one another.

Map `Issue Type`, `Issue ID`, and `Parent` to Jira's work type, work item ID, and parent fields. Put every parent row before its children: Epics first, Stories second, Subtasks last. Every ID must be unique and every Parent must reference an ID in the same file unless the user explicitly supplies an existing Jira parent key supported by the chosen importer.

If the user shows a different site-specific work type name or hierarchy, use that evidence instead of silently applying this profile, and update the validator/profile deliberately.

## Required pre-delivery validation

For the standard 11-column MentalBridge import format, preserve this header order:

```text
Issue Type,Issue ID,Summary,Parent,Assignee,Story Points,Original Estimate,Sprint,Priority,Labels,Description
```

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .codex/skills/mentalbridge-jira-task-csv/scripts/validate-jira-task-csv.ps1 -Path <csv-path>
```

Do not hand off the CSV unless the validator reports:

- exact headers and a parseable non-empty file;
- only `Epic`, `Story`, and `Subtask` types for this hierarchy profile;
- unique IDs, valid parents, correct adjacent hierarchy levels, and parent-before-child order;
- Story Points only on Stories and positive second-based estimates on Subtasks;
- at least one Subtask for every Story in a decomposed feature backlog;
- required Objective, Acceptance Criteria, Definition of Done, Evidence, and Completion Notes sections;
- no missing required import values.

Report row counts by type, Story Point total, estimate-hour total, and the exact Jira value mapping. Remind the user to re-upload a changed CSV because an open import wizard retains the previously uploaded file.

## Evidence wording

Name verification boundaries truthfully in task DoD and completion evidence:

- Browser E2E with fixtures;
- Service integration with real owned infrastructure;
- Live cross-stack E2E using the real frontend/BFF, provider, and controlled real infrastructure.

Fixture browser tests and separate service tests do not together prove live cross-stack E2E.
