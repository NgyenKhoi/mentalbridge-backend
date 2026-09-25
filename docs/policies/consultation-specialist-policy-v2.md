# Consultation and specialist policy v2

## Policy metadata

| Field | Value |
| --- | --- |
| Scope decisions | `MB-SCOPE-V2-001`, amended by `MB-SCOPE-V2-002` |
| Status | `PRODUCT POLICY APPROVED; MB-360 LIFECYCLE, MB-362 AVAILABILITY, AND MB-378 APPOINTMENT REQUEST RUNTIMES IMPLEMENTED; CREDIT V2 / DISCOVERY / VIDEO SESSION RUNTIMES DELIVERY-GATED` |
| Effective decision date | 2026-09-24 for ADR 0022 amendments |
| Appointment, specialist, evidence, and billing owner | Consultation |
| Brief and SupportPlan-change decision owner | Care |
| Chat owner | Realtime |
| Base decision | [ADR 0017](../adr/0017-product-scope-v2.md) |
| Current amendment | [ADR 0022](../adr/0022-current-product-blueprint-amendments.md) |
| Historical credit implementation | [ADR 0020 consultation-credit v1](../adr/0020-service-plan-consultation-credits.md) |
| Amends | [Consultation and specialist policy v1](consultation-specialist-policy-v1.md) |

The v1 specialist approval, deterministic non-clinical discovery, atomic
booking/credit hold, cancellation/no-show outcomes, consent boundaries, and
idempotency rules remain in force unless amended here or by ADR 0022.

## Specialist discovery and booking boundary

Only approved, active specialists may appear in current discovery. New v2
appointments use only `IN_APP_CHAT` and `IN_APP_VIDEO`; physical
`PracticeLocation`, phone consultation, and external meeting links are not
current booking modes.

`FREE` may browse approved specialists and currently selectable online slots
but cannot book. Booking authority is server-side Consultation entitlement and
credit policy.

A booking request may be created only when all are true:

1. the account has a package allowed to book;
2. at least one `AVAILABLE` consultation credit exists for the active period;
3. the account is below its package's concurrent active-reservation limit; and
4. the exact specialist slot is still selectable.

Credit exhaustion and reservation-cap exhaustion are separate stable outcomes.
The client never derives either mutable fact from package name.

## Consultation-credit policy v2

For new plan periods governed by `consultation-credit-v2`:

| Package | Credits / active paid period | Rollover | Max active appointment reservations |
| --- | ---: | --- | ---: |
| `FREE` | 0 | none | 0 |
| `PLUS` | 4 | no rollover | 2 |
| `PREMIUM` | 10 | no rollover | 4 |

Historical periods created under `consultation-credit-v1` remain `FREE=0`,
`PLUS=1`, `PREMIUM=3`. They remain queryable with their original policy and are
never rewritten to look like v2 periods.

Unused v2 credits expire with their billing period and do not roll into the
next period. The next period provisions a fresh versioned set from the exact
plan version and credit policy.

Reservation capacity prevents one account from holding excessive specialist
availability at once; it does not reduce the total credits included in the
period. `REQUESTED`, `CONFIRMED`, and `IN_PROGRESS` (or exact equivalent
reserving states) count. `SESSION_ENDED` no longer reserves future specialist
capacity even while evidence settlement is pending. Rejected, expired,
cancelled, completed, no-show, and other terminal/non-reserving states do not
count.

Reschedule-as-new is one logical reservation replacement. A user already at
the reservation cap must still be able to replace the reservation being
rescheduled through one atomic/reconciled operation without temporarily
counting both old and replacement reservations as independent capacity.

The `consultation-credit-v2` target is approved by ADR 0022 but is not runtime
truth until MB-558 or equivalent delivery updates the owner ledger and booking
consumers. Existing MB-377 runtime remains truthful `consultation-credit-v1`.

## Specialist exception lifecycle

MB-360 implements the same-profile lifecycle below. Every decision appends an
audit record with the actor, resulting state, stable reason where required, and
time. A rejected specialist may edit the six public fields and explicitly
resubmit the same profile; resubmission clears the current rejection reason and
returns the profile to `PENDING`. It never creates a replacement identity or
profile.

```text
PENDING -> APPROVED | REJECTED
REJECTED -> PENDING
APPROVED -> SUSPENDED
SUSPENDED -> APPROVED
```

Rejection reasons are `PROFILE_INFORMATION_INCOMPLETE`,
`PROFILE_CONTENT_NOT_APPROVED`, and `OUTSIDE_SUPPORTED_SCOPE`. Suspension
reasons are `POLICY_VIOLATION`, `QUALITY_REVIEW_REQUIRED`, and
`ACCOUNT_REVIEW_REQUIRED`. Other values fail closed.

