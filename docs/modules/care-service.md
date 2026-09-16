# Care Service specification

## Business boundary

Care owns user profiles, consent decisions, specialist access grants,
questionnaire definitions, assessment attempts/results, deterministic item-9
and explicit-user safety triggers, the all-tier one-time Support Guide,
versioned SupportEvaluations, the single official `PLUS`/`PREMIUM` SupportPlan,
`PlanChangeRequest` decisions, follow-up, and wellbeing projections. V1
screening domains remain PHQ-9/`DEPRESSIVE_SYMPTOMS` and
GAD-7/`ANXIETY_SYMPTOMS`. These paths remain correct without optional AI,
Kafka, Redis, WebSocket, notification, or directory delivery.

## Use cases and acceptance

| Capability | Main behavior | Acceptance |
| --- | --- | --- |
| Profile/privacy | Update profile and independent privacy/AI-processing consent decisions | Versioned consent evidence; unsupported/null fields rejected; revoked `AI_PROCESSING` blocks new provider attempts immediately |
| Anonymous screening | Fetch current PHQ-9/GAD-7 and submit opaque attempt | Complete 0..3 answers only; deterministic score; short-lived result; no silent account link; guidance in response |
| Authenticated assessment | Start and idempotently submit immutable versioned attempt | Exact question version; no partial final score; duplicate key returns original; score/result/outbox atomically commit |
| Safety/Support Guide/SupportPlan | Activate safety from positive item 9 or explicit user action, return a one-time guide for every package, and manage one official paid plan | High/Severe band alone is not a safety trigger; no global severity; `FREE` has no durable plan; specialist input is a request; Care revalidates and user confirms; approved safety guidance is synchronous |
| Reassessment Summary | Present standardized screening trend, AI-derived available-journal context, SupportPlan engagement, and user reflection as separate dimensions | No combined improvement score/recovery claim; journal coverage visible; Care selects only policy-allowed plan alternatives and the user confirms changes |
| Specialist grant | Grant exact scopes, range/entries, purpose and expiry; authorize reads | Appointment never implies journal access; current grant checked by owner; revoke/read race fails closed and is audited |
| Follow-up/analytics | Manage milestones/check-ins and compare valid results | Missing differs from zero; source/freshness exposed; no causal/diagnostic claims |
| Export/deletion | Export owned data and participate in deletion workflow | Scope and retention policy explicit; retries idempotent; evidence contains no deleted content |

## Implementation design

- Feature slices: `profile`, `consent`, `assessment`, `progress`, `reassessment-summary`, `support`, `intervention`, `followup`, `analytics`, `data-rights`.
- OpenAPI defines public assessment/profile APIs plus the implemented minimal current-user `AI_PROCESSING` authorization decision consumed with forwarded bearer context. Kafka schemas carry minimized assessment/support/consent/follow-up facts only for separately accepted publication needs.
- PostgreSQL and Liquibase own scoring inputs/results, policy provenance, grants and outbox. Constraints enforce immutable published questionnaires and unique submissions.
- Safety calculation and reviewed fallback are pure local domain behavior. Structured journal indicators arrive asynchronously and never include raw journal text.
- Active `mb-support-routing-capstone-v1` remains immutable coarse history.
  ADR 0017 and SupportPlan policy v2 add Support Guide, paid-plan entitlement,
  one official plan, and `PlanChangeRequest` governance without rewriting v1
  evidence. Runtime remains a later delivery slice using exact eligibility.
- Care consumes exact Content-owned eligibility through the generated Resource Eligibility v1 OpenFeign boundary with explicit deadlines, bounded retry, circuit breaker and fail-closed `UNAVAILABLE` outcomes. It makes the final future plan decision without cross-database access or a transaction spanning the remote call. Review/publication alone is insufficient.
- Care combines reassessment dimensions without normalizing them into one score. Journal/AI context remains model-derived evidence limited to available consented entries; Care maps it only to policy-allowed review candidates and requires user confirmation.
- Exceptional Identity lookups use a consumer-owned Feign port outside transactions with timeout/breaker and safe failure semantics.

## MB-88 delivered foundation

