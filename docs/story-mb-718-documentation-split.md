# MB-718 shared documentation split

Task: [MB-718](https://vunguyenkhoi47.atlassian.net/browse/MB-718).
Original split baseline: `b7f225e2`, backend `dev` after merged MB-717 PR #221.
Current synchronized base: `2ac6d3d65199ba8c7657fa489e1f44333b575cfd`, including
merged MB-676 PR #218. Merge commit `711d0f06` preserves the published review
history while resolving the traceability conflict into the correct story file.
Branch: `docs/database-split-shared-hotspots-(MB-718)`.

## Design and scope

The three shared files accumulated unrelated feature edits. Service-owned field
descriptions and logical SQL now live in owner files; traceability delivery
records live in files named for their primary story. Existing entry points route
readers to those sources and retain their legacy section anchors.

The split moves existing facts rather than generating a second copy from runtime
migrations. AI/platform conceptual sections remain explicitly conceptual in
`shared-concepts.md`; grouping them does not invent a new deployable or promote a
planned entity into active persistence. The five physical ConsultationBrief
descriptions are grouped with Care, consistent with their existing text, Care
migrations and logical model.

Compound traceability records remain whole under their primary story. Related
story keys route to that record, so statements spanning MB-684 and its dependent
stories are not duplicated or assigned new delivery claims. Shared product
decisions and source/WBS coverage retain dedicated files. Dates and index row
order preserve the original append order, including nonmonotonic dates.

## Before and after file map

| Original shared source | Canonical sources after split | Compatibility entry point |
| --- | --- | --- |
| `docs/database/postgresql-field-data-dictionary.md` | Five `docs/database/postgresql/<owning-service>.md` files and `shared-concepts.md` | [Field dictionary router](database/postgresql-field-data-dictionary.md), including all legacy headings |
| `docs/domain-model/relational/postgresql-logical-schema.sql` | Five `docs/domain-model/relational/<owning-service>.sql` files | [Comment-only SQL router](domain-model/relational/postgresql-logical-schema.sql) and [linked model index](domain-model/relational/README.md) |
| `docs/requirements-traceability.md` | 57 original historical story files plus the newly merged MB-676 record, `baseline.md` and `decisions.md` under `docs/requirements-traceability/`; MB-718 adds its own story record | [Traceability router](requirements-traceability.md), preserving six old section headings and related-story aliases, plus a stable directory link for new stories |

Owner dictionaries link to their matching logical SQL, module README and
executable migration history. Shared guides, data-model navigation and existing
story evidence now direct edits/readers to the owner files. CODEOWNERS covers
the traceability directory as well as its original entry point.

All logical SQL remains read-only documentation. The old SQL path contains only
comments, without SQL commands or executable include directives. No runtime
migration, application code, API, configuration or product rule changes.

## Preservation evidence

Compared against the three original blobs at `b7f225e2`:

| Source | Evidence |
| --- | --- |
| Logical SQL | All 176 SQL statements match exactly as a multiset after removing comments; 163 table definitions remain across five owners. All 2,526 original nonblank lines, including relationship/status comments, remain present. |
| Field dictionary | All 2,475 original nonblank lines remain after normalizing moved Markdown links. Per-section comparison preserves 191 moved sections, 178 table headings and 1,547 field rows; only relative links and document-ending whitespace are normalized. |
| Traceability | All 527 original nonblank lines remain after normalizing moved links. The section manifest accounts for all 563 original lines in 77 chunks. |

The preservation audit counted repeated lines as a multiset, so a repeated field
description could not mask a lost occurrence. Markdown link comparison resolved
destinations relative to each old/new source location. The checked-in
`scripts/verify-documentation-preservation.mjs` reproduces the audit using Git
blobs from an explicitly selected pre-split commit. It derives the repository
location from its own path and needs no temporary workspace files. Temporary
split maps remain supporting artifacts under `.task-tools/mb718-*`.

The same audit also passes against synchronized base `752ed16b`; MB-672 changed
none of the three original shared sources.

After the request-changes review, the audit also passes against exact refreshed
base `2ac6d3d65199ba8c7657fa489e1f44333b575cfd`. It checks 532 traceability
nonblank lines, including all five MB-676 lines and the unchanged evidence link
rebased into [the MB-676 story](requirements-traceability/stories/mb-676.md).
The evidence file from `dev` remains unchanged at
[mb-676-reminder-overview.md](mb-676-reminder-overview.md).

## Request-changes reconciliation

The repository gate now runs the preservation audit automatically when its
supplied base has the old SQL monolith and the working tree has the split
router. It uses that exact `BaseSha`, so CI covers additions merged after the
original split baseline. Branches already based on the split skip this one-off
exact comparison, allowing normal owner schema evolution.

A real temporary Git-repository regression reproduces the stale-base bug:
the old baseline passes when MB-676 is missing, the refreshed baseline rejects
all five missing lines, the correctly rebased story passes, and an altered
evidence target fails.

The pending deltas from #222, #216 and #220 are mapped into eight owner/story
destinations as [SHA-pinned adoption packets](reconciliation/mb-718/README.md).
For each feature, the verifier combines its authored monolith delta with current
`dev`, applies the corresponding owner/story patch in a disposable directory,
and proves SQL/content/link preservation. These pending changes are not applied
to MB-718's active models or another author's branch. Owners still adopt the
packets after integrator coordination; no merge-order approval is claimed.

## Follow-up review and implementation

The fresh review on 2026-10-23 found and fixed two SQL policy defects:

- An `ALTER TABLE` against another owner's table could evade source-owner
  validation when its foreign-key target was local. CREATE and ALTER sources
  now both enforce the model's owner namespace.
- Keywords inside ordinary, escaped or dollar-quoted SQL strings could be
  mistaken for table/foreign-key declarations. Literal bodies are masked for
  structural checks while the SQL router remains strictly comment-only.

Regression cases cover both findings. Four preservation-verifier regressions
also cover repeated-line loss, correct link rebasing and altered facts/targets.
The repository gate runs these tests automatically.

Legacy wording about editing "this dictionary/model/document" now explicitly
refers to the matching owner/story source. Frozen router lists are compatibility
navigation. A stable story-directory link makes new records discoverable without
editing the shared router; the [MB-718 record](requirements-traceability/stories/mb-718.md)
uses that path.

## Verification

Commands ran in the MB-718 worktree and exited `0`:

| Command | Result |
| --- | --- |
| `node --test --test-reporter=dot scripts/documentation-policy.test.mjs scripts/verify-documentation-preservation.test.mjs` | 20 regression tests passed: 16 policy cases and 4 preservation cases. |
| `node scripts/documentation-policy.mjs` | Required sources, owner SQL warnings/local physical references, comment-only SQL router, moved-source links and fragments passed. |
| `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-repository.ps1 -BaseSha origin/dev` | Repository and paired-change policy passed; automatically includes all 20 documentation regression tests and the refreshed-base preservation audit during the layout transition. |
| `node scripts/verify-documentation-preservation.mjs --base b7f225e2` | Original SQL statements and all three sources' nonblank-line multisets preserved. |
| `node scripts/verify-documentation-preservation.mjs --base 2ac6d3d65199ba8c7657fa489e1f44333b575cfd` | Preservation passes against the latest synchronized base, including MB-676. |
| `node scripts/verify-documentation-reconciliation.mjs` | All three SHA-pinned pending patches apply; their expected SQL/dictionary/traceability and 25 moved local links/anchors pass in disposable snapshots. |
| `git diff --check` | Passed. |

The repository policy now requires each changed PostgreSQL migration to have its
own service's dictionary change. A material change additionally requires that
owner's logical SQL. Changing a shared router or another service's file cannot
satisfy either check. Existing material-change detection remains unchanged.

Java/service infrastructure suites are outside this documentation/tooling
change; the relevant local gate is the repository policy. Read remote CI status
from the PR checks for the published head; local results do not imply CI passed.

## Review slices

Delivery is organized into three reviewable commit groups:

1. Owner database documentation, SQL models and their inbound guide/evidence links.
2. Story traceability shards, compatibility router and directory ownership.
3. Documentation policy, regression tests and this delivery evidence.

The file count exceeds the usual approximately 30-file guideline because the
requested story split alone creates 57 small story files. Most paths are exact
content moves; there are no unrelated cleanup or behavior changes. Publication
and remote check results are tracked on the associated MB-718 pull request.
