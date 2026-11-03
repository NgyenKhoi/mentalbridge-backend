# care-service agent map

Applies inside `care-service/` in addition to the repository `AGENTS.md`. This file maps code and names the invariants that are easy to break; the cited policy or README section is the authority. `README.md` here is a story-by-story change log; search it by story key instead of reading it whole.

## Flow and code

Package root: `src/main/java/com/mentalbridge/care/`. Packages are flat: controller, service, entity and repository sit together.

| Step | Package | Entry point | Main tables |
| --- | --- | --- | --- |
| Consent | `consent` | `ConsentController` `/api/v1/consents`, `/api/v1/consent-decisions` | `consent_decision` |
| Questionnaire, assessment | `assessment` | `QuestionnaireController`, `AssessmentController` `/api/v1/assessments`, `/api/v1/anonymous-assessment-sessions` | `assessment_submission`, `assessment_answer`, `assessment_result` |
| PHQ-9/GAD-7 pairing | `screeningepisode` | `ScreeningEpisodeController` `/api/v1/screening-episodes` | `screening_episode` |
| Progress | `progress` | `AssessmentProgressController` `/api/v1/assessments/{id}/progress` | read only |
| SupportEvaluation v1 / v2 | `support` | `SupportEvaluationController` `/api/v1/support-evaluations`, `SupportEvaluationV2Controller` `/api/v2/support-evaluations` | `support_evaluation*`, `support_evaluation_v2*` |
| Combined meaning (MB-667) | `support` | `CombinedScreeningMeaningController` `GET /api/v1/support-evaluations/{id}/combined-meaning` | read only |
| Support Guide | `supportguide` | `SupportGuideController` `/api/v1/support-guides` | `support_guide*` |
| SupportPlan v2 (resource plans) | `supportplan` | `SupportPlanController` `/api/v1/support-plans` | `support_plan`, `support_plan_slot*`, `support_plan_command*` |
| SupportPlan v3 (native actions) | `supportplan` | `NativeSupportPlanController`, `SupportPlanV3Controller` under `/api/v2` | `support_plan_focus`, `support_plan_action`, `support_plan_schedule_command` |
| Occurrences, engagement | `supportplan` | `SupportPlanActivityOccurrenceController`, `NativeActionOccurrenceController` | `support_plan_activity_schedule`, `support_plan_activity_occurrence` |
| Plan change request | `supportplan` | `PlanChangeRequestController` | `plan_change_request` |
| Reassessment | `reassessment` | `ReassessmentSummaryController`, `ReassessmentSelfReportController` | `reassessment_summary`, `reassessment_self_report` |

Other packages: `profile`, `consultationbrief`, `safetydirectory`, `analytics`, `entitlement` and `resourceeligibility` (outbound clients), `shared` (`ApiExceptionHandler`, RFC 9457 problems).

Plan lifecycle: draft → choices → activate (`If-Match` + `Idempotency-Key`) → `PUT .../status`. `replacement-review` is read only; `replace` is the only path that supersedes a plan. The v1 plan API rejects native plans with `SUPPORT_PLAN_MODEL_VERSION_UNSUPPORTED`.

## Invariants

