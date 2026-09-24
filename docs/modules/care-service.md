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
| Safety/Support Guide/SupportPlan | Activate safety from positive item 9 or explicit user action, persist one immutable Guide per screening context for every package, and manage one official paid plan | High/Severe band alone is not a safety trigger; no global severity; the Guide has no automatic time-based expiry or plan lifecycle; `FREE` has no durable plan; specialist input is a request; Care revalidates and user confirms; approved safety guidance is synchronous |
| Reassessment Summary | Idempotently persist and query screening trend, minimized available-journal context, SupportPlan engagement, and explicit owner-authored current-period experience | V2 current/detail/history reads return immutable source/version/coverage snapshots; activity reflection is separately labelled supporting evidence, missing/deleted self-report is explicit, v1 history remains readable, and no combined improvement score or recovery claim exists |
| Specialist grant | Grant exact scopes, range/entries, purpose and expiry; authorize reads | Appointment never implies journal access; current grant checked by owner; revoke/read race fails closed and is audited |
| Follow-up/analytics | Manage milestones/check-ins and compare valid results | Missing differs from zero; source/freshness exposed; no causal/diagnostic claims |
| Export/deletion | Export owned data and participate in deletion workflow | Scope and retention policy explicit; retries idempotent; evidence contains no deleted content |

## Implementation design

- Feature slices: `profile`, `consent`, `assessment`, `progress`, `reassessment-summary`, `support`, `intervention`, `followup`, `analytics`, `data-rights`.
- OpenAPI defines public assessment/profile APIs plus the implemented minimal current-user `AI_PROCESSING` authorization decision consumed with forwarded bearer context. Kafka schemas carry minimized assessment/support/consent/follow-up facts only for separately accepted publication needs.
- PostgreSQL and Liquibase own scoring inputs/results, policy provenance, grants and outbox. Constraints enforce immutable published questionnaires and unique submissions.
- Safety calculation and reviewed fallback are pure local domain behavior. Reassessment reads minimized structured journal indicators synchronously through the canonical internal REST projection and never receives raw journal text.
- Active `mb-support-routing-capstone-v1` remains immutable coarse history.
  Additive `mb-support-routing-capstone-v2` snapshots both approved domains and
  independent item-9 safety through owner-scoped `/api/v2` resources without a
  global tier or severity.
  MB-511 implements the one-time all-tier Support Guide on the v2 evaluation
  boundary using exact eligibility. MB-372 adds the bounded initial
  `PLUS`/`PREMIUM` draft and exact persisted reload. MB-373 adds admitted-choice
  mutation, exact revalidation, explicit activation, one-current-plan
  enforcement, and authoritative current reload. MB-374 adds the explicit
  owner lifecycle/history while MB-513 adds deterministic activity schedules
  and occurrences. MB-376 adds owner-only
  occurrence engagement with minimized event provenance. MB-386 adds immutable
  compatibility Reassessment Summary composition and owner-only current/detail/
  history queries without rewriting source evidence. The canonical explicit
  reassessment self-report, governed `PlanChangeRequest`, and actor-facing
  reassessment UI are completed by MB-559; richer history/presentation remains a later slice.
- Care consumes exact Content-owned eligibility through the generated Resource Eligibility v1 OpenFeign boundary with explicit deadlines, bounded retry, circuit breaker and fail-closed `UNAVAILABLE` outcomes. It makes the final future plan decision without cross-database access or a transaction spanning the remote call. Review/publication alone is insufficient.
- Care combines reassessment dimensions without normalizing them into one score. Journal/AI context remains model-derived evidence limited to available consented entries; Care maps it only to policy-allowed review candidates and requires user confirmation.
- MB-386 retains compatible explicit 7-31 day analysis composition. MB-559's canonical path issues adjacent 14-day periods from a versioned Care context, validates that the selected PHQ-9/GAD-7 results remain current, resolves a Journal job authoritatively, requires the selected self-report to match the current bounds, and snapshots truthful `UNAVAILABLE` or `INSUFFICIENT_DATA` states. Local engagement/reflection selects only owner-approved reusable occurrences by `scheduled_at`; a source change or deletion after composition cannot rewrite history.
- Exceptional Identity lookups use a consumer-owned Feign port outside transactions with timeout/breaker and safe failure semantics.

## MB-88 delivered foundation

MB-88 defines the profile, platform-consent, and PHQ-9 contract/persistence foundation. MB-89 implements public questionnaire retrieval plus authenticated and anonymous PHQ-9 submission/read handlers with JWT or hashed session-token authorization, server-owned scoring, item-9 safety status, owner-scoped idempotency, and an atomic minimized outbox fact. MB-178 implements own-profile optimistic concurrency, backend-owned versioned privacy disclosure, append-only `PRIVACY_POLICY` decisions, consent-gated authenticated submission, disclosure-gated anonymous submission, stable owned history pagination, immutable result reopening, and user-initiated reassessment. Story 1102 makes `privacy-capstone-v3` current for new PHQ-9/GAD-7 submissions while preserving v1/v2 historical policy references without backfill. MB-367 activates the already reserved `AI_PROCESSING` database value with immutable `ai-processing-capstone-v1` disclosure, append-only grant/revoke behavior, and a minimal bearer-protected authorization response; it adds no Care migration or frontend UI. MB-205 implements owner-only descriptive progress by selecting the immediately preceding non-voided same-instrument result with an identical scoring version and returning only score/band/elapsed comparison data. The executable Liquibase history contains profile and append-only consent evidence, isolated anonymous sessions, versioned questionnaire reference data, score bands, immutable assessment submission/answer/result shapes, disclosure provenance, and a transactional outbox table.

