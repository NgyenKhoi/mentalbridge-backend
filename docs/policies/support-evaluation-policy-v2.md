# SupportEvaluation policy v2

## Policy metadata

| Field | Value |
| --- | --- |
| Policy version | `mb-support-routing-capstone-v2` |
| Status | Controlled Capstone implementation; production domain review pending |
| Effective date | 2026-09-16 |
| Owner | Care |
| Applies to | Authenticated users with one owned compatible PHQ-9 result and one owned compatible GAD-7 result |
| Decisions | [ADR 0012](../adr/0012-two-domain-screening-and-system-proposed-support-plans.md) and [ADR 0013](../adr/0013-freeze-support-plan-policy-v1.md) |
| Contract | [`care-support-evaluation-v2.yaml`](../../contracts/openapi/care-support-evaluation-v2.yaml) |

This version is additive. It does not modify, backfill, reinterpret, or replace
`mb-support-routing-capstone-v1`, its REST resources, its database rows, or its
`care.support-tier.resolved` events.

## Inputs and compatibility

The command explicitly supplies one PHQ-9 assessment identifier and one GAD-7
assessment identifier. Both results must be owned by the authenticated user,
complete, non-voided, and listed with their exact questionnaire and scoring
versions in the v2 eligibility table. Care does not infer a latest result or a
freshness window.

The controlled version accepts:

- PHQ-9 `phq9-vi-vn-capstone-v1` or `phq9-vi-vn-capstone-v2` with
  `phq9-standard-bands-v1`;
- GAD-7 `gad7-vi-vn-adult-v1` with `gad7-standard-bands-v1`.

Missing or foreign evidence returns the same not-found response. Voided,
incomplete, wrong-instrument, or unlisted versions return
`SUPPORT_EVIDENCE_INCOMPATIBLE`. The same assessment cannot fill both inputs.

## Domain contributions

The immutable result contains exactly two ordered domain snapshots. No
top-level support tier, combined score, global severity, or global
mental-health level exists.

| Instrument | Domain | Level | Domain-local pathway | Stable reason code |
| --- | --- | --- | --- | --- |
| PHQ-9 | `DEPRESSIVE_SYMPTOMS` | `MINIMAL` | `SELF_GUIDED_SUPPORT` | `PHQ9_LEVEL_MINIMAL` |
| PHQ-9 | `DEPRESSIVE_SYMPTOMS` | `MILD` | `SELF_GUIDED_SUPPORT` | `PHQ9_LEVEL_MILD` |
| PHQ-9 | `DEPRESSIVE_SYMPTOMS` | `MODERATE` | `PROFESSIONAL_SUPPORT_RECOMMENDED` | `PHQ9_LEVEL_MODERATE` |
| PHQ-9 | `DEPRESSIVE_SYMPTOMS` | `MODERATELY_SEVERE` | `PROFESSIONAL_SUPPORT_RECOMMENDED` | `PHQ9_LEVEL_MODERATELY_SEVERE` |
| PHQ-9 | `DEPRESSIVE_SYMPTOMS` | `SEVERE` | `PROFESSIONAL_SUPPORT_RECOMMENDED` | `PHQ9_LEVEL_SEVERE` |
| GAD-7 | `ANXIETY_SYMPTOMS` | `MINIMAL` | `SELF_GUIDED_SUPPORT` | `GAD7_LEVEL_MINIMAL` |
| GAD-7 | `ANXIETY_SYMPTOMS` | `MILD` | `SELF_GUIDED_SUPPORT` | `GAD7_LEVEL_MILD` |
| GAD-7 | `ANXIETY_SYMPTOMS` | `MODERATE` | `PROFESSIONAL_SUPPORT_RECOMMENDED` | `GAD7_LEVEL_MODERATE` |
| GAD-7 | `ANXIETY_SYMPTOMS` | `SEVERE` | `PROFESSIONAL_SUPPORT_RECOMMENDED` | `GAD7_LEVEL_SEVERE` |

Each contribution snapshots its assessment and questionnaire-definition IDs,
instrument, domain, questionnaire/scoring versions, instrument-specific level,
domain-local pathway, and reason code. Downstream policy composes one family
per domain from these facts rather than interpreting a global classification.

## Independent safety evidence

Safety is stored and returned as a separate PHQ-9 item-9 object. It references
the exact PHQ-9 assessment and safety-policy version and contains either
`PHQ9_ITEM9_NEGATIVE` with `NEGATIVE_SAFETY_SCREEN` or
`PHQ9_ITEM9_POSITIVE` with `POSITIVE_SAFETY_SCREEN`. It does not alter either
domain's level, reason, or pathway and does not represent intent, plan,
imminence, urgency, or diagnosis. GAD-7 never supplies this safety object.

The response and v2 event contain no raw answer, item-9 value, or total score.
Care produces the evaluation synchronously from local owned data and does not
wait for Content, AI, Kafka, Redis, Realtime, or notification delivery.

## Authorization, idempotency, and publication

Only a `USER` may create or read v2 evaluations. Reads are owner-scoped and use
an opaque not-found response for another owner's identifier. `Idempotency-Key`
is scoped to the owner: the same key and evidence replays the original result;
different evidence conflicts. Different keys for the same evidence pair and
policy resolve to one aggregate.

The aggregate, two domain snapshots, safety snapshot, idempotency alias, and
`care.support-evaluation.created` v2 outbox fact commit atomically. The event is
minimized and versioned separately from `care.support-tier.resolved` v1.

Corrections are forward-only: publish a new policy/contract version and an
append-only Liquibase changeset. Never update historical v2 aggregates,
domain/safety snapshots, request aliases, or v1 data in place.