Suspension is one owner-local transaction: lock the profile, record the stable
reason, withdraw every future active slot, cancel every future not-started
`REQUESTED` or `CONFIRMED` appointment, and release exactly its held credit
with append-only credit and appointment histories. Any credit inconsistency
rolls the transaction back. Restoration returns the profile to `APPROVED`; it
does not revive withdrawn slots, cancelled appointments, or released holds.
The reason is available through authenticated owner/admin reads. This
synchronous owner-local flow has no independent notification consumer, so it
does not add Kafka or an outbox.

## Modes and session boundary

Every slot is exactly 60 minutes. At `scheduledEndAt`, the authoritative
appointment becomes `SESSION_ENDED` and interactive chat/video access closes.
The channel end is a time boundary, not proof of service delivery.

```text
REQUESTED -> CONFIRMED | REJECTED | EXPIRED | CANCELLED
CONFIRMED -> IN_PROGRESS | CANCELLED
IN_PROGRESS -> SESSION_ENDED | USER_NO_SHOW | SPECIALIST_NO_SHOW
SESSION_ENDED -> COMPLETED | DISPUTED | other approved non-payable settlement outcome
```

The exact transition into `COMPLETED` requires a versioned evidence policy.
Chat evidence is server-observed. Video evidence is server/provider-observed
and must remain attributable to the appointment and both participants. A clock
job, specialist assertion, UI state, or elapsed 60 minutes alone is
insufficient.

Only evidence-backed `COMPLETED` consumes one held credit. Specialist earning
creation belongs to the approved settlement boundary and uses the consumed
credit fact; timer-only `SESSION_ENDED` never creates an earning or payout.

No earning is created for cancellation, rejection, expiry, insufficient
evidence, either no-show, provider failure, or unresolved dispute unless a
future explicit settlement decision amends that outcome.

## Appointment request, decision, and change

A request snapshots the exact 60-minute interval, specialist, mode, applicable
timezone/provenance, held slot, and held credit. Duplicate or concurrent
requests must not double-book a slot or double-hold one credit.

The assigned specialist may accept or reject only while the request remains
eligible. The decision deadline is `min(requestedAt + 24h, startsAt - 2h)`
unless a later accepted policy changes it. Acceptance moves to `CONFIRMED` and
keeps the credit `HELD`; rejection or deterministic expiry releases slot and
credit exactly once.

Cancellation and reschedule preserve immutable appointment history. Reschedule
creates a linked replacement request and never mutates the original interval or
mode snapshot. Failure to create the replacement must not fabricate success or
double-settle credit/slot state.

## Brief, summary, next steps, and plan proposals

`ConsultationBrief` exists before the session. Care exposes it to the specialist
only after the user reviews and explicitly approves the appointment-scoped
snapshot under current sharing consent.

After an eligible completed session, the specialist may create a user-visible
`SessionSummary` and `AgreedNextSteps`. These are not a second SupportPlan and
do not mutate the Care-owned plan. Reuse in a later brief, SupportPlan review,
reassessment, or AI context requires explicit user approval.

A specialist may propose an approved platform resource by exact
`ResourceVersion` with bounded appointment/summary provenance. Consultation
records/emits that proposal. The governed `PlanChangeRequest` decision is
Care-owned: Care freshly revalidates entitlement, current plan/version, exact
resource eligibility/publication state, and plan constraints; the user confirms
any applied change. Consultation and the specialist never write SupportPlan
state directly.

## Appointment reminder

An appointment reminder is separate from the wellbeing digest. The scheduler
may send it once, approximately one hour before `scheduledStartAt`, according
to approved deterministic policy. Rejection, expiry, cancellation, reschedule,
or terminal state invalidates stale reminder intent. AI may phrase approved
content only and does not decide whether or when the reminder is sent.

## Video runtime gate

`IN_APP_VIDEO` remains unavailable until versioned contracts define signaling
or provider selection, participant authorization, presence and duration
evidence, reconnection and provider-failure behavior, recording prohibition,
privacy/log exclusions, and session shutdown. An external meeting link is not
a fallback.

MB-362 may publish a video availability slot only when the typed capability
gate is enabled after that provider contract is accepted. With the gate off,
publication is rejected and existing video slots are reported as disabled.
Publishing a slot never issues room credentials or proves that video-session
runtime is enabled.

## Financial boundary

Each paid credit snapshots a fixed VND `creditAllocation`. An evidence-backed
completed appointment creates a specialist earning equal to 70% of that
allocation, not 70% of the package price.

Changing period quantities from historical `1/3` to target `4/10` changes
commercial capacity and liability but does not change the per-credit earning
formula.

Real payment and payout are VND + MoMo only and remain disabled until approved
plan prices, `creditAllocation`, provider contracts, reconciliation rules, and
credentials are configured and verified.