MB-205 is contained in `com.mentalbridge.care.progress` (controller, read-only service, owner-scoped JDBC repository and arithmetic direction). It reuses `ix_assessment_submission_user_history`; measured integration behavior needs no new migration or data-dictionary field.

The reassessment feature is contained in `com.mentalbridge.care.reassessment`. Controllers expose versioned owner self-report commands plus idempotent composition and owner-only current/detail/history reads; the service coordinates local evidence and a transaction-free Journal/AI query. Liquibase changeset 018 adds immutable snapshots and changeset 019 adds the mutable versioned self-report source plus content-clearing deletion tombstones. Snapshots exclude assessment answers, raw journal text, provider responses, and any combined direction.

The original English PHQ-9 definition, retired immutable
`phq9-vi-vn-capstone-v1`, current corrected `phq9-vi-vn-capstone-v2`, and
published `gad7-vi-vn-adult-v1` are seeded. Existing item-9 runtime remains
unchanged. ADR 0017 approves the additional explicit-help trigger and reviewed
area directory as target behavior without claiming they are implemented.
Specialist plan changes, directory expansion, and production consent/retention
remain separate runtime gates in `docs/policies/`. MB-511 adds owner-scoped Support Guide
generation/history under `/api/v1/support-guides` with immutable provenance,
local safety, stable dependency outcomes, and no plan lifecycle fields.
MB-372 adds owner-scoped create/current-draft routes under
`/api/v1/support-plans`. Care obtains current entitlement from Consultation
with the forwarded bearer, revalidates an owned current-policy
SupportEvaluation v2, resolves exact Content eligibility before the local
transaction, and stores one reproducible draft snapshot. `FREE`, stale facts,
or dependency uncertainty create no plan; AI is not called.

MB-373 adds owner-scoped `PUT /api/v1/support-plans/{id}/choices`,
`POST /api/v1/support-plans/{id}/activate`, and
`GET /api/v1/support-plans/current`. Only candidates persisted in the draft may
be kept/swapped; optional slots may be removed and core slots may not. Every
accepted command rechecks authoritative paid entitlement, current-compatible
SupportEvaluation/template facts, and exact resource versions immediately
before activation. Choice replacement is a naturally idempotent PUT guarded by
`If-Match`; activation keeps explicit request idempotency. Owner locks and
partial unique indexes yield exactly one official current plan under retries
and concurrency. Activation emits one minimized atomic outbox fact.

MB-178 does not implement specialist grants, automatic follow-up, clinical progress interpretation, export/deletion, or production retention. Support/intervention, analytics, Kafka event delivery, and these deferred workflows must not be inferred from the implemented profile/consent/history slice.

## Ordered tasks

- [x] CARE-01 Complete PHQ-9 and GAD-7 controlled-Capstone publication records; specialist sharing, domain-aware SupportPlans, and production gates remain explicitly unavailable until their own checklists pass.
- [ ] CARE-02 Extend the MB-88 profile, platform-consent and PHQ-9 OpenAPI foundation with consent authorization, safety/support, intervention, follow-up and analytics contracts.
- [ ] CARE-03 Define assessment/support/consent/follow-up event schemas and journal-indicator consumer contract.
- [ ] CARE-04 Extend the MB-88 Liquibase/reference-data foundation with approved grants, safety/support, intervention and follow-up persistence.
- [ ] CARE-05 Profile and general privacy decisions are implemented by MB-178; specialist scoped grants and their concurrent revoke/read protection remain deferred.
- [x] CARE-06 Implement anonymous and authenticated assessment, validation, scoring and idempotency for published PHQ-9 and GAD-7 reference data while retaining retired definitions for history.
- [x] CARE-07a Publish deterministic cross-cutting safety, coarse `mb-support-routing-capstone-v1`, synchronous fallback and provenance through Story 1103.
- [x] CARE-07b Preserve v1 history and implement compatible domain-aware SupportEvaluation (#48) plus exact eligible resources (#50).
- [x] CARE-07c Implement the MB-372 deterministic initial paid SupportPlan draft and persisted owner-only reload.
- [x] CARE-07d Implement MB-373 bounded admitted choices with natural PUT behavior, exact revalidation, idempotent explicit activation, one-current-plan enforcement, activation audit, and owner-only current reload; keep later lifecycle unavailable.
- [x] CARE-07e Implement MB-374 owner lifecycle and immutable terminal history, MB-513 deterministic schedules/occurrences, and MB-376 owner engagement without adherence or specialist-monitoring semantics.
- [x] CARE-07f Implement the MB-386 compatibility Reassessment Summary composition with safe Journal/AI fallback and owner current/history queries.
- [x] CARE-07g Implement MB-559 explicit reassessment self-report, deletion semantics, and canonical v2 composition.
- [ ] CARE-08 Add the ADR 0022 explicit reassessment self-report and governed plan-review outcome contract; follow-up, other analytics projections, export and deletion participation remain open.
- [ ] CARE-09 Verify scoring boundaries, item-9 safety, stale/missing input, concurrency, rollback/outbox, duplicate/reordered events and dependency failures.
- [ ] CARE-10 Add safe observability/configuration, update README, and pass module/contract/migration gates.
