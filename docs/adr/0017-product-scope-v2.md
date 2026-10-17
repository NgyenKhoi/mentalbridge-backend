# ADR 0017: Product scope v2

- Status: Accepted
- Date: 2026-09-15
- Decision ID: `MB-SCOPE-V2-001`
- Amended by: [ADR 0022 — Current product blueprint amendments](0022-current-product-blueprint-amendments.md) (`MB-SCOPE-V2-002`)
- Amends: [ADR 0005](0005-consultation-billing-and-credit-settlement.md),
  [ADR 0009](0009-care-screening-safety-and-support-boundaries.md),
  [ADR 0013](0013-freeze-support-plan-policy-v1.md),
  [ADR 0014](0014-appointment-specialist-and-consultation-continuity.md), and
  [ADR 0015](0015-ai-companion-analysis-contract.md)

> **Current-authority note (2026-09-24):** ADR 0022 prospectively amends the
> consultation-credit quantities and reassessment/user-reflection semantics.
> The original `PLUS=1` / `PREMIUM=3` values below are retained as historical
> ADR 0017 rationale. Current target periods use `PLUS=4` / `PREMIUM=10`, no
> rollover, and concurrent active-reservation caps `2` / `4`. ADR 0022 also
> requires an explicit user-authored reassessment self-report as the canonical
> fourth dimension. Historical records keep their original policy provenance.

## Context

The accepted v1 decisions established service ownership, deterministic
screening and safety, SupportPlan lifecycle, bounded consultation, AI
governance, and credit settlement. They also retained product assumptions that
no longer match the approved v2 scope: `Premium Care`/`Premium Plus` names, USD
catalogue values, in-person consultation, a deferred video mode, and no single
cross-feature definition of Support Guide, reminders, or specialist-proposed
plan changes.

Deleting or rewriting those decisions would remove the rationale for existing
contracts and data. Scope v2 therefore amends them prospectively. Historical
records keep their original plan version, currency, mode, and policy
provenance.

## Decision

### Service packages and entitlements

The only canonical package codes are `FREE`, `PLUS`, and `PREMIUM`.

| Package | Included v2 capability |
| --- | --- |
| `FREE` | Standard one-time Support Guide after screening, Journal, emotion check-in, reviewed resources, and a default AI chat quota of five successfully delivered assistant responses per day |
| `PLUS` | A higher versioned AI quota, persistent SupportPlan capability, and one consultation credit per paid period |
| `PREMIUM` | No daily response limit displayed to the user, while server-side token, rate, abuse, and fair-use limits still apply; a stronger configured model may be used; three consultation credits per paid period; advanced recommendation capability |

The consultation-credit quantities in this original table are superseded for
new target periods by ADR 0022. The table remains unchanged to preserve this
ADR's historical decision text.

Reviewed resources are not count-limited by package. Commercial access controls
gate capabilities, consultation credits, and AI token/request quota, not the
number of resources a user may browse or use. Care may still enforce
non-commercial SupportPlan composition bounds for safety, clarity, and
reproducibility.

Entitlement and quota facts are versioned and server-authoritative. UI wording
such as “unlimited” must not claim infinite provider capacity or bypass token,
rate, abuse, cost, or fair-use enforcement.

### AI authority

The governing invariant remains:

```text
AI understands and supports -> Care decides -> User confirms
```

AI may explain approved content, accompany an active SupportPlan, surface
bounded reassessment context, help express approved reminder content, and
suggest that a governed review flow be opened. AI does not score PHQ-9 or
GAD-7, diagnose, determine safety, decide resource eligibility, own business
state, or mutate a SupportPlan.

`FREE` and `PLUS` may use the same configured model with different quotas.
`PREMIUM` may use a stronger configured model. Package selection never changes
Care's authority or the safety boundary.

### Support Guide, SupportPlan, and specialist input

A `SupportGuide` is a one-time, Care-approved guidance result produced after a
screening. It is available to `FREE`, `PLUS`, and `PREMIUM` and is not a
persistent plan with lifecycle or activity tracking.

