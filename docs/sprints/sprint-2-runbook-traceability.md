# Sprint 2 runbook, traceability, and release evidence

This is the executable handoff for the Sprint 2 integrated slice. It is
deliberately split between behavior that is runtime-complete and Review 1
behavior that is definition-complete but not implemented. All validation uses
synthetic or test data.

## 1. Prerequisites

- Windows PowerShell
- Docker Desktop running with the Linux engine
- Node.js 22 or newer for Content/Notification and Node.js 20.9 or newer for
  the frontend
- Java 21 or newer for the Spring services
- A clean, secrets-free checkout

The Compose stack is useful for manually running local services. Maven
integration tests use isolated Testcontainers and do not depend on the Compose
containers being started.

```powershell
Set-Location ./mentalbridge-backend
.\scripts\docker-local.ps1 status
```

To start shared local infrastructure:

```powershell
.\scripts\docker-local.ps1 up
.\scripts\docker-local.ps1 status
```

The stack contains three owner PostgreSQL databases, MongoDB, Redis, and Kafka.
It contains no production credentials or production data. `docker-compose.local.yml`
intentionally contains known local-only passwords; they are non-secret and must
never be reused outside this disposable local stack.

## 2. Migrations and service startup

Migrations are reviewed and applied by the owning service. Application startup
does not run migrations.

```powershell
# Identity
Set-Location ./identity-service
.\mvnw.cmd liquibase:validate
.\mvnw.cmd liquibase:status

# Care
Set-Location ./care-service
.\mvnw.cmd liquibase:validate
.\mvnw.cmd liquibase:status

# Content/Notification
Set-Location ./content-notification-service
npm ci
npm run migration:check
```

Use the service-specific README for environment variables and startup. Never
copy real keys into `.env.example`, command arguments, logs, or evidence.

## 3. Required verification commands

### Frontend

```powershell
Set-Location ../../mentalbridge-frontend/mentalbridge
npm ci
npm run quality
npm run test:e2e:install
npm run test:e2e
```

`npm run quality` covers formatting, lint, typecheck, OpenAPI snapshot checks,
unit tests, and the production build. Record counts from the clean run rather
than copying them into this runbook. The Playwright fixture starts a
synthetic Identity/Care/Content server and does not call production services.
It is browser-fixture evidence only, not live cross-stack E2E evidence.

### Identity and Care

```powershell
Set-Location ../../mentalbridge-backend/identity-service
.\mvnw.cmd test

Set-Location ../care-service
.\mvnw.cmd test
```

These suites use Testcontainers for PostgreSQL, Kafka, and Redis. The
integration JVM timezone is forced to UTC so results do not depend on the host
timezone.

### Content/Notification

```powershell
Set-Location ../content-notification-service
npm ci
npm run lint
npm run typecheck
npm test
npm run test:integration
npm run contract:check
npm run migration:check
npm run build
```

`npm run format:check` is a required service gate. On 2026-09-12,
`npm.cmd run format:check`, lint, typecheck, unit tests (24/24), contract
check, migration check, build, and the Docker-backed Testcontainers integration
suite passed. The integration run passed 2 files and 25 tests in 13.72 seconds.

## 4. Traceability and status

| Concern | Authoritative artifact | Sprint 2 status |
| --- | --- | --- |
| Target cohort and terminology | [MB-179 blueprint](mb-179-screening-to-support-blueprint.md#target-cohort-and-eligibility), [closure matrix](mb-179-review-1-closure-matrix.md) | Definition complete; mentor/supervisor closure pending |
| PHQ-9 provenance and item-9 safety | [PHQ-9 policy](../policies/phq9-screening-and-safety-policy.md), [ADR 0009](../adr/0009-care-screening-safety-and-support-boundaries.md) | Published for controlled Capstone use; runtime complete |
| GAD-7 | [GAD-7 policy](../policies/gad7-screening-policy.md) | Research verified/implementation authorized; unpublished and unavailable |
| Profile, consent, history, reassessment | [Care specification](../modules/care-service.md), Care OpenAPI | Runtime complete and integration-tested |
| Anonymous/authenticated journeys and fallback | [integrated evidence](../sprint-2-integrated-release-evidence.md), frontend Care E2E specs | Fixture-browser coverage exists; live cross-stack evidence is pending |
| Reviewed resources | [Content specification](../modules/content-notification-service.md), Content README | Reviewed published content only; explicit empty/unavailable fallback |
| Specialist, consultation, scoped sharing | [MB-179 blueprint](mb-179-screening-to-support-blueprint.md#specialist-recommendation-and-handoff), [consent policy](../policies/care-consent-and-retention-policy.md) | Definition complete; runtime unavailable |
| Follow-up and progress | [MB-179 blueprint](mb-179-screening-to-support-blueprint.md#follow-up-and-reassessment) | User-initiated reassessment is runtime complete; automatic follow-up/progress API remains deferred |
| Journal frontend and AI runtime | [Sprint 2 status](sprint-2-status.md#explicit-runtime-deferrals) | Deferred; not represented as delivered |

The complete Review 1 decision-by-decision record is the
[closure matrix](mb-179-review-1-closure-matrix.md). The project-level source
mapping remains [requirements traceability](../requirements-traceability.md).

## 5. Verification record

| Gate | Result |
| --- | --- |
| Frontend quality | Previously observed pass: 29 files, 159 tests, build. Rerun required after base synchronization. |
| Frontend fixture Playwright | Previously observed: 6 passed, 30 skipped. Not release evidence. |
| Frontend live cross-stack E2E | 2026-09-12 controlled Docker rerun: 6 passed in 1.4m, 0 skipped. Command: `E2E_CONTROL_DOCKER=true npm.cmd run test:e2e:live -- tests/e2e/live-cross-stack.spec.ts --workers=1`. PR/CI link remains required. |
| Identity Maven suite | Rerun and attach exact result after base synchronization. |
| Care Maven suite | Rerun and attach exact result after base synchronization. |
| Content unit/integration/static gates | 2026-09-12: format, lint, typecheck, 24 unit tests, contract, migration, build, and Docker-backed integration tests (25/25) passed. |
| Docker local infrastructure | Compose configuration may be validated separately; startup evidence is pending. |

Failed browser-test evidence is bounded to correlation IDs. It excludes
cookies, bearer tokens, request bodies, and response content. Test artifacts
must not contain production personal or clinical data.

## 6. Closure checklist

- [x] Startup, migration, test, and E2E commands are documented above.
- [x] Requirements, policies, service boundaries, and Review 1 decisions link to
  authoritative artifacts.
- [x] Runtime-complete, definition-complete, unpublished, production-blocked,
  deferred, and unavailable states are explicit.
- [x] Deterministic synthetic fixtures and privacy-safe failure evidence are
  documented.
- [ ] All required service and frontend quality gates pass from a clean synchronized checkout.
- [x] Live cross-stack E2E proves the mandatory anonymous, authenticated, authorization and degradation journeys.
- [ ] Mentor/supervisor records Review 1 closure approval in Jira.
- [ ] Jira subtasks are updated with this runbook and verification evidence.
- [ ] Integrating branch owner adds the final pull-request URL(s) and reviewer
  approval record.

The final three items are administrative release actions. No Jira key, PR URL,
or reviewer approval is fabricated by this document.
