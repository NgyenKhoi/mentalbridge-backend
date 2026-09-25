# ADR 0022: Current product blueprint amendments

- Status: Accepted
- Date: 2026-09-24
- Decision ID: `MB-SCOPE-V2-002`
- Amends: [ADR 0017](0017-product-scope-v2.md), [ADR 0020 service-plan consultation credits](0020-service-plan-consultation-credits.md), and the reassessment interpretation recorded by [ADR 0021 SupportPlan engagement](0021-support-plan-engagement.md)
- Delivery tracking: MB-558, MB-559, MB-560 and the affected consultation/reassessment stories

## Context

ADR 0017 established the cross-feature v2 product boundary. Subsequent runtime work made two assumptions concrete that are no longer the approved target:

1. consultation-credit v1 provisions `FREE=0`, `PLUS=1`, `PREMIUM=3`; and
2. the first MB-386 ReassessmentSummary runtime derives its fourth dimension entirely from reusable SupportPlan occurrence helpfulness/reflection.

Product review on 2026-09-24 also clarified Support Guide persistence, SupportPlan review outcomes, specialist-to-plan change ownership, credit rollover, and the maximum number of appointment reservations a user may hold at once.

Historical records and already-delivered v1/v2 runtime facts must remain truthful. This ADR therefore amends the target prospectively instead of rewriting old ledger periods, old ReassessmentSummary snapshots, or prior ADR rationale.

## Decision

### 1. Support Guide is persisted history, not an ongoing plan

A `SupportGuide` is a persisted immutable post-screening guidance snapshot with provenance and owner history. It does not expire merely because time passes unless a separately approved retention/deletion policy applies.

“One-time” means one generated guidance result for one screening context. It does **not** mean ephemeral data.

A Support Guide has no plan lifecycle, no official-current-plan state, no schedule, no activity occurrences, and no engagement checklist. Reading or opening a resource does not move a Support Guide through `ACTIVE`, `PAUSED`, `COMPLETED`, or similar states.

`SupportPlan` remains the ongoing `PLUS`/`PREMIUM` capability with explicit lifecycle, schedules, occurrences, engagement evidence, reassessment, and governed replacement.

### 2. Reassessment uses four separate evidence dimensions

The canonical ReassessmentSummary dimensions are:

1. **Screening change** — deterministic PHQ-9 and GAD-7 comparisons, kept instrument-specific and separately versioned.
2. **Journal context** — bounded longitudinal user-authored context with explicit source coverage and unavailable/insufficient states.
3. **Plan engagement** — factual SupportPlan occurrence engagement such as completed/skipped state and coded barriers.
4. **Self-reported experience** — an explicit reassessment input authored by the user about how the period has been going.

Activity helpfulness and bounded occurrence reflection may support the fourth dimension, but they do not substitute for the explicit reassessment self-report. The fourth dimension must not be inferred solely from Journal/AI analysis or occurrence engagement.

The dimensions may disagree. MentalBridge preserves that contradiction and never collapses them into a recovery percentage, clinical-improvement verdict, adherence score, or global mental-health score.

The MB-386 runtime merged before this ADR is a compatibility baseline, not the final fourth-dimension contract. MB-559 or equivalent implementation work must add the explicit reassessment self-report before the four-dimension model is considered fully aligned with this decision.

### 3. Reassessment never changes the SupportPlan automatically

After reassessment, Care freshly revalidates the current SupportPlan and any alternatives against current entitlement, current plan/version, exact resource/provider state, eligibility, and applicable policy.

Plan review has three canonical outcomes:

- `CURRENT_PLAN_VALID_NO_BETTER_ALTERNATIVE`: the current plan remains admissible and may continue.
- `CURRENT_PLAN_VALID_ALTERNATIVES_AVAILABLE`: the current plan remains admissible; the user may explicitly keep it or replace it.
- `CURRENT_PLAN_NOT_ADMISSIBLE`: the current plan is not presented as a normal keep option; Care presents an admissible replacement path and preserves history.

A changed screening result does not by itself make the current plan invalid. User preference matters only inside the options admitted by Care; it never overrides safety or eligibility.

