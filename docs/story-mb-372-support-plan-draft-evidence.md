# MB-372 SupportPlan initial draft evidence

Date: 2026-09-19

Scope: MB-372 and subtasks MB-441, MB-442, MB-443, and MB-444.

## Delivered behavior

- Care is the sole owner of the initial SupportPlan `DRAFT` and its persisted
  rationale, safety copy, entitlement decision, policy/template versions,
  exact resource/publication versions, reviewed display copy, alternatives,
  and timestamps.
- `POST /api/v1/support-plans` accepts only one owned SupportEvaluation v2
  reference. It obtains current `PLUS`/`PREMIUM` entitlement from Consultation
  with the forwarded end-user bearer, revalidates current Care evidence, and
  resolves exact Content eligibility before opening the local write
  transaction.
- `GET /api/v1/support-plans/current-draft` reloads the persisted snapshot and
  does not call Consultation, Content, or AI.
- `FREE`, stale/incompatible evaluation evidence, stale/withdrawn/missing core
  resources, or dependency uncertainty fail closed and create no draft.
- Idempotency replay returns the original snapshot. A Care-owned profile lock
  and a partial unique index converge concurrent requests on one current
  `DRAFT`.
- The web BFF derives the exact SupportEvaluation v2 from server-held HttpOnly
  assessment references. The browser cannot submit a package, evaluation ID,
  resource list, policy decision, or safety decision.
- The proposal page shows rationale, safety-first copy, selected exact
  resources, admitted alternatives, source versions, Free/stale/dependency
  states, and the explicit “not activated” user-confirmation boundary.
- AI is not called and cannot select, rank, mutate, or override any plan fact.

Choice mutation, activation, discard, pause/resume, completion, replacement,
activity tracking, reminders, and `PlanChangeRequest` remain outside MB-372.
The remaining lifecycle proposal stays `planned`; initial creation/reload is
canonical in `contracts/openapi/care-service-v1.yaml`.

## Subtask evidence

| Subtask | Evidence |
| --- | --- |
| MB-441 decision and flow | ADR 0013 implementation note, SupportPlan policy v2 initial-draft rules, domain/use-case and traceability updates; Free remains on the separate Support Guide path. |
| MB-442 owner capability | Care controller/service/policy/writer, Consultation entitlement adapter, additive Liquibase change `care-013-support-plan-draft`, OpenAPI, entities/repositories, and PostgreSQL data dictionary. |
| MB-443 consumer flow | Next.js BFF, generated Care and SupportEvaluation v2 types, strict response validation, `/support-plan` page, accessible stable states, component/route tests, and Playwright fixture. |
| MB-444 verification | Deterministic policy, entitlement failure, contract/privacy, frontend unit/build, browser fixture, and repository paired-change checks passed. PostgreSQL integration assertions are present but environment-blocked as described below. |

## Verification results

Backend from `care-service`:

```text
.\mvnw.cmd -q -DskipTests test-compile
PASS

.\mvnw.cmd -q '-Dtest=AnonymousAssessmentSessionEntityTests,AssessmentScoringPolicyTests,AiProcessingConsentServiceTests,AiProcessingDisclosureServiceTests,PrivacyDisclosureServiceTests,CareEventContractTests,CareOpenApiContractTests,SupportEvaluationV2ContractTests,SupportGuideContractTests,SupportPlanProposalContractTests,EntitlementClientTests,CareLiquibaseChangelogTests,ProfileServiceTests,ScoreDirectionTests,ResourceEligibilityClientTests,ResourceEligibilityHttpConsumerContractTests,DomainAwareSupportPolicyTests,SupportEvaluationServiceTests,SupportRoutingPolicyTests,SupportGuideServiceTests,SupportPlanPolicyTests' test
PASS: 91 tests, 0 failures, 0 errors, 0 skipped

The policy suite covers deterministic one-domain and two-domain input. The
integration suite also asserts same-key replay and rejects reuse of that key
with different SupportEvaluation evidence.

.\mvnw.cmd -q '-Dtest=SupportPlanProposalContractTests,CareOpenApiContractTests,SupportPlanPolicyTests,EntitlementClientTests,CareLiquibaseChangelogTests' test
PASS after removal of the conflicting planned-create contract
```

Repository root:

```text
powershell -ExecutionPolicy Bypass -File .\scripts\verify-repository.ps1 -BaseSha origin/dev
PASS: repository policy and paired-change policy
```

Frontend from `mentalbridge` with the three Care contract source variables
pointing at the MB-372 backend worktree:

```text
npm run quality
PASS:
- format, lint, typecheck
- all contract checks
- 59 test files / 300 unit tests
- production Next.js build including /api/care/support-plans and /support-plan

$env:CARE_E2E_MODE='fixture'; npx playwright test tests/e2e/support-plan.spec.ts
PASS: 2 browser fixture cases (persisted reload/safety/alternatives/provenance and stable Free path)
```

## Environment-blocked verification

```text
.\mvnw.cmd -q '-Dtest=SupportPlanIntegrationTests' test
```

The six PostgreSQL integration scenarios were discovered, but Spring failed
while creating the Testcontainers PostgreSQL bean with:

```text
Could not find a valid Docker environment
```

This happened before any MB-372 assertion. It is an environment blocker, not
an assertion failure. The blocked suite contains paid create/replay/reload,
Free no-write, stale SupportEvaluation no-write, stale/unavailable eligibility
no-write, ownership, and two-request concurrency/one-draft assertions. A live
cross-stack browser claim is intentionally not made while Docker is
unavailable.

## Privacy and safety review

- Contracts, tables, UI, logs, and tests contain no raw assessment answers or
  journal content.
- Bearer tokens are forwarded only in request memory and are not persisted or
  logged.
- The stored safety snapshot comes from deterministic PHQ-9 item-9 Care
  evidence and is presented before ordinary plan content.
- The response carries general-wellbeing and non-treatment wording; it has no
  diagnosis, treatment, clinical recovery, or safety-resolution claim.
- All fixtures are synthetic. No live user or provider data was used.
