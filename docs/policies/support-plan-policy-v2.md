# SupportPlan policy v2

## Policy metadata

| Field | Value |
| --- | --- |
| Scope decision | `MB-SCOPE-V2-001` |
| Status | `PRODUCT POLICY APPROVED; RUNTIME NOT IMPLEMENTED` |
| Effective decision date | 2026-09-15 |
| Owner | Care |
| Resource eligibility owner | Content/Notification |
| Applies to | Registered users with `PLUS` or `PREMIUM` entitlement and a compatible domain-aware SupportEvaluation |
| Decision | [ADR 0017](../adr/0017-product-scope-v2.md) |
| Amends | [SupportPlan policy v1](support-plan-policy-v1.md) |

This policy retains the v1 immutable-template, deterministic composition,
exact-resource eligibility, 1-to-5 selected-resource bound, and lifecycle
rules except where this document explicitly amends them. The bound is a Care
composition rule, not a commercial limit on how many reviewed resources a
package may access.

## Support Guide versus SupportPlan

| Capability | Support Guide | SupportPlan |
| --- | --- | --- |
| Availability | `FREE`, `PLUS`, and `PREMIUM` | `PLUS` and `PREMIUM` only |
| Purpose | One-time approved guidance after a screening | Durable support with lifecycle and activity tracking |
| Persistence | Immutable guidance result and provenance; no plan lifecycle | Care-owned aggregate with versioned proposal, activation, pause/resume, completion, replacement, and history |
| Authority | Care selects approved guidance | Care decides eligibility and allowed changes; user confirms |

Losing or lacking paid entitlement cannot suppress screening results, safety
guidance, or the Support Guide. Exact behavior for an already current
SupportPlan when paid entitlement expires must be fixed in the compatible
lifecycle contract before runtime enablement; implementations must not invent
silent deletion or mutation.

## Single official plan

Care is the only SupportPlan owner. A user has at most one official current
SupportPlan across `ACTIVE` and `PAUSED`. A system proposal, replacement draft,
or pending `PlanChangeRequest` is not another official plan. Historical
`COMPLETED` and `SUPERSEDED` plans remain immutable.

New assessments, AI output, reminders, appointment events, and specialist
summaries never activate, replace, pause, complete, or otherwise mutate a plan
without the appropriate Care command and explicit user confirmation.

## Specialist-proposed changes

A specialist may record `SessionSummary` and `AgreedNextSteps` after a session.
The specialist cannot create a parallel plan or insert a resource directly.

When the specialist proposes a resource, Consultation submits a bounded
`PlanChangeRequest` to Care containing the user, appointment, current-plan
reference, proposed exact resource version, specialist-authored rationale, and
source summary/next-step references. It contains no raw chat or journal text.

Care:

1. verifies the user, completed appointment, current `PLUS`/`PREMIUM`
   entitlement, and current plan/version;
2. resolves current exact-version eligibility from Content/Notification
   without holding a Care database transaction open;
3. checks template slot compatibility, publication/effective state, total
   bounds, safety presentation, and optimistic version;
4. presents only an allowed proposed change to the user; and
5. applies it atomically only after explicit user confirmation.

Rejection, expiry, failed eligibility, dependency uncertainty, stale plan
version, or no user confirmation leaves the official plan unchanged.

## AI, reassessment, and reminders

AI may guide an approved plan activity and surface preferences, barriers, or
helpful patterns. Care remains authoritative for the four-dimensional
reassessment and for every candidate or lifecycle decision. The user confirms
every applied change.

The default wellbeing digest may include unfinished plan resources together
with Journal and emotion check-in prompts. A per-resource reminder exists only
after explicit user opt-in. Reminders do not own activity or lifecycle state.

## Runtime gates

Runtime requires compatible entitlement, Support Guide, SupportPlan,
`PlanChangeRequest`, activity-tracking, reminder, and summary-reuse contracts;
append-only Care/Consultation migrations; exact eligibility revalidation;
frontend confirmation flows; and authorization, idempotency, concurrency, and
dependency-failure tests.