For MB-511, Care is the authoritative guide owner. It persists the immutable
guide, exact SupportEvaluation/assessment references, Content publication
provenance, display snapshot, and stable resource-resolution outcome. Content
continues to decide exact-version eligibility and returns the reviewed display
snapshot used at generation time. The web BFF supplies only server-held guided
assessment references. Reload reads the stored snapshot; it does not silently
re-resolve or convert a guide into a SupportPlan. Optional AI may rephrase only
approved copy. The approved Care copy is the required fallback and safety never
waits for Content or AI.

ADR 0022 clarifies that this persisted immutable guide is historical data and
does not automatically expire merely because time passes. “One-time” means one
guidance result for one screening context; it does not mean ephemeral storage.

A `SupportPlan` is durable Care-owned data with an explicit lifecycle and
activity tracking. It is available only to `PLUS` and `PREMIUM`. At most one
official current SupportPlan exists for a user. A replacement draft or change
request is not a second official plan, and becoming current requires the
existing Care transaction and explicit user confirmation.

A specialist does not author a parallel SupportPlan. After a consultation, the
specialist may create only a user-visible `SessionSummary` and
`AgreedNextSteps`. A specialist-proposed resource creates a
`PlanChangeRequest`; Care revalidates current entitlement, plan state, and exact
resource eligibility, presents the allowed change, and applies it only after
the user confirms. Rejection or expiry leaves the current SupportPlan
unchanged.

### Reminders

The default wellbeing reminder is at most one digest per user per day. It may
combine unfinished SupportPlan resources, Journal, and emotion check-in prompts
from approved structured facts. A separate reminder for an individual resource
exists only when the user explicitly enables it.

An appointment reminder is a separate flow and is sent once, approximately one
hour before the scheduled start. The deterministic scheduler and owner policy
decide whether and when to send. AI may only express already approved reminder
content; it cannot schedule, suppress, escalate, or invent a reminder.

No safety email is sent automatically. A safety signal must never be converted
into background email, specialist contact, emergency dispatch, or third-party
notification.

### Appointment and evidence

V2 supports only `IN_APP_CHAT` and `IN_APP_VIDEO`. New `IN_PERSON`, phone, and
external-meeting-link appointments are not supported. Every session is exactly
60 minutes.

At the scheduled end, the appointment becomes `SESSION_ENDED` and Realtime or
the video provider closes interactive chat/video access. Elapsed time alone
does not produce `COMPLETED`, consume a credit, create an earning, or trigger a
payout. Completion requires the versioned evidence policy to accept
server-observed chat evidence or server/provider-observed video evidence.

A `ConsultationBrief` is reviewed and approved by the user before the session.
A `SessionSummary` and `AgreedNextSteps` exist only after the session and require
user approval before either can be reused in a later brief, SupportPlan review,
or AI context.

### Payment, credits, earnings, and payout

Supported commercial acquisition operations are a new paid-package purchase
and an upgrade. There is no downgrade or user-initiated refund API. Period
expiry, payment reconciliation, and administrative revocation are lifecycle
behavior, not alternative purchase operations.

Published v2 plan versions and real payments use VND. MoMo is the only real
payment and payout provider. One evidence-backed `COMPLETED` appointment
consumes exactly one credit.

ADR 0022 changes new target plan-period quantities to `FREE=0`, `PLUS=4`, and
`PREMIUM=10`, with no rollover and concurrent active-reservation caps
`0/2/4`. Historical periods created under the older policy remain immutable.

Each issued credit snapshots a fixed VND `creditAllocation`. A completed
appointment creates a specialist earning equal to 70% of that credit's
snapshotted `creditAllocation`, never 70% of the package price. No earning is
created for `SESSION_ENDED`, cancellation, either no-show, or dispute.

Real-money payment and payout remain disabled until the approved VND price
table, the VND `creditAllocation` for each paid plan version, and the required
MoMo credentials are configured and verified. No runtime FX conversion may be
used to fill this gap.

### Safety activation and directory language

