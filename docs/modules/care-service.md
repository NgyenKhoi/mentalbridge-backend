# Care Service specification

## Business boundary

Care owns user profiles, consent decisions, specialist access grants, questionnaire definitions, assessment attempts/results, deterministic safety status, versioned SupportEvaluations, system-proposed SupportPlan selection/lifecycle, follow-up, and wellbeing projections. V1 screening and post-screening support are limited to PHQ-9/`DEPRESSIVE_SYMPTOMS` and GAD-7/`ANXIETY_SYMPTOMS`. Care is the authority for current consent, safety and final plan-eligibility decisions. These paths remain correct without AI, Kafka, Redis, WebSocket, or notification delivery.

## Use cases and acceptance

| Capability | Main behavior | Acceptance |
| --- | --- | --- |
| Profile/privacy | Update profile and independent consent decisions | Versioned consent evidence; unsupported/null fields rejected; revoked consent blocks new reads immediately |
| Anonymous screening | Fetch current PHQ-9/GAD-7 and submit opaque attempt | Complete 0..3 answers only; deterministic score; short-lived result; no silent account link; guidance in response |
| Authenticated assessment | Start and idempotently submit immutable versioned attempt | Exact question version; no partial final score; duplicate key returns original; score/result/outbox atomically commit |
| Safety/support/SupportPlan | Evaluate instrument/domain-specific evidence and independent safety, then propose and manage a bounded plan | Item-9 status never changes a band; no global severity; missing/stale input gives insufficient data; v1 tier alone cannot select a plan; user cannot submit an arbitrary initial resource set; AI cannot determine eligibility; approved safety guidance is synchronous |
| Specialist grant | Grant exact scopes, range/entries, purpose and expiry; authorize reads | Appointment never implies journal access; current grant checked by owner; revoke/read race fails closed and is audited |
| Follow-up/analytics | Manage milestones/check-ins and compare valid results | Missing differs from zero; source/freshness exposed; no causal/diagnostic claims |
| Export/deletion | Export owned data and participate in deletion workflow | Scope and retention policy explicit; retries idempotent; evidence contains no deleted content |

## Implementation design

- Feature slices: `profile`, `consent`, `assessment`, `progress`, `support`, `intervention`, `followup`, `analytics`, `data-rights`.
- OpenAPI defines public assessment/profile APIs and future minimal internal consent-authorization decisions. Kafka schemas carry minimized assessment/support/consent/follow-up facts.
- PostgreSQL and Liquibase own scoring inputs/results, policy provenance, grants and outbox. Constraints enforce immutable published questionnaires and unique submissions.
- Safety calculation and reviewed fallback are pure local domain behavior. Structured journal indicators arrive asynchronously and never include raw journal text.
- Active `mb-support-routing-capstone-v1` remains immutable coarse history. Compatible domain-bearing SupportEvaluation work belongs to #48; system-proposed SupportPlan decisions/contracts belong to #49.
- Care consumes exact Content-owned resource definitions/eligibility through a versioned contract and makes the final plan decision without cross-database access. Review/publication alone is insufficient.
- Exceptional Identity lookups use a consumer-owned Feign port outside transactions with timeout/breaker and safe failure semantics.

## MB-88 delivered foundation

MB-88 defines the profile, platform-consent, and PHQ-9 contract/persistence foundation. MB-89 implements public questionnaire retrieval plus authenticated and anonymous PHQ-9 submission/read handlers with JWT or hashed session-token authorization, server-owned scoring, item-9 safety status, owner-scoped idempotency, and an atomic minimized outbox fact. MB-178 implements own-profile optimistic concurrency, backend-owned versioned privacy disclosure, append-only `PRIVACY_POLICY` decisions, consent-gated authenticated submission, disclosure-gated anonymous submission, stable owned history pagination, immutable result reopening, and user-initiated reassessment. Story 1102 makes `privacy-capstone-v3` current for new PHQ-9/GAD-7 submissions while preserving v1/v2 historical policy references without backfill. MB-205 implements owner-only descriptive progress by selecting the immediately preceding non-voided same-instrument result with an identical scoring version and returning only score/band/elapsed comparison data. The executable Liquibase history contains profile and append-only consent evidence, isolated anonymous sessions, versioned questionnaire reference data, score bands, immutable assessment submission/answer/result shapes, disclosure provenance, and a transactional outbox table.

MB-205 is contained in `com.mentalbridge.care.progress` (controller, read-only service, owner-scoped JDBC repository and arithmetic direction). It reuses `ix_assessment_submission_user_history`; measured integration behavior needs no new migration or data-dictionary field.

The original English PHQ-9 definition, retired immutable `phq9-vi-vn-capstone-v1`, current corrected `phq9-vi-vn-capstone-v2`, and published `gad7-vi-vn-adult-v1` are seeded. Story 1102 adds instrument-aware scoring, GAD-7 non-applicable safety semantics, immutable definition lookup, and version-2 assessment events while preserving event v1. ADR 0009 fixes the PHQ-9 item-9 boundary, non-paywall rule, AI boundary, and removal of the hotline catalogue. ADR 0010 separates controlled Capstone questionnaire publication from production governance and optional support features. ADR 0012 fixes the two-domain and system-proposed SupportPlan direction without changing current contracts or runtime. SupportPlan and production consent/retention remain separate gates in `docs/policies/`.

MB-178 does not implement specialist grants, automatic follow-up, clinical progress interpretation, export/deletion, or production retention. Support/intervention, analytics, Kafka event delivery, and these deferred workflows must not be inferred from the implemented profile/consent/history slice.

## Ordered tasks

- [x] CARE-01 Complete PHQ-9 and GAD-7 controlled-Capstone publication records; specialist sharing, domain-aware SupportPlans, and production gates remain explicitly unavailable until their own checklists pass.
- [ ] CARE-02 Extend the MB-88 profile, platform-consent and PHQ-9 OpenAPI foundation with consent authorization, safety/support, intervention, follow-up and analytics contracts.
- [ ] CARE-03 Define assessment/support/consent/follow-up event schemas and journal-indicator consumer contract.
- [ ] CARE-04 Extend the MB-88 Liquibase/reference-data foundation with approved grants, safety/support, intervention and follow-up persistence.
- [ ] CARE-05 Profile and general privacy decisions are implemented by MB-178; specialist scoped grants and their concurrent revoke/read protection remain deferred.
- [x] CARE-06 Implement anonymous and authenticated assessment, validation, scoring and idempotency for published PHQ-9 and GAD-7 reference data while retaining retired definitions for history.
- [x] CARE-07a Publish deterministic cross-cutting safety, coarse `mb-support-routing-capstone-v1`, synchronous fallback and provenance through Story 1103.
- [ ] CARE-07b Preserve v1 history; implement compatible domain-aware SupportEvaluation (#48) only after policy/contract approval, then system-proposed SupportPlans (#49) using exact eligible resources (#50).
- [ ] CARE-08 Descriptive assessment comparison is implemented by MB-205; follow-up, other analytics projections, export and deletion participation remain open.
- [ ] CARE-09 Verify scoring boundaries, item-9 safety, stale/missing input, concurrency, rollback/outbox, duplicate/reordered events and dependency failures.
- [ ] CARE-10 Add safe observability/configuration, update README, and pass module/contract/migration gates.