Replacement reuses the Care-owned atomic `SUPERSEDE`/`ACTIVATE` path and requires explicit user confirmation.

### 4. Specialist input never mutates SupportPlan directly

A specialist may publish a bounded `SessionSummary`, `AgreedNextSteps`, and an exact-version platform resource proposal after an eligible completed consultation.

Consultation records the proposal and provenance. The governed `PlanChangeRequest` is Care-owned: Care freshly revalidates the proposal, presents an allowed change, and applies it only after explicit user confirmation. Rejection, expiry, stale eligibility, or failed revalidation leaves the official SupportPlan unchanged.

### 5. Consultation-credit policy v2

For new plan periods governed by `consultation-credit-v2`:

| Package | Credits per active paid period | Rollover | Max active appointment reservations |
| --- | ---: | --- | ---: |
| `FREE` | 0 | none | 0 |
| `PLUS` | 4 | no rollover | 2 |
| `PREMIUM` | 10 | no rollover | 4 |

A credit remains one indivisible right to one evidence-backed completed 60-minute consultation. The ledger remains server-authoritative.

Historical `consultation-credit-v1` periods using `0/1/3` remain immutable and queryable under their original policy/version. They are not rewritten to `0/4/10`.

Unused credits do not carry into a new billing period. A new period provisions a new versioned entitlement according to its exact plan version and credit policy.

### 6. Concurrent reservation capacity is distinct from credit balance

The concurrent-reservation limit prevents one account from holding too much future specialist availability at once. It does not reduce the total number of consultations included in the billing period.

For this policy, reservation capacity is consumed by appointment states that still reserve a future/ongoing session: `REQUESTED`, `CONFIRMED`, and `IN_PROGRESS` or their exact contract equivalents. `SESSION_ENDED` no longer reserves future specialist capacity even if evidence settlement is still pending. Rejected, expired, cancelled, completed, no-show, and other terminal/non-reserving states do not count.

Creating an appointment request requires all of the following server-side:

1. a package allowed to book;
2. an `AVAILABLE` consultation credit;
3. active reservation count below the package limit; and
4. a still-selectable specialist slot.

The slot and credit hold are coordinated atomically under Consultation ownership. Credit exhaustion and reservation-cap exhaustion are distinct outcomes.

Reschedule-as-new is one logical reservation replacement. The implementation must atomically replace/rebind the old reservation and its hold, or otherwise exclude the appointment being replaced from the cap check, so a user already at the cap is not blocked from rescheduling that reservation.

### 7. Financial semantics are unchanged per consumed credit

Increasing monthly credits changes commercial capacity and platform liability, not per-credit settlement semantics.

Each issued paid credit still snapshots a fixed VND `creditAllocation`. An evidence-backed `COMPLETED` appointment consumes exactly one held credit and creates a specialist earning equal to 70% of that credit's `creditAllocation`, never 70% of package price.

Real payment/payout remains VND + MoMo only and remains disabled until approved price, `creditAllocation`, credentials, provider contract, and reconciliation requirements are configured.

## Authority and compatibility

- This ADR amends the affected clauses of ADR 0017 and supersedes the **target quantities** in `consultation-credit-v1`; ADR 0020 remains the historical rationale for already-created v1 credit periods.
- Existing MB-386 snapshots remain readable under their source/policy provenance. New canonical reassessment behavior must record the explicit self-report source/version.
- Jira tracks delivery state and dependencies; it does not override this ADR or current approved policy.
- Contracts and code must not claim the amended behavior is executable until the corresponding delivery gates pass.

## Consequences

- Support Guide history remains useful without accidentally becoming a free SupportPlan.
- Reassessment represents four genuinely distinct evidence perspectives and preserves user voice explicitly.
- Users may keep an admissible familiar SupportPlan when evidence changes, while Care still prevents continuation of a plan that is no longer admissible.
- `PLUS` supports four consultations per paid period and `PREMIUM` ten, while reservation caps prevent excessive slot hoarding.
- Old `0/1/3` credit periods and pre-amendment ReassessmentSummary records remain truthful historical data.