- **Pairing.** A plan proposal needs an episode-backed v2 evaluation through `ScreeningEpisodeService.requiredEvaluationContext` (`SCREENING_EPISODE_REQUIRED`). Never infer a pair from "latest" history; one PHQ-9 and one GAD-7, owned, complete, non-voided. Source: `docs/policies/support-evaluation-policy-v2.md`, README MB-292.
- **Independent domains.** No combined score, global severity, recovery or adherence score. Reassessment keeps four separate dimensions with no verdict. Source: `support-evaluation-policy-v2.md`, `docs/CURRENT_PRODUCT_BLUEPRINT.md`.
- **Safety.** PHQ-9 item 9 (`answer >= 1`) is separate evidence, independent of band; a High/Severe band alone is not a safety trigger; GAD-7 safety is `NOT_APPLICABLE`. Scoring, evaluation and safety fallback must never depend on AI, Kafka, Redis, Content, Realtime, notifications, Eureka, package or credits. Source: `phq9-screening-and-safety-policy.md`, ADR 0009.
- **Immutability.** Results, evaluations, terminal plans and reassessment snapshots are never updated in place. Voiding uses `voided_at`/`void_reason_code`; history excludes voided rows. Corrections are forward-only: new policy version plus a new changeset. Source: `docs/policies/README.md` approval rule.
- **Provenance.** Keep questionnaire, scoring, safety-policy, policy/content and entitlement versions on what they produced. MB-667 draft content version is distinct from the source policy version and is not deployment-approved (`combined-screening-meaning-draft-v1.md`).
- **Entitlement.** Checked against consultation `GET /internal/v1/entitlements/current` via `entitlement/ConsultationEntitlementHttpClient`; any failure is 503 `ENTITLEMENT_UNAVAILABLE` (fail closed). FREE gets 403 `SUPPORT_PLAN_ENTITLEMENT_REQUIRED` from `SupportPlanService` / `NativeSupportPlanService` on propose, choices/activate and replace. Status changes, occurrence tracking and reads are not gated. FREE never loses screening, safety or the Support Guide. Expiry behaviour on an existing plan is undecided; do not invent it. Source: `care-support-and-intervention-policy.md`, `support-plan-policy-v2.md`.
- **Consent.** `PRIVACY_POLICY` gates assessment submission; `AI_PROCESSING` gates ConsultationBrief AI drafts. Streams are independent, append-only and versioned; withdrawal never deletes history. Source: `care-consent-and-retention-policy.md`.
- **Ownership.** Scope every query by the JWT subject; never take the account ID from the request. Missing and foreign IDs return the same `*_NOT_FOUND`.
- **Data minimization.** No answer text, journal, emotion or consultation content in logs, errors, outbox or plan storage.
- **AI never decides.** AI understands and supports, Care decides, the user confirms; AI is absent from plan composition.
- **Concurrency.** Commands take `Idempotency-Key`, mutations a strong `If-Match`. Never hold a transaction across a remote call: resolve entitlement/eligibility/evidence first, then lock and recheck.

## Persistence

- Liquibase master: `src/main/resources/db/changelog/db.changelog-master.yaml`; files `changes/NNN-kebab.sql`, append-only after merge. Database `mentalbridge_care`, schema `public`, no cross-service FK. Hibernate validates only.
- Trap: from file `010` on, the changeset id is the file number plus one (`029-*.sql` declares `care-030-*`). Check the last id before adding a file.
- With any persisted-shape change, update `docs/domain-model/canonical-entities.md`, `docs/domain-model/relational/postgresql-logical-schema.sql`, `docs/database/postgresql-field-data-dictionary.md`, `docs/database/postgresql.md`, and contracts when the API changes.

## Integration and tests

- Outbound (OpenFeign + Resilience4j, fail closed): consultation (entitlement, appointment brief context, client relationships, resource proposals), content-notification (resource eligibility, safety directory with local fallback), journal-ai (longitudinal analyses, guide phrasing with fallback, brief drafts, plan companion, completion evidence). Redis only for companion admission and rate limits.
- Events are written to `outbox_event`. Only user-notification facts are relayed to Kafka (`notification/UserNotificationOutboxRelay`); other events have no relay yet. Schemas: `contracts/events/care/`. Care consumes no events.
- Tests mirror packages under `src/test/java/com/mentalbridge/care/`; `contract/` validates OpenAPI and event schemas; `persistence/` holds Liquibase upgrade tests. Testcontainers PostgreSQL needs Docker. Run `.\mvnw.cmd test` (CI: `./mvnw -B test` with `SPRINGDOTENV_ENABLED=false`).
