# SupportPlan proposal policy v1

| Field | Value |
| --- | --- |
| Policy version | `support-plan-proposal-v1` |
| Cycle policy | `support-plan-cycle-v1` |
| Status | Approved by the owner for MB-669 and MB-644 on 2026-10-17 |
| Owner | Care |
| Amends | [SupportPlan policy v3](support-plan-policy-v3.md) prospectively |
| Applies to | New native drafts for admitted `PLUS` and `PREMIUM` users |

Historical plans retain their exact policy, source snapshots and rules. The
single-current-plan invariant, explicit activation and replacement, entitlement,
source authority and safety rules of policies v2 and v3 continue to apply.

## Admission and composition

Care creates a deterministic draft from an owned current-compatible screening
evaluation, exact reviewed Content versions and the immutable optional
`support-plan-personalization-v1` revision. The client supplies neither action
ranking nor clinical decisions. AI does not select, rank or activate actions.

Safety precedes ordinary plan creation. A positive safety screen fails closed
without persisting a draft; the user receives the existing safety support path.
`FREE`, uncertain entitlement and uncertain required source eligibility also
fail closed. No proposal activates or replaces a plan automatically.

| Action | Admission and role |
| --- | --- |
| `RESOURCE` | Keep the existing reviewed `SupportPlanPolicy` families and slots, exact `contentVersion`, role and eligibility checks. A missing eligible `CORE` fails closed; an unfilled `OPTIONAL` is dropped. The Support Guide resource map is not used. |
| `PROFESSIONAL_SUPPORT` | Primary in every `*_PROFESSIONAL_ADJUNCT` family. Resource and other self-support actions remain adjuncts. No booking or consent is implied. |
| `EMOTION_CHECK_IN` | One short five-minute `DAILY` action when preferences are absent, all fields were skipped, or the provided activity modes include `EMOTION_CHECK_IN`. Provided preferences without that mode opt out. |
| `JOURNAL` | One bounded short-reflection action only when provided activity modes include `JOURNALING`. |
| `REASSESSMENT_SCREENING` | One `ONCE` reminder on the cycle review date, fulfilled by the explicit owner review decision described below. |
| `ROUTINE_SELF_MANAGEMENT`, `SELF_REPORT_REVIEW` | Not proposed because their owner integration is absent. |

Cadence uses `support-plan-activity-schedule-v2` defaults. The emotion check-in
counts within the daily time budget; resource and Journal schedules must share
the remaining budget or use fewer weekly days. When a five-minute budget and
the daily five-minute check-in leave no room for a required minimum resource
activity, proposal fails closed with `SUPPORT_PLAN_TIME_BUDGET_INCOMPATIBLE`
(409), without a draft. The one-time reassessment is separate review effort.
A source opening or client assertion of source completion never fulfils an
emotion, Journal, professional or reassessment action.

Immediately before explicit activation, Care revalidates paid entitlement,
the exact evaluation context, reviewed resource versions, family/role rules,
the snapshotted preference revision and every admitted typed action. Existing
native policy versions keep their original action admission rules.

Proposal retries with the same owner-scoped idempotency key return the stored
draft outcome, including a subsequently discarded outcome. A competing proposal
cannot silently overwrite the current draft; it receives
`SUPPORT_PLAN_DRAFT_EXISTS` (409).

## Four governed examples

1. **Self-guided, MILD.** An admitted user has MILD depressive and anxiety
   contributions and no preferences. Care selects exact eligible resources from
   the self-guided families, includes the default daily emotion check-in, omits
   Journal and professional support, and adds the review-date reassessment.
2. **Professional support, moderate or above.** An admitted contribution uses
   a professional-adjunct family. Professional support is the primary path;
   exact eligible resources remain adjuncts. Explicit `JOURNALING` preferences
   admit Journal. Provided preferences without `EMOTION_CHECK_IN` omit emotion
   check-in. The review reminder remains present.
3. **Safety-positive.** A positive safety screen admits no ordinary draft,
   resource composition or activation. Safety support takes precedence even
   for a paid user whose preferences request self-support actions.
4. **Sparse resources.** Missing eligible content for a required `CORE` slot
   rejects the proposal without a draft. Missing eligible content only for an
   `OPTIONAL` slot drops that slot and preserves the remaining admitted actions.
   Unknown, stale, withdrawn or unavailable exact-version evidence keeps the
   existing fail-closed behavior; it is not treated as optional absence.

## Fourteen-day review cycle

Only `support-plan-proposal-v1` plans have a cycle. Other native policies and
`V2_RESOURCE_ADAPTER` plans omit `cycle`; their history and wire shape are not rewritten.
The cycle field is optional and readers also tolerate null. The six additive
occurrence engagement fields are optional and emitted only for native
occurrences with engagement metadata or from the native engagement commands.
An older state-only engagement timestamp does not count as engagement metadata.
Other native occurrences and adapter occurrences retain their original response shape.

- The cycle length is 14 local days. `cycleNumber` starts at one.
- `startedOn` is the activation date in the plan's schedule timezone, defaulting
  to `Asia/Ho_Chi_Minh`. That date is day one.
- `reviewDueOn = startedOn + 13 days + pausedDays`.
- `pausedDays` counts calendar days from the pause date up to but excluding the
  resume date, in the cycle timezone. An ongoing pause uses the current local
  date as the exclusive end. Multiple pauses accumulate; paused days do not
  advance the cycle.
- A pause that begins on or after the review date does not move it or add
  paused days. For a pause that begins before the review date, resume shifts
  the review date and the open reassessment occurrence to that date,
  preserving completed and historical evidence.
- A draft is `NOT_STARTED`; an active cycle is `IN_PROGRESS` before its review
  date and `REVIEW_DUE` from that date. A paused plan exposes `PAUSED`.
- Completed, superseded and discarded plans expose `CLOSED`.

Reaching `REVIEW_DUE` changes no lifecycle state, completion evidence or schedule
automatically. The plan stays active until an explicit user command. A completed
reassessment alone does not clear review due. There is no automatic renewal.

In this version, the `REASSESSMENT_SCREENING` reminder is fulfilled only by the
owner's explicit keep or change at `REVIEW_DUE`. No integration yet completes
the occurrence from Care screening history; screening-history reconciliation
is a follow-up.

## Explicit continuation

`POST /api/v2/support-plans/{supportPlanId}/cycle-continuation` requires the
strong `If-Match` plan validator and an owner-scoped `Idempotency-Key`. Care
accepts it only for an active proposal-policy plan whose cycle is `REVIEW_DUE`.

The confirmed command increments `cycleNumber`, sets `startedOn` to today's
local date, resets `pausedDays` to zero and schedules a new reassessment
occurrence on `startedOn + 13 days`. It changes no action intent, resource
selection or completion history. An identical retry replays the recorded
outcome; a stale version fails with 412. The alternative is the existing
reassessment, replacement-review and explicit replacement flow.