Care activates the safety flow when PHQ-9 item 9 is positive under its
versioned rule or when the user explicitly selects “Tôi cần hỗ trợ ngay”. A
`High` or `Severe` instrument band alone is not a safety trigger.

The user manually enters or selects province, district, or another supported
area. The product does not infer or share precise location. Without coordinates
and a distance calculation, results must be described as “cơ sở trong khu vực
đã chọn”, never “gần nhất”.

Every directory entry used by this flow records source and provenance,
`reviewedAt`, `verifiedAt`, address, phone, coverage area, and active status.
Unverified, inactive, or out-of-coverage entries are not presented as current
options. MentalBridge does not automatically call, share location, notify a
third party, or claim guaranteed response.

## Ownership

- Care owns screening, both safety triggers, Support Guide, SupportPlan,
  reassessment composition, eligibility revalidation, `PlanChangeRequest`
  decisions, and user confirmation.
- Consultation/Billing owns package versions, entitlements, credits,
  appointments, evidence evaluation, summaries, earnings, and payouts.
- Realtime owns in-app chat delivery; the selected video adapter remains behind
  a Consultation/Realtime contract without owning appointment truth.
- Content/Notification owns reviewed resources, directory content,
  notification preferences, scheduler execution, and delivery attempts. It
  does not decide safety or appointment state.
- Journal/AI owns AI conversations and normalized analysis results but owns no
  Care, reminder, appointment, entitlement, or financial state.

Owners communicate through versioned contracts and never read another owner's
storage.

## Compatibility and migration consequences

- Historical `Premium Care` and `Premium Plus` plan versions map conceptually
  to `PLUS` and `PREMIUM`, respectively, but stored codes, prices, currencies,
  credits, appointments, and earnings are not rewritten. New APIs and UI use
  only the v2 canonical names.
- Existing USD catalogue versions are historical/demo-only and cannot enable
  real payment. V2 publishes new immutable VND plan versions after prices and
  `creditAllocation` are approved.
- Historical `IN_PERSON` appointments remain readable and settle under their
  original versioned policy. V2 rejects new `IN_PERSON` bookings. Enabling
  `IN_APP_VIDEO` requires a versioned signaling/provider, authorization,
  evidence, failure, and recording-prohibition contract before runtime use.
- Existing v1 SupportPlan records and selection provenance remain immutable.
  Support Guide, tier entitlement, `PlanChangeRequest`, reminder, summary reuse,
  reassessment self-report, and plan-review behavior require compatible
  contracts and append-only owner migrations.
- Existing `consultation-credit-v1` `0/1/3` periods and pre-amendment
  ReassessmentSummary snapshots remain readable under their original
  provenance; they are not rewritten to new target policy.
- Existing runtime and contracts must not claim v2 or ADR-0022 availability
  until their delivery gates pass. These ADRs approve target behavior; they do
  not silently make an endpoint, provider, credential, migration, or UI
  executable.

## Consequences

- Package display and authorization can use one stable vocabulary while quota
  and model choices remain versioned configuration.
- `FREE` users retain useful post-screening guidance without receiving a durable
  paid SupportPlan.
- Specialist recommendations, AI assistance, reassessment, and reminders enter
  governed owner flows rather than mutating business state.
- A closed 60-minute channel is not financial proof; completion, credit
  consumption, earning creation, and payout remain evidence-backed.
- Location language and directory provenance match the product's actual
  capabilities without implying geolocation, proximity, or emergency action.

## Rejected alternatives

- Delete or rewrite the v1 ADR history: rejected because existing contracts and
  records need their original rationale and provenance.
- Limit resource count by package: rejected because packages differentiate
  capabilities, credits, and AI quota rather than access to reviewed resources.
- Treat `SESSION_ENDED` as completion: rejected because elapsed time alone does
  not prove delivery and cannot justify credit consumption or earning creation.
- Let AI, a specialist, or the scheduler own Care state: rejected because
  governed decisions and user confirmation would be bypassed.
- Describe area-filtered facilities as nearest: rejected because proximity is
  unknown without coordinates and a distance calculation.
