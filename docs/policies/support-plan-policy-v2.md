# SupportPlan policy v2

## Policy metadata

| Field | Value |
| --- | --- |
| Scope decision | `MB-SCOPE-V2-001` |
| Status | `PRODUCT POLICY APPROVED; DRAFT, ACTIVATION, AND ACTIVITY OCCURRENCES IMPLEMENTED BY MB-372/MB-373/MB-513` |
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

The MB-511 Support Guide runtime is implemented independently of paid
entitlement. Its immutable `mb-support-guide-capstone-v1` result uses exact v2
SupportEvaluation evidence, exact Content eligibility/publication provenance,
stable `AVAILABLE`/`PARTIAL`/`EMPTY`/`STALE`/`UNAVAILABLE` outcomes, local
synchronous safety, owner-only history, and approved-copy fallback when AI is
unavailable.

`PlanChangeRequest`, notification delivery, and summary reuse remain separate
runtime gates. MB-513 implements the user-owned lifecycle commands and
Care-owned activity schedules/occurrences described below; it does not make
Content/Notification the lifecycle owner and does not add treatment adherence.

## MB-372 initial draft runtime

MB-372 enables only deterministic creation and reload of the current `DRAFT`.
It does not enable choice mutation, activation, pause/resume, completion,
replacement, activity tracking, reminders, or `PlanChangeRequest` handling.

- A new draft requires a current authoritative `PLUS` or `PREMIUM` decision
  from Consultation. `FREE`, entitlement uncertainty, or a client-supplied
  package never creates a draft.
- “Fresh SupportEvaluation” means an owned evaluation version 2 whose exact
  policy remains the currently published compatible Care policy and whose
  immutable assessment evidence remains valid. MB-372 does not invent a
  time-based expiry window.
- Care resolves all exact resource versions before its local write transaction.
  Any stale/withdrawn version, missing required core eligibility, malformed
  response, or provider uncertainty fails closed without creating a draft.
- The first successful proposal stores the exact entitlement, evaluation,
  template, eligibility, resource-copy, rationale, and safety snapshot. Reload
  returns that same snapshot without silently recomputing it.
- Requests with the same idempotency key replay the stored outcome. Concurrent
  requests for one user serialize on the Care-owned profile boundary and return
  the same single current draft.
- AI is absent from proposal composition and persistence. It cannot select,
  rerank, mutate, or override any draft fact.

## MB-373 choice and activation runtime

MB-373 implements bounded user choice inside the current draft and the normal
explicit activation transition. MB-513 subsequently adds the lifecycle and
activity occurrence behavior without changing MB-373 activation semantics.

- Choice requests name existing slot IDs and exact resource versions already
  admitted into those slots. A core slot must remain selected; an optional slot
  may be removed. Injected, duplicate, cross-slot, or stale choices fail without
  mutation.
- Choice replacement is a complete desired-state PUT guarded by `If-Match`.
  Repeating the current complete selection is a no-op; no separate request
  idempotency record is needed. A changed selection revalidates current paid
  entitlement, evaluation/template compatibility, and the requested exact
  versions before the local write.
- Immediately before activation, Care rechecks current authoritative
  `PLUS`/`PREMIUM` entitlement, the owned current-compatible SupportEvaluation
  and template composition, and every selected exact Content version.
  Dependency uncertainty fails closed.
- Activation supplies `If-Match` and an owner-scoped `Idempotency-Key`. Care
  records the exact final selection, expected/resulting versions, outcome, and
  revalidation provenance so uncertain activation retries replay the same result.
- Activation accepts no safety acknowledgement field. Approved safety guidance
  remains before ordinary plan controls, and a safety-positive user uses the
  same explicit activation command.
- The `DRAFT` to `ACTIVE` transition, idempotency outcome, and minimized
  `care.support-plan.activated` outbox fact commit atomically. Owner locking and
  a partial unique current-plan index enforce exactly one `ACTIVE`/`PAUSED`
  official plan under concurrent requests.
- `GET /api/v1/support-plans/current` returns only the authoritative official
  current plan. The frontend reloads it after activation instead of deriving an
  active object from local state.

## MB-513 activity occurrence runtime

MB-513 turns each selected exact resource in an `ACTIVE` SupportPlan into a
Care-owned schedule and persisted, user-visible occurrence. The fixed policy
version is `support-plan-activity-schedule-v1`.

- `BREATHING`, `MEDITATION`, and `JOURNALING` resources recur daily. Other
  selected resource categories recur weekly on the activation weekday.
- Slot order fixes local times at 08:00, 10:00, 14:00, 18:00, and 20:00. The
  schedule snapshots the profile IANA timezone. A DST gap moves to the first
  valid local instant; a DST overlap uses the earlier offset.
- Activation fills an inclusive 14-local-day horizon. Authorized bounded reads
  extend missing occurrences; the unique schedule/version/local-date intent
  and deterministic occurrence ID make reloads, retries, and concurrency safe.
- Persisted occurrence states are `SCHEDULED`, `COMPLETED`, `SKIPPED`, and
  `CANCELLED`. `MISSED` is a read-time display state for an overdue scheduled
  occurrence, so merely passing time does not rewrite history.
- Pause cancels future open occurrences with `PLAN_PAUSED`; resume restores only
  still-future occurrences cancelled for that pause and extends the horizon.
  Complete terminally relabels future open or pause-cancelled occurrences and
  prevents restoration. Replacement atomically
  supersedes the old plan, cancels its future occurrences with `PLAN_REPLACED`,
  and activates schedules for the revalidated draft. Discarding a draft creates
  no schedule or occurrence.
- Journal and emotion-check-in prompts may use distinct source types in the
  occurrence contract. They are separate self-reported wellbeing inputs and
  are never inferred as resource completion, treatment adherence, clinical
  outcome, or recovery. The current generator creates only `RESOURCE` sources.
- Only the authenticated user makes pause, resume, complete, discard, replace,
  complete-occurrence, or skip-occurrence decisions. AI may assist wording but
  cannot select a state, issue a lifecycle command, or mark work complete.
