# MB-386 four-dimension Reassessment Summary evidence

> Historical compatibility note: MB-559 supersedes activity reflection as the
> canonical fourth dimension. New `reassessment-summary-v2` snapshots use an
> explicit owner-authored self-report and expose activity reflection separately;
> the v1 evidence below remains accurate for existing snapshots.

## Delivered behavior

MB-386 implements Care-owned composition and immutable owner reads for a
four-dimension reassessment snapshot:

- standardized PHQ-9 and GAD-7 trends calculated locally from exact owned Care
  results and their immediately preceding compatible result;
- minimized longitudinal journal context obtained from the canonical
  Journal/AI `REASSESSMENT_SUMMARY` projection;
- SupportPlan completion/skip engagement from occurrences explicitly approved
  by the owner for summary reuse; and
- owner reflection from the same explicitly approved occurrences, kept
  separate from engagement.

The contract never returns a global score, a combined direction, or a clinical
improvement/recovery verdict. A contradiction across dimensions remains visible
as separate evidence and is represented by the canonical
`ContradictoryReassessmentSummary` OpenAPI example for Story 6502.

## Contract and persistence

Implemented owner-authenticated operations:

- `POST /api/v1/reassessment-summaries`
- `GET /api/v1/reassessment-summaries`
- `GET /api/v1/reassessment-summaries/current`
- `GET /api/v1/reassessment-summaries/{summaryId}`

The append-only Liquibase changeset `care-019-reassessment-summary` creates the
`reassessment_summary` table. Each command is idempotent per owner and stores
the complete `reassessment-summary-v1` JSON snapshot, exact half-open periods,
Journal/AI analysis ID, request hash, and composition time. Current, detail,
and cursor-based history reads deserialize only that snapshot; they do not
re-query mutable or deleted source rows.

Care validates equal non-overlapping periods of 7-31 days. Journal/AI responses
must attribute the requested analysis and periods and pass bounded source,
coverage, direction, and provenance validation. Missing sources, current
consent denial, dependency failure, and invalid projection are persisted as
explicit safe fallback reasons. Valid sparse or imbalanced coverage remains
`INSUFFICIENT_DATA` rather than becoming an unavailable or unchanged result.

The outbound adapter forwards the verified end-user bearer context and
correlation ID, uses a 200 ms connection deadline, 800 ms read deadline, at
most one transient retry, and a separate circuit breaker. Startup rejects
overrides whose conservative retry budget exceeds 2.5 seconds, leaving time
for Care to persist and return a safe fallback before the three-second caller
deadline. The remote call completes before the snapshot transaction begins.

## Verification

Evidence classifications:

- Unit and HTTP consumer-contract tests use deterministic synthetic evidence
  and a local HTTP provider stub validated against the canonical Journal/AI
  OpenAPI.
- Service integration tests use disposable real PostgreSQL containers and run
  the complete Care Liquibase history, including changeset 019.
- No browser fixture, live AI-provider call, live multi-service deployment, or
  actor-facing frontend evidence is claimed. The screen belongs to Story 6502.

Commands executed from `care-service` unless noted:

| Command | Result |
| --- | --- |
| `.\mvnw.cmd -q '-Dtest=CareOpenApiContractTests,JournalLongitudinalClientTests,JournalLongitudinalHttpConsumerContractTests' test` | Passed: 17 contract, resilience, malformed-response, configuration-budget, and real-Feign HTTP consumer tests; stalled provider fallback completed in 1.802 s |
| `$env:DOCKER_HOST='npipe:////./pipe/dockerDesktopLinuxEngine'; .\mvnw.cmd -q -Dtest=ReassessmentSummaryIntegrationTests test` | Passed: 4 focused PostgreSQL integration tests; the endpoint persisted and returned `201 + UNAVAILABLE` in 2.242 s when Journal/AI was unavailable within the caller budget |
| `$env:DOCKER_HOST='npipe:////./pipe/dockerDesktopLinuxEngine'; .\mvnw.cmd -q test` | Passed: 205 tests, 0 failures, 0 errors, 0 skipped |
| `.\scripts\verify-repository.ps1 -BaseSha origin/dev` from the repository root | Passed: 720 tracked files and all paired-change policies |
| `git diff --check` from the repository root | Passed |

The focused scenarios cover sufficient and contradictory dimensions, sparse
journal coverage, unavailable/deleted/consent-denied journal evidence,
deterministic local PHQ-9/GAD-7 arithmetic, insufficient screening history,
explicit SupportPlan reuse consent, current/detail/history retrieval,
idempotent replay and conflicting key reuse, authentication, owner isolation,
period validation, response attribution, bounded retry and circuit breaking,
malformed projection rejection, and snapshot stability after source deletion.

## Intentional boundary

MB-386 changes the backend owner capability only. The separate frontend
worktree remains unchanged because actor-facing composition, copy, and visual
presentation are Story 6502. No Jira transition or actor-facing frontend
evidence is part of this implementation evidence.
