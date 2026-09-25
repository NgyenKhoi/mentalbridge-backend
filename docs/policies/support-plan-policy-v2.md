# SupportPlan policy v2

## Policy metadata

| Field | Value |
| --- | --- |
| Scope decisions | `MB-SCOPE-V2-001`, amended by `MB-SCOPE-V2-002` |
| Status | `PRODUCT POLICY APPROVED; SUPPORT GUIDE, DRAFT, ACTIVATION, LIFECYCLE/HISTORY, ACTIVITY OCCURRENCES, ENGAGEMENT, AND EXPLICIT REASSESSMENT SELF-REPORT IMPLEMENTED; GOVERNED PLAN REVIEW DELIVERY-GATED` |
| Effective decision date | 2026-09-24 for ADR 0022 amendments |
| Owner | Care |
| Resource eligibility owner | Content/Notification |
| Applies to | Registered users with `PLUS` or `PREMIUM` entitlement and a compatible domain-aware SupportEvaluation |
| Base decision | [ADR 0017](../adr/0017-product-scope-v2.md) |
| Current amendment | [ADR 0022](../adr/0022-current-product-blueprint-amendments.md) |
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
| Purpose | One approved guidance snapshot for one screening context | Durable ongoing support with lifecycle and activity tracking |
| Persistence | Persisted immutable guidance result, owner history, and provenance | Care-owned aggregate with proposal, activation, pause/resume, completion, replacement, and history |
| Automatic expiry | None merely because time passes; only a separately approved retention/deletion policy may remove it | Lifecycle-governed, not TTL-governed |
| Official current state | None | At most one official current `ACTIVE`/`PAUSED` plan |
| Schedule / activity occurrences | None | Yes |
| Engagement lifecycle | None | Occurrence-scoped user-owned engagement |
| Authority | Care selects approved guidance | Care decides eligibility and allowed changes; user confirms |

“One-time” means one generated Support Guide result for one screening context.
It does not mean ephemeral data. Reload/history reads the stored immutable
snapshot. Opening or using a resource never turns a Support Guide into a
checklist, an `ACTIVE` object, or a free SupportPlan.

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

New assessments, AI output, reminders, appointment events, specialist
summaries, and reassessment snapshots never activate, replace, pause, complete,
or otherwise mutate a plan without the appropriate Care command and explicit
user confirmation.

## Specialist-proposed changes

A specialist may record `SessionSummary` and `AgreedNextSteps` after an eligible
completed session. The specialist cannot create a parallel plan or insert a
resource directly.

When the specialist proposes a platform resource, Consultation records/emits a
bounded exact-version proposal with the user, appointment, specialist, source
summary/next-step, proposed `ResourceVersion`, and bounded rationale. It
contains no raw chat or journal text.

The governed `PlanChangeRequest` decision is Care-owned. Care:

1. verifies the user, eligible completed appointment, current `PLUS`/`PREMIUM`
   entitlement, and current plan/version;
2. resolves current exact-version eligibility/publication state from
   Content/Notification without holding a Care database transaction open;
3. checks template slot compatibility, total bounds, safety presentation,
   current plan constraints, and optimistic version;
4. presents only an allowed proposed change to the user; and
5. applies it atomically only after explicit user confirmation.

Rejection, expiry, failed eligibility, dependency uncertainty, stale plan
version, or no user confirmation leaves the official plan unchanged.

## Reassessment: four separate evidence dimensions

Care owns the ReassessmentSummary. The canonical dimensions under ADR 0022 are:

1. **Screening change** — deterministic, versioned PHQ-9 and GAD-7 comparison,
   kept instrument-specific.
2. **Journal context** — bounded longitudinal user-authored context with exact
   source coverage, version/provenance, and explicit unavailable/insufficient
   states.
3. **Plan engagement** — factual SupportPlan occurrence engagement such as
   completion/skip state and coded barriers.
4. **Self-reported experience** — an explicit reassessment input authored by
   the user about how the period has been going.

Activity helpfulness and a bounded occurrence reflection may support the fourth
dimension when the user has explicitly approved reuse, but they do not
substitute for the explicit reassessment self-report. Care must not infer the
user's self-reported experience solely from Journal/AI output or activity
engagement.

