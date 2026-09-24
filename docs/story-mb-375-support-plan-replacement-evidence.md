# MB-375 SupportPlan replacement evidence

## Delivered boundary

- Care owns both the read-only replacement review and the sole replacement mutation.
- The review reloads persisted owner state and the latest exact `reassessment-summary-v2`; the client cannot supply scores, labels, eligibility, or a replacement outcome.
- Current and proposed plans are freshly checked against paid entitlement, current SupportEvaluation and template policy, exact resource versions, publication/effective state, eligibility, and bounded plan constraints.
- The response keeps screening, Journal context, plan engagement, and owner self-report separate, including `INSUFFICIENT_DATA`/`UNAVAILABLE`, periods, source IDs, versions, and provenance. It does not emit an overall wellbeing verdict.
- Explicit confirmation is protected by both draft/current optimistic versions and an idempotency key. One owner-locked transaction marks the former current plan `SUPERSEDED`, cancels future occurrences with `PLAN_REPLACED`, activates/schedules the draft, records the source-plan/reassessment/outcome relation, and writes the activation outbox event.
- Same-selection, stale-summary, withdrawn-resource, lost-entitlement, dependency-unavailable, version-conflict, and duplicate paths fail closed or replay without mutating the current plan.

## Durable artifacts

- Contract: `contracts/openapi/care-service-v1.yaml`
- Runtime: `SupportPlanController`, `SupportPlanService`, `SupportPlanWriter`, `ReassessmentSummaryService`
- Migration: `020-support-plan-replacement-review.sql`
- Data model: `docs/domain-model/relational/postgresql-logical-schema.sql` and `docs/database/postgresql-field-data-dictionary.md`
- Integration coverage: `SupportPlanIntegrationTests`

## Verification

- Full Care service suite: 212 tests passed against the Liquibase schema, including 21 `SupportPlanIntegrationTests`.
- Replacement coverage includes success, idempotent replay, unchanged proposals, concurrent confirmation, rollback, and fail-closed dependency/evidence paths.
- Care OpenAPI, route inventory, and Liquibase inventory contract tests pass with the new review and replacement boundary.
- Frontend contract generation consumes the exact Care contract; the full frontend quality gate passes with 94 test files / 439 tests, lint, typecheck, contract checks, and production build.
