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
Set-Location 'D:\DO AN\MentalBridge\mentalbridge-backend'
.\scripts\docker-local.ps1 status
```

To start shared local infrastructure:

```powershell
.\scripts\docker-local.ps1 up
.\scripts\docker-local.ps1 status
```

The stack contains three owner PostgreSQL databases, MongoDB, Redis, and Kafka.
It contains no committed credentials or production data.

## 2. Migrations and service startup

Migrations are reviewed and applied by the owning service. Application startup
does not run migrations.

```powershell
# Identity
Set-Location 'D:\DO AN\MentalBridge\mentalbridge-backend\identity-service'
.\mvnw.cmd liquibase:validate
.\mvnw.cmd liquibase:status

# Care
Set-Location 'D:\DO AN\MentalBridge\mentalbridge-backend\care-service'
.\mvnw.cmd liquibase:validate
.\mvnw.cmd liquibase:status

# Content/Notification
Set-Location 'D:\DO AN\MentalBridge\mentalbridge-backend\content-notification-service'
npm ci
npm run migration:check
```

Use the service-specific README for environment variables and startup. Never
copy real keys into `.env.example`, command arguments, logs, or evidence.

## 3. Required verification commands

### Frontend

```powershell
Set-Location 'D:\DO AN\MentalBridge\mentalbridge-frontend\mentalbridge'
npm ci
npm run quality
npm run test:e2e:install
npm run test:e2e
```

`npm run quality` covers formatting, lint, typecheck, OpenAPI snapshot checks,
109 unit tests, and the production build. The Playwright fixture starts a
synthetic Identity/Care/Content server and does not call production services.

### Identity and Care

```powershell
Set-Location 'D:\DO AN\MentalBridge\mentalbridge-backend\identity-service'
.\mvnw.cmd test

Set-Location 'D:\DO AN\MentalBridge\mentalbridge-backend\care-service'
.\mvnw.cmd test
```

These suites use Testcontainers for PostgreSQL, Kafka, and Redis. The
integration JVM timezone is forced to UTC so results do not depend on the host
timezone.

### Content/Notification

```powershell
Set-Location 'D:\DO AN\MentalBridge\mentalbridge-backend\content-notification-service'
npm ci
npm run lint
npm run typecheck
npm test
npm run test:integration
npm run contract:check
npm run migration:check
npm run build
```

`npm run format:check` is also part of the service README gate. At the time of
this record it reports 30 pre-existing formatting violations outside the
Sprint 2 changes; the other Content gates and the integration suite pass.

## 4. Traceability and status

| Concern | Authoritative artifact | Sprint 2 status |
| --- | --- | --- |
| Target cohort and terminology | [MB-179 blueprint](mb-179-screening-to-support-blueprint.md#target-cohort-and-eligibility), [closure matrix](mb-179-review-1-closure-matrix.md) | Definition complete; mentor/supervisor closure pending |
| PHQ-9 provenance and item-9 safety | [PHQ-9 policy](../policies/phq9-screening-and-safety-policy.md), [ADR 0009](../adr/0009-care-screening-safety-and-support-boundaries.md) | Published for controlled Capstone use; runtime complete |
| GAD-7 | [GAD-7 policy](../policies/gad7-screening-policy.md) | Research verified/implementation authorized; unpublished and unavailable |
| Profile, consent, history, reassessment | [Care specification](../modules/care-service.md), Care OpenAPI | Runtime complete and integration-tested |
| Anonymous/authenticated journeys and fallback | [integrated evidence](../sprint-2-integrated-release-evidence.md), frontend Care E2E specs | Runtime complete and Playwright-tested |
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
| Frontend quality | Pass: 26 test files, 109 unit tests, production build |
| Frontend Playwright | Pass: 37 Chromium tests |
| Identity Maven suite | Pass: 28 tests |
| Care Maven suite | Pass: 35 tests |
| Content unit suite | Pass: 26 tests |
| Content integration suite | Pass: 24 tests |
| Docker local infrastructure | Pass: Compose configuration valid; PostgreSQL, MongoDB, and Redis healthy; Kafka running |

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
- [ ] Mentor/supervisor records Review 1 closure approval in Jira.
- [ ] Jira subtasks are updated with this runbook and verification evidence.
- [ ] Integrating branch owner adds the final pull-request URL(s) and reviewer
  approval record.

The final three items are administrative release actions. No Jira key, PR URL,
or reviewer approval is fabricated by this document.