Dimensions may contradict each other. The product preserves the contradiction
and never collapses the dimensions into a recovery percentage, clinical
improvement verdict, treatment-adherence score, or global mental-health score.

The MB-386 runtime merged before ADR 0022 is a compatibility baseline. Its
existing activity helpfulness/reflection-based fourth dimension remains
historically readable under its source/version provenance. MB-559 implements
the canonical explicit self-report source and v2 composition prospectively.

## Governed plan review after reassessment

Reassessment never mutates a SupportPlan automatically. Before presenting plan
review actions, Care freshly revalidates the current plan and alternatives
against current entitlement, current plan/version, exact resource/provider
state, eligibility, and applicable policy.

The canonical review outcomes are:

- `CURRENT_PLAN_VALID_NO_BETTER_ALTERNATIVE`: current plan remains admissible
  and may continue.
- `CURRENT_PLAN_VALID_ALTERNATIVES_AVAILABLE`: current plan remains admissible;
  the user may explicitly keep it or replace it.
- `CURRENT_PLAN_NOT_ADMISSIBLE`: current plan is not presented as a normal keep
  option; Care presents an admissible replacement path while preserving
  history.

A changed screening result does not by itself invalidate the current plan.
User preference operates only within Care-admitted options and never overrides
safety or exact eligibility.

Replacement uses the Care-owned atomic `SUPERSEDE`/`ACTIVATE` path and explicit
user confirmation. AI may explain approved alternatives but never decides
admission or issues the mutation.

## AI and reminders

AI may guide an approved plan activity and surface preferences, barriers, or
helpful patterns. Care remains authoritative for reassessment, candidate
admission, and every lifecycle/change decision. The user confirms every applied
change.

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

`PlanChangeRequest` and notification delivery remain separately delivery-gated
where their owner contracts are not yet complete. Explicit reassessment
self-report and approved occurrence summary reuse are implemented in Care.

MB-513 implements Care-owned schedules/occurrences. MB-374 implements owner
lifecycle/history. MB-376 implements owner engagement/helpfulness. Do not cite
MB-513 as the owner of pause/resume/complete/discard lifecycle commands.

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
explicit activation transition.

- Choice requests name existing slot IDs and exact resource versions already
  admitted into those slots. A core slot must remain selected; an optional slot
  may be removed. Injected, duplicate, cross-slot, or stale choices fail without
  mutation.
- Choice replacement is a complete desired-state PUT guarded by `If-Match`.
  Repeating the current complete selection is a no-op. A changed selection
  revalidates current paid entitlement, evaluation/template compatibility, and
  requested exact versions before the local write.
- Immediately before activation, Care rechecks current authoritative
  `PLUS`/`PREMIUM` entitlement, the owned current-compatible SupportEvaluation
  and template composition, and every selected exact Content version.
  Dependency uncertainty fails closed.
- Activation supplies `If-Match` and an owner-scoped `Idempotency-Key`. Care
  records final selection, expected/resulting versions, outcome, and
  revalidation provenance so uncertain retries replay the same result.
- Activation accepts no safety acknowledgement field. Approved safety guidance
  remains before ordinary plan controls.
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
  extend missing occurrences; unique schedule/version/local-date intent and
  deterministic occurrence ID make reloads, retries, and concurrency safe.
- Persisted occurrence states are `SCHEDULED`, `COMPLETED`, `SKIPPED`, and
  `CANCELLED`. `MISSED` is a read-time display state for an overdue scheduled
  occurrence, so merely passing time does not rewrite history.
- Pause cancels future open occurrences with `PLAN_PAUSED`; resume restores only
  still-future occurrences cancelled for that pause and extends the horizon.
  Complete terminally relabels future open or pause-cancelled occurrences and
  prevents restoration. Replacement atomically supersedes the old plan,
  cancels its future occurrences with `PLAN_REPLACED`, and activates schedules
  for the revalidated draft. Discarding a draft creates no schedule or
  occurrence.
- Journal and emotion-check-in prompts may use distinct source types in the
  occurrence contract. They are separate self-reported wellbeing inputs and
  are never inferred as resource completion, treatment adherence, clinical
  outcome, or recovery. The current generator creates only `RESOURCE` sources.