MB-88 defines the profile, platform-consent, and PHQ-9 contract/persistence foundation. MB-89 implements public questionnaire retrieval plus authenticated and anonymous PHQ-9 submission/read handlers with JWT or hashed session-token authorization, server-owned scoring, item-9 safety status, owner-scoped idempotency, and an atomic minimized outbox fact. MB-178 implements own-profile optimistic concurrency, backend-owned versioned privacy disclosure, append-only `PRIVACY_POLICY` decisions, consent-gated authenticated submission, disclosure-gated anonymous submission, stable owned history pagination, immutable result reopening, and user-initiated reassessment. Story 1102 makes `privacy-capstone-v3` current for new PHQ-9/GAD-7 submissions while preserving v1/v2 historical policy references without backfill. MB-367 activates the already reserved `AI_PROCESSING` database value with immutable `ai-processing-capstone-v1` disclosure, append-only grant/revoke behavior, and a minimal bearer-protected authorization response; it adds no Care migration or frontend UI. MB-205 implements owner-only descriptive progress by selecting the immediately preceding non-voided same-instrument result with an identical scoring version and returning only score/band/elapsed comparison data. The executable Liquibase history contains profile and append-only consent evidence, isolated anonymous sessions, versioned questionnaire reference data, score bands, immutable assessment submission/answer/result shapes, disclosure provenance, and a transactional outbox table.

MB-205 is contained in `com.mentalbridge.care.progress` (controller, read-only service, owner-scoped JDBC repository and arithmetic direction). It reuses `ix_assessment_submission_user_history`; measured integration behavior needs no new migration or data-dictionary field.

The original English PHQ-9 definition, retired immutable
`phq9-vi-vn-capstone-v1`, current corrected `phq9-vi-vn-capstone-v2`, and
published `gad7-vi-vn-adult-v1` are seeded. Existing item-9 runtime remains
unchanged. ADR 0017 approves the additional explicit-help trigger and reviewed
area directory as target behavior without claiming they are implemented.
Support Guide, SupportPlan, directory, and production consent/retention remain
separate runtime gates in `docs/policies/`.

MB-178 does not implement specialist grants, automatic follow-up, clinical progress interpretation, export/deletion, or production retention. Support/intervention, analytics, Kafka event delivery, and these deferred workflows must not be inferred from the implemented profile/consent/history slice.

## Ordered tasks

- [x] CARE-01 Complete PHQ-9 and GAD-7 controlled-Capstone publication records; specialist sharing, domain-aware SupportPlans, and production gates remain explicitly unavailable until their own checklists pass.
- [ ] CARE-02 Extend the MB-88 profile, platform-consent and PHQ-9 OpenAPI foundation with consent authorization, safety/support, intervention, follow-up and analytics contracts.
- [ ] CARE-03 Define assessment/support/consent/follow-up event schemas and journal-indicator consumer contract.
- [ ] CARE-04 Extend the MB-88 Liquibase/reference-data foundation with approved grants, safety/support, intervention and follow-up persistence.
- [ ] CARE-05 Profile and general privacy decisions are implemented by MB-178; specialist scoped grants and their concurrent revoke/read protection remain deferred.
- [x] CARE-06 Implement anonymous and authenticated assessment, validation, scoring and idempotency for published PHQ-9 and GAD-7 reference data while retaining retired definitions for history.
- [x] CARE-07a Publish deterministic cross-cutting safety, coarse `mb-support-routing-capstone-v1`, synchronous fallback and provenance through Story 1103.
- [ ] CARE-07b Preserve v1 history; close the #49 policy gate under ADR 0013, implement compatible domain-aware SupportEvaluation (#48) and exact eligible resources (#50), then deliver system-proposed SupportPlan runtime as a separate story.
- [ ] CARE-08 Descriptive assessment comparison is implemented by MB-205; follow-up, other analytics projections, export and deletion participation remain open.
- [ ] CARE-09 Verify scoring boundaries, item-9 safety, stale/missing input, concurrency, rollback/outbox, duplicate/reordered events and dependency failures.
- [ ] CARE-10 Add safe observability/configuration, update README, and pass module/contract/migration gates.
