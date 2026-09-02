# Care Service specification

## Business boundary

Care owns user profiles, consent decisions, specialist access grants, questionnaire definitions, assessment attempts/results, deterministic safety status, support-tier selection, intervention selection, follow-up, and wellbeing projections. It is the authority for current consent and safety decisions. These paths remain correct without AI, Kafka, Redis, WebSocket, or notification delivery.

## Use cases and acceptance

| Capability | Main behavior | Acceptance |
| --- | --- | --- |
| Profile/privacy | Update profile and independent consent decisions | Versioned consent evidence; unsupported/null fields rejected; revoked consent blocks new reads immediately |
| Anonymous screening | Fetch current PHQ-9/GAD-7 and submit opaque attempt | Complete 0..3 answers only; deterministic score; short-lived result; no silent account link; guidance in response |
| Authenticated assessment | Start and idempotently submit immutable versioned attempt | Exact question version; no partial final score; duplicate key returns original; score/result/outbox atomically commit |
| Safety/support/intervention | Evaluate independent versioned safety and support policies | Item-9 status never changes the questionnaire band; missing/stale support input gives insufficient data; AI cannot override safety; approved guidance is synchronous |
| Specialist grant | Grant exact scopes, range/entries, purpose and expiry; authorize reads | Appointment never implies journal access; current grant checked by owner; revoke/read race fails closed and is audited |
| Follow-up/analytics | Manage milestones/check-ins and compare valid results | Missing differs from zero; source/freshness exposed; no causal/diagnostic claims |
| Export/deletion | Export owned data and participate in deletion workflow | Scope and retention policy explicit; retries idempotent; evidence contains no deleted content |

## Implementation design

- Feature slices: `profile`, `consent`, `assessment`, `support`, `intervention`, `followup`, `analytics`, `data-rights`.
- OpenAPI defines public assessment/profile APIs and future minimal internal consent-authorization decisions. Kafka schemas carry minimized assessment/support/consent/follow-up facts.
- PostgreSQL and Liquibase own scoring inputs/results, policy provenance, grants and outbox. Constraints enforce immutable published questionnaires and unique submissions.
- Safety calculation and reviewed fallback are pure local domain behavior. Structured journal indicators arrive asynchronously and never include raw journal text.
- Exceptional Identity lookups use a consumer-owned Feign port outside transactions with timeout/breaker and safe failure semantics.

## MB-88 delivered foundation

MB-88 defines the profile, platform-consent, and PHQ-9 contract/persistence foundation. MB-89 implements public questionnaire retrieval plus authenticated and anonymous PHQ-9 submission/read handlers with JWT or hashed session-token authorization, server-owned scoring, item-9 safety status, owner-scoped idempotency, and an atomic minimized outbox fact. Profile and consent handlers remain planned. The executable Liquibase history contains profile and append-only consent evidence, isolated anonymous sessions, versioned questionnaire reference data, score bands, immutable assessment submission/answer/result shapes, and a transactional outbox table.

The original English PHQ-9 definition and the exact `phq9-vi-vn-capstone-v1` definition are seeded. ADR 0009 fixes the item-9 decision boundary, non-paywall rule, AI boundary, and removal of the hotline catalogue. ADR 0010 separates controlled Capstone questionnaire publication from production governance and optional support features. MB-177 records the Vietnamese artifact, import and tests. The [MB-179 blueprint](../sprints/mb-179-screening-to-support-blueprint.md) defines future support, specialist handoff, reassessment and progress boundaries without claiming their runtime; intervention and production consent/retention remain separate gates in `docs/policies/`.

MB-88 does not implement specialist grants, support/intervention, follow-up, analytics, export/deletion, Kafka event schemas, or runtime controllers. Those remain in the ordered tasks below and must not be inferred from the foundation tables.

## Ordered tasks

- [x] CARE-01 Complete the PHQ-9 Capstone publication and MB-179 product-definition approval records; GAD-7, executable support, specialist sharing and production gates remain explicitly unavailable until their own checklists pass.
- [ ] CARE-02 Extend the MB-88 profile, platform-consent and PHQ-9 OpenAPI foundation with consent authorization, safety/support, intervention, follow-up and analytics contracts.
- [ ] CARE-03 Define assessment/support/consent/follow-up event schemas and journal-indicator consumer contract.
- [ ] CARE-04 Extend the MB-88 Liquibase/reference-data foundation with approved grants, safety/support, intervention and follow-up persistence.
- [ ] CARE-05 Implement profile and independent consent/grant decisions with concurrent revoke/read protection.
- [x] CARE-06 Implement anonymous and authenticated assessment, validation, scoring and idempotency, including published `phq9-vi-vn-capstone-v1` reference data.
- [ ] CARE-07 Implement deterministic safety/support behavior and approved-catalogue intervention with synchronous fallback and provenance.
- [ ] CARE-08 Implement follow-up, comparison projections, export and deletion participation.
- [ ] CARE-09 Verify scoring boundaries, item-9 safety, stale/missing input, concurrency, rollback/outbox, duplicate/reordered events and dependency failures.
- [ ] CARE-10 Add safe observability/configuration, update README, and pass module/contract/migration gates.