- Only the authenticated user makes pause, resume, complete, discard, replace,
  complete-occurrence, or skip-occurrence decisions. AI may assist wording but
  cannot select a state, issue a lifecycle command, or mark work complete.

## MB-374 owner lifecycle and history

MB-374 completes the ordinary owner-facing pause, resume, complete, and draft
discard journey without changing replacement behavior.

- The consumer asks for explicit confirmation before every lifecycle command.
  Discard and complete confirmations explain their terminal effect; pause and
  resume explain their occurrence effect. Confirmation is interaction evidence,
  not a client-authored authorization field in the Care contract.
- Completion may include one optional stable reason code:
  `USER_DECISION`, `PLAN_NO_LONGER_FITS`, or `OTHER`. Care stores no free-text
  completion note, and no code means recovery, clinical improvement, or goal
  attainment. A reason supplied to another transition is rejected.
- Lifecycle writes remain optimistic desired-state PUTs. `If-Match` protects a
  changed transition, while a repeat of an already-applied target is a no-op and
  returns the persisted snapshot.
- `COMPLETED`, `SUPERSEDED`, and `DISCARDED` plans are returned in stable,
  owner-only terminal history. Detail reload returns the exact persisted plan,
  including source versions, selected resource copies, lifecycle instants, and
  optional completion reason; terminal plans accept no further lifecycle write.
- Assessment submission, SupportEvaluation creation, AI output, reminders, and
  activity-occurrence updates have no path that invokes a SupportPlan lifecycle
  command. Only the authenticated owner can submit the explicit command.

## MB-376 owner engagement and helpfulness

MB-376 keeps mutable engagement on the exact scheduled occurrence. It is
self-reported wellbeing context, never treatment adherence, clinical outcome,
specialist monitoring, or a recovery score.

- The owner may replace a current occurrence with `SCHEDULED`, `COMPLETED`, or
  `SKIPPED`, independently hide it, and optionally record helpfulness for a
  completed item, a stable barrier code for a skipped item, and a private
  reflection of at most 500 characters. Reopening means replacing the state
  with `SCHEDULED` and clearing those signals.
- Replacement is desired-state idempotent and versioned by `If-Match`. A
  natural replay returns the current representation; a stale different write
  fails. Only occurrences belonging to the current `ACTIVE` plan accept a
  mutation. Paused, terminal, cancelled, stale, and cross-owner input fail
  closed.
- Deleting engagement resets mutable state and visibility but retains the
  occurrence, schedule, exact plan/slot/resource/content versions, and normal
  occurrence retention. The controlled demo does not invent a production
  retention duration; production retention remains a separate approval gate.
- A minimized atomic event carries coded engagement and exact source versions.
  It never carries the private reflection. `summaryReuseApproved` is explicit
  per occurrence; no specialist checklist or continuous-observation endpoint
  is exposed.
- Reassessment may consume this evidence as two different contributions:
  completion/skip/barrier belongs to the `Plan engagement` dimension;
  helpfulness/reflection may support `Self-reported experience` only when reuse
  is approved. Neither substitutes for the explicit reassessment self-report.

## MB-386 compatibility baseline and amendment

MB-386 currently composes an immutable owner ReassessmentSummary from local
screening comparison, bounded Journal context, and reusable occurrence evidence.
The occurrence `scheduledAt` decides previous/current half-open period
membership. Sparse evidence remains `INSUFFICIENT_DATA` and dependency failure
remains explicit `UNAVAILABLE`.

The pre-ADR-0022 implementation groups completion/skip/barrier into engagement
and helpfulness/reflection into a fourth user-reflection dimension. That output
remains valid historical runtime evidence under its exact source/version, but it
is **not** the final canonical fourth-dimension target after ADR 0022.

MB-559 delivers the amended target through a versioned explicit reassessment
self-report authored by the user and exact source revision in
`reassessment-summary-v2`. Activity helpfulness/reflection is separately
labelled supporting evidence, not a replacement for the explicit self-report.
Existing v1 snapshots are not rewritten. Deleting a mutable source clears its
content and prevents future composition while prior immutable snapshots retain
the exact historical evidence they originally returned.
