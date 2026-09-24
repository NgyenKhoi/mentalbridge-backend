# Consultation and specialist policy v2

## Policy metadata

| Field | Value |
| --- | --- |
| Scope decision | `MB-SCOPE-V2-001` |
| Status | `PRODUCT POLICY APPROVED; MB-360 LIFECYCLE AND MB-362 AVAILABILITY RUNTIME IMPLEMENTED; VIDEO SESSION RUNTIME DISABLED` |
| Effective decision date | 2026-09-15 |
| Appointment, specialist, evidence, and billing owner | Consultation |
| Brief and SupportPlan-change decision owner | Care |
| Chat owner | Realtime |
| Decision | [ADR 0017](../adr/0017-product-scope-v2.md) |
| Amends | [Consultation and specialist policy v1](consultation-specialist-policy-v1.md) |

The v1 specialist approval, deterministic non-clinical discovery, atomic
booking/credit hold, cancellation/no-show outcomes, consent boundaries, and
idempotency rules remain in force unless amended here.

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

New v2 appointments support only `IN_APP_CHAT` and `IN_APP_VIDEO`. New
`IN_PERSON`, phone, and external-meeting-link appointments are rejected. A
historical in-person appointment remains readable and follows its snapshotted
v1 policy.

Every slot is exactly 60 minutes. At `scheduledEndAt`, the authoritative
appointment becomes `SESSION_ENDED` and interactive chat/video access closes.
The channel end is a time boundary, not proof of service delivery.

```text
CONFIRMED -> IN_PROGRESS -> SESSION_ENDED
SESSION_ENDED -> COMPLETED | DISPUTED
IN_PROGRESS -> USER_NO_SHOW | SPECIALIST_NO_SHOW
```

The exact transition into `COMPLETED` requires a versioned evidence policy.
Chat evidence is server-observed. Video evidence is server/provider-observed
and must remain attributable to the appointment and both participants. A clock
job, specialist assertion, or UI state alone is insufficient.

Only `COMPLETED` consumes one credit and atomically creates one earning. No
earning is created for `SESSION_ENDED`, cancellation, either no-show, or
`DISPUTED`.

## Brief, summary, and next steps

`ConsultationBrief` exists before the session. Care exposes it to the specialist
only after the user reviews and explicitly approves the appointment-scoped
snapshot under current sharing consent.

After the session, the specialist may create a user-visible `SessionSummary`
and `AgreedNextSteps`. These are not a second SupportPlan and do not mutate the
Care-owned plan. Reuse in a later brief, SupportPlan review, reassessment, or AI
context requires explicit user approval.

A proposed resource is routed as a `PlanChangeRequest`. Care revalidates
entitlement, current plan state, and exact resource eligibility; the user
confirms any applied change. Consultation and the specialist never write
SupportPlan state directly.

## Appointment reminder

An appointment reminder is separate from the wellbeing digest. The scheduler
may send it once, approximately one hour before `scheduledStartAt`, according
to approved deterministic policy. AI may phrase approved content only and does
not decide whether or when the reminder is sent.

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

`PLUS` grants one credit per paid period and `PREMIUM` grants three. Each credit
has a fixed VND `creditAllocation`. A completed appointment earns the
specialist 70% of that allocation, not 70% of the package price. Real payment
and payout remain disabled until the VND price/allocation table and MoMo
credentials are approved and configured.
