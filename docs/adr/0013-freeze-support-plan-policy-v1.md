# ADR 0013: Freeze SupportPlan policy v1

- Status: Accepted
- Date: 2026-09-13
- Decision ID: `MB-SUPPORT-PLAN-POLICY-001`
- Reassessment extension: [ADR 0015](0015-ai-companion-analysis-contract.md)
- Amended by: [ADR 0017](0017-product-scope-v2.md), which distinguishes the all-tier one-time Support Guide from the `PLUS`/`PREMIUM` durable SupportPlan and governs specialist proposals through `PlanChangeRequest`
- Tracks: [#49](https://github.com/NgyenKhoi/mentalbridge-backend/issues/49)
- Resolves: the SupportPlan policy questions left open by [ADR 0012](0012-two-domain-screening-and-system-proposed-support-plans.md)
- Policy: [SupportPlan policy v1](../policies/support-plan-policy-v1.md)

## Context

ADR 0012 established that Care must create a bounded, domain-aware SupportPlan
proposal from an immutable SupportEvaluation and exact eligible content. It
deliberately left template storage, slot meaning, resource bounds, selection
rules, safety-positive activation, and cross-domain resource roles open for the
Product Owner.

Those questions must be fixed before SupportEvaluation v2, Resource Eligibility
v1, or a SupportPlan contract can be implemented without product inference.
The policy must stay simple enough for the controlled Capstone, preserve audit
history, and avoid a separate template for every PHQ-9/GAD-7 band combination.

## Decision

### Immutable template policy

`SupportPlanTemplate` is immutable versioned Care policy data, not a mutable
user-owned aggregate. A published template version is never updated. A policy
change retires the old version and publishes a new version. V1 exposes no
template administration CRUD.

`SupportPlan` is the user-owned runtime instance. It records the exact template
versions and `mb-support-plan-selection-v1` policy version that produced it.

### Slots and bounds

Templates contain `CORE` and `OPTIONAL` slots:

- every `CORE` slot must contain one currently eligible alternative before
  activation;
- a user may swap the selected resource only for another alternative admitted
  for that slot;
- an `OPTIONAL` slot may be kept, swapped, or removed;
- safety guidance and professional-support calls to action are outside plan
  slots.

An activatable SupportPlan contains 1 through 5 selected resources. Templates
use 1 through 2 core slots and 0 through 3 optional slots where applicable, and
the normal proposal target is 2 through 3 resources. Composition, deduplication,
and truncation are server policy; a client never chooses what to discard when
the composed candidates exceed the bound.

### Deterministic composition

Care applies versioned deterministic composition rules identified by
`mb-support-plan-selection-v1`. It selects independent template families for
each contributing domain and composes them. It does not store or maintain a
matrix row for every PHQ-9/GAD-7/safety combination.

The approved families are:

- `DEPRESSIVE_MAINTENANCE` for PHQ-9 Minimal;
- `DEPRESSIVE_SELF_GUIDED` for PHQ-9 Mild;
- `DEPRESSIVE_PROFESSIONAL_ADJUNCT` for PHQ-9 Moderate or higher;
- `ANXIETY_MAINTENANCE` for GAD-7 Minimal;
- `ANXIETY_SELF_GUIDED` for GAD-7 Mild;
- `ANXIETY_PROFESSIONAL_ADJUNCT` for GAD-7 Moderate or higher.

`SAFETY_OVERLAY` is not a SupportPlan template. It remains a separate
cross-cutting presentation and support layer.

### Eligibility roles

Resource Eligibility v1 has `PRIMARY` and `ADJUNCT` roles for an exact content
version and target domain. Only `PRIMARY` may satisfy a domain `CORE` slot.
`ADJUNCT` may fill an optional slot but can never prove that domain-specific
core support exists. Review, publication, or resource type alone never grants
either role.

### Safety-positive activation

Safety-positive users use the normal explicit SupportPlan activation command;
there is no additional acknowledgement checkbox. Safety guidance and available
immediate or professional-support options must be presented before ordinary
plan controls. A SupportPlan remains secondary support and never proves that a
user is safe or that a human has been contacted.

### Lifecycle

Per user, Care permits at most one `DRAFT` and at most one `ACTIVE` or `PAUSED`
SupportPlan. `COMPLETED` and `SUPERSEDED` history is unlimited. A user may edit
the draft only through its allowed slot alternatives, remove optional choices,
keep it, or discard it.

Replacing a current plan is one Care transaction: the current plan becomes
`SUPERSEDED` and the replacement draft becomes `ACTIVE`. A new assessment or
SupportEvaluation never creates, activates, replaces, or supersedes a plan
automatically.

## Consequences

- Care owns template policy, deterministic composition, plan admission, and
  lifecycle. Content/Notification owns exact resource eligibility provenance.
- A proposal command accepts the owned SupportEvaluation reference only; it
  never accepts a client-authored initial resource list, tier, template family,
  domain, or eligibility decision.
- Activation and replacement must revalidate the evaluation, template policy,
  exact content version, eligibility role, publication state, and effective
  window before opening the Care transaction.
- Existing `mb-support-routing-capstone-v1` contracts and rows remain unchanged.
- The first proposal/lifecycle implementation requires new compatible contracts
  and append-only owner migrations; this ADR does not make an endpoint or table
  executable.
- After accountable Care, Content, Frontend, and safety acceptance, issue #49
  may close as the policy gate. Provider and runtime delivery remain separate
  stories and must not be claimed by that closure.
- Scheduling, reminders, activity tracking, AI selection/reranking, community,
  gamification, specialist booking, and payment remain outside this policy.

## Rejected alternatives

- Mutable template administration in V1: rejected because published policy
  must remain reproducible and the Capstone does not need template CRUD.
- A mandatory named resource: rejected because a core slot requires an eligible
  role while preserving bounded user choice among reviewed alternatives.
- A persisted combination matrix: rejected because composition avoids
  PHQ-9/GAD-7/safety combination explosion.
- Client-side composition or truncation: rejected because it bypasses Care's
  deterministic policy and audit boundary.
- A separate safety acknowledgement checkbox: rejected because it neither
  proves understanding nor safety and resembles unsupported clinical consent.
