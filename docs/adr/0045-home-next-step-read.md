# ADR 0045: Care-owned Home next step

- Status: Accepted for MB-672 implementation
- Date: 2026-10-22
- Decision ID: `MB-HOME-NEXT-STEP-001`
- Owner: Care
- Related: [ADR 0034](0034-support-plan-v3-cross-domain-actions.md) (`MB-SUPPORT-PLAN-V3-ACTIONS-001`), [SupportPlan proposal policy v1](../policies/support-plan-proposal-v1.md), [SupportPlan review policy v1](../policies/support-plan-review-v1.md)

## Context

USER Home needs one next step rather than independent assessment, plan and
tracking cards. The same Care decision must serve web and native clients.
`GET /api/v1/guided-journey/current` already orders safety, professional support,
current plan/draft, a compatible paid proposal and Guide resources. Its deployed
native consumer parses the response strictly, so adding Home fields to that
schema would break compatibility.

## Decision

Add the owner-authenticated USER read `GET /api/v1/home-next-step`, with optional
IANA `timezone` and `Cache-Control: no-store`. Its policy version is
`mb-home-next-step-v1`. `HomeNextStepService` calls `GuidedJourneyService.current`
and embeds the returned object under `journey` without changing `GuidedJourney`.
The endpoint uses the journey controller's bearer, subject and correlation
handling. No client decides a replacement journey action.

`plan` is nullable and contains only an ACTIVE or PAUSED plan's ID, status,
focus, review date, resolved local date/timezone, today's counts and the next
open occurrence's bounded navigation/presentation fields. Care chooses focus
in this order:

1. `RESUME_PAUSED` for PAUSED;
2. `REVIEW_DUE` when the existing cycle is due;
3. `DO_TODAY` when today's occurrences include SCHEDULED or OVERDUE;
4. `TODAY_DONE` when today's non-CANCELLED occurrences are all COMPLETED or SKIPPED;
5. `NOTHING_TODAY` otherwise.

The next occurrence is the earliest scheduled instant, then plan action order.
Today counts only non-CANCELLED occurrences. `completed` counts COMPLETED
occurrences; SKIPPED is terminal for focus selection without becoming completed
work. The request timezone determines today's local date when supplied; omission
uses the plan schedule timezone. Invalid timezone input returns 400.
The read reuses `SupportPlanV3Service` current-plan, cycle, single-date occurrence
and history queries. It introduces no table or migration and never changes
plan lifecycle merely because review is due.
After the journey call finishes, the current-plan day projection locks the
owner's current plan in one local transaction before reading its view, zone
and occurrences. Concurrent replacement cannot mix another plan's actions
into that projection; no transaction spans the journey's entitlement call.

A DRAFT remains represented by `journey.nextAction = REVIEW_PLAN_DRAFT`, with
`plan: null`. `lastCompletedPlan` contains only a plan ID and completion instant,
and appears only when there is no ACTIVE, PAUSED or DRAFT plan and the newest
plan in history is COMPLETED. It is context for a secondary history link, not
an inferred journey-completion state.

Clients render the embedded journey's assessment/Guide prerequisites, safety
and professional-support actions before using the plan focus. A safety action
therefore stays primary even when a plan exists. FREE retains useful Guide
resources. An entitlement outage retains the existing resources fallback and
`entitlementStatus: UNAVAILABLE`; a missing published support policy remains
503 `SUPPORT_POLICY_UNAVAILABLE`. Authentication and role failures remain
401/403 as on the journey endpoint.

The response excludes assessment answers and scores, Journal/emotion content,
appointments, streaks, diagnosis and fabricated progress. No Consultation read
is added beyond the existing conditional journey entitlement check. Missed or
absent work yields factual occurrence counts and neutral focus states.

## Compatibility and consequences

The new sibling read is additive. Existing journey fields, endpoint behavior,
policy version and strict native consumers remain compatible. Web adopts the
read for MB-672; native Home adoption belongs to MB-663. SupportPlan Today and
Progress retain ongoing tracking and lifecycle commands.

Care remains the sole next-step authority without a second decision table in
the BFF or native app. The projection composes existing owner reads and does
not introduce a distributed snapshot or a new persistence model. It is fetched
on each Home visit rather than cached by the client. The domain-model and
database documentation need no change because persisted shapes are unchanged.

## Verification

Care unit tests cover focus ordering, occurrence selection/counts, timezone
resolution, completed-plan context and journey reuse. HTTP integration tests
cover USER authorization, owner isolation, no-store, unchanged embedded journey,
minimal response fields, invalid timezone and missing-policy behavior. The
versioned OpenAPI defines the response, errors and representative Home states.
