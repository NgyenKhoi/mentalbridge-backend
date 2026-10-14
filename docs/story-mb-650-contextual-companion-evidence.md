# MB-650 contextual SupportPlan Companion evidence

## Delivered boundary

Care exposes one owner-scoped, user-authenticated endpoint for an explicit
contextual explanation request:

`POST /api/v2/support-plans/{supportPlanId}/actions/{actionId}/companion-explanation`

The browser sends only the two path identities and `surface: TODAY|PLAN`.
Care reads the authoritative owned SupportPlan v3 projection, locates the exact
eligible Resource, Journal, Emotion Check-in or Professional Support action,
and builds the accepted MB-647 `EXPLAIN_ACTION` envelope. Identifiers, tokens,
raw source-domain data and arbitrary browser state never enter model context.

Care forwards the verified USER bearer plus the dedicated server-only service
credential to the MB-649 Journal/AI endpoint. A missing credential, timeout,
quota response, provider outage, malformed response or open circuit returns the
bounded deterministic template and never blocks the original plan action.
The POST has no retry and creates no Care persistence or migration.

## Contract and failure behavior

| Rule | Evidence |
| --- | --- |
| Explicit invocation only | The endpoint is POST-only and is called by a separate UI button; focus/hover does not call it. |
| Care owns context | The request has only `surface`; Care derives action type/title/rationale from the owner-scoped plan projection. |
| Exact identity | Non-owned plans and actions fail closed; the response repeats `supportPlanId` and `actionId`, which the BFF binds to its exact request. |
| Approved scope | Only Today/Plan and the four MB-650 action types are admitted. Draft actions are unavailable on Today and ended actions are rejected. |
| Bounded provider | Connect/read deadlines are 0.5s/8s, a circuit breaker is applied and non-idempotent POST retry is disabled. |
| Deterministic fallback | Disabled/missing credential and all dependency failures return `support-plan-companion-template-v1` copy with an explicit closed fallback reason. |
| Safe telemetry | Care logs correlation ID, intent, surface, failure category and HTTP status only; no context or response text is logged. Journal/AI retains its accepted intent/outcome telemetry. |

## Configuration lifecycle

`CARE_SUPPORT_PLAN_COMPANION_SERVICE_TOKEN` is a Care-to-Journal/AI secret and
must equal Journal/AI `JOURNAL_AI_SUPPORT_PLAN_CARE_SERVICE_TOKEN`. It stays on
the server. Leaving the token or direct base URL unset deliberately disables
provider phrasing while preserving deterministic template delivery.

## Verification

- `SupportPlanContextualCompanionServiceTests`: exact minimized context,
  response identity, cross-action failure, surface/status/type admission and
  truthful active/draft availability.
- `SupportPlanCompanionClientTests`: credential-disabled template and malformed
  cross-surface dependency response fallback, including the approved
  unavailable-action template.
- `SupportPlanV3ControllerCompanionTests`: verified subject, bearer, path and
  correlation propagation.
- Care OpenAPI/contract and full module gates are run on the final exact head.

Local verification on 2026-10-14 passed 19 focused contract/client/service/
controller tests. The complete `care-service` suite passed 314/314 tests with
PostgreSQL Testcontainers.
