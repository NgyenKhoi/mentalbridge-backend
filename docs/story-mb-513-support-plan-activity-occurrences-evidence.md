# MB-513 verification evidence

## Delivered boundary

Care persists deterministic schedules and occurrences for each selected exact
resource when a SupportPlan becomes active. Owner-only APIs expose bounded
today/upcoming reads, detail, explicit complete/skip input, and plan lifecycle
commands. The web BFF validates and forwards those contracts and the SupportPlan
page renders local time, source/resource versions, state, and lifecycle controls.

The implementation follows [ADR 0020](adr/0020-support-plan-activity-occurrence-scheduling.md)
and [SupportPlan policy v2](policies/support-plan-policy-v2.md). It does not add
notification delivery, a specialist `PlanChangeRequest`, treatment adherence,
or AI decision authority.

## Automated evidence

- Backend unit tests cover DST gap and overlap resolution.
- Backend integration tests cover activation concurrency, deterministic reload,
  duplicate suppression, occurrence state retry, pause/resume, completion,
  replacement replay, old-plan cancellation, and draft discard.
- Frontend validation, BFF, and component tests cover bounded dates, optimistic
  state mutation, today/upcoming rendering, source versions, paused controls,
  and the non-adherence explanation.
- Fixture browser E2E covers active-plan schedule rendering and a user marking
  one occurrence complete.

Verification commands and their final pass/block status are recorded in the PR
description. A Docker-backed backend integration run and a live cross-stack E2E
must never be claimed when the required local engine or stack is unavailable.
