# MB-635 — Owner implementation evidence

Story: [MB-635](https://vunguyenkhoi47.atlassian.net/browse/MB-635).
Decision: [ADR 0033](adr/0033-reviewed-specialist-profile-amendment.md)
(`MB-SPECIALIST-PROFILE-AMENDMENT-001`). Consultation owns all data and decisions.

## Delivered owner slice

- OpenAPI 1.11.0: nine specialist/admin amendment operations, explicit role
  protection, If-Match/ETag, closed reasons, stable errors, bounded review queue,
  proposed-versus-approved detail, and additive published content version.
- Migration 018: approved-content version snapshots, one open amendment per
  specialist, exact immutable revision/review snapshots and available approval
  provenance. Existing approved/suspended rows receive one real baseline,
  not reconstructed historical content.
- Separate draft/review/rejection state never writes live profile data.
  ADMIN approval promotes the reviewed payload, increments published version,
  and appends both audit histories in one owner-local transaction.
- Profile-first lock ordering serializes starts, edits, review and suspension.
  ADMIN owner lookup is scalar so it cannot preload stale amendment state.
  Existing initial profile endpoints and normal discovery eligibility remain
  compatible. Availability, appointments, credits and payouts are not rewritten.
- Canonical policy, lifecycle diagram, blueprint/use case, entity index,
  logical schema, data dictionary and owner README updated together.

## Executed verification

From `consultation-service/`, `mvnw.cmd package` with WARN-level test logging:
159 tests passed, zero failures/errors, executable package produced.

`ProfileAmendmentIntegrationTests` exercises reload, submit, approve,
rejection/correction/resubmit, wrong actors, invalid fields/reasons, missing and
stale versions, duplicate current-version approval, concurrent starts and
concurrent review/edit, suspension, discovery before/after promotion,
immutable approved/review payloads and exact preservation of an existing
confirmed appointment plus active availability.

`ProfileAmendmentMigrationTests` upgrades disposable PostgreSQL through owner
migrations 001–018, proving that only approved/suspended profiles receive
published baselines, original approval actors are retained rather than replaced
by later operational actors, and rejection without a bounded reason is rejected.
Existing lifecycle, OpenAPI and changelog tests also pass in the full module run.

Tests use synthetic actors and disposable Testcontainers databases. No shared
dev/staging migration was run. No secrets or real health records are test data.

## Rollout and handoff

Apply owner migration 018 before deploying the updated consultation owner.
Drain old owner instances before enabling amendment writes. Rollback must keep
approved/audit records and disable new commands rather than delete published
facts. Frontend BFF/UI/test evidence lives in the frontend MB-635 evidence file.

No Git publication, Jira transition, shared-database migration or Docker
deployment was performed at the original implementation checkpoint.

## Publication checkpoint

The owner subsequently authorized PR publication to `dev`, waiting for green CI,
merge and local Docker rebuild. Publication uses an isolated worktree based on
`origin/dev` at `18ba4724`; unrelated local files are not included.

In that worktree, `SPRINGDOTENV_ENABLED=false` and
`mvnw.cmd -B -Dlogging.level.root=WARN package` passed all 159 tests with zero
failures/errors/skips and produced the executable package. Repository and paired
change policy also passed with `scripts/verify-repository.ps1 -BaseSha origin/dev`.
These are local results, not a claim that GitHub CI or deployment has completed.
