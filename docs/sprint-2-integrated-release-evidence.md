# Sprint 2 integrated journey and release evidence

## Scope

This evidence covers the controlled Sprint 2 vertical slice:

- anonymous PHQ-9 submission, result reopening, expiry handling, and reviewed-resource fallback;
- authenticated session, Care profile, privacy consent, PHQ-9 submission, history, reassessment, and reviewed resources;
- cross-user authorization and explicit Identity, Care, and Content/Notification degradation states;
- server-owned scoring, consent, privacy, and resource publication boundaries.

The executable startup, migration, verification, and closure checklist is
[Sprint 2 runbook, traceability, and release evidence](sprints/sprint-2-runbook-traceability.md).

The browser journey uses the deterministic fixture at
`mentalbridge-frontend/mentalbridge/scripts/identity-e2e-server.mjs`. It uses
synthetic identities, profiles, consent decisions, answers, and reviewed
resources only. It does not connect to production services or contain
production personal or clinical data.

## Reproducible commands

Run from the indicated directory in PowerShell. Docker Desktop must be running
for the Spring Testcontainers suites:

```powershell
Set-Location 'D:\DO AN\MentalBridge\mentalbridge-backend'
.\scripts\docker-local.ps1 status
```

The command above validates that the Docker daemon is reachable. Do not start
the compose stack solely for Maven tests: Testcontainers creates isolated
PostgreSQL/Kafka/Redis containers for each Spring test context. Use
`.\scripts\docker-local.ps1 up` when manually running services against shared
local infrastructure.

```powershell
# Frontend quality and integrated browser journeys
Set-Location 'D:\DO AN\MentalBridge\mentalbridge-frontend\mentalbridge'
npm ci
npm run quality
npm run test:e2e

# Identity service
Set-Location 'D:\DO AN\MentalBridge\mentalbridge-backend\identity-service'
.\mvnw.cmd test

# Care service
Set-Location 'D:\DO AN\MentalBridge\mentalbridge-backend\care-service'
.\mvnw.cmd test

# Content/Notification service
Set-Location 'D:\DO AN\MentalBridge\mentalbridge-backend\content-notification-service'
npm ci
npm run format:check
npm run lint
npm run typecheck
npm test
npm run test:integration
npm run contract:check
npm run migration:check
npm run build
```

The frontend E2E command starts the local fixture and production-mode Next.js
server automatically. Failed browser tests retain only bounded correlation IDs
in `correlation-evidence.json`; tokens, cookies, request bodies, and response
content are not attached.

## Verification record

| Boundary | Command/result | Evidence |
| --- | --- | --- |
| Frontend quality | `npm run quality` — passed; 26 files and 109 unit tests, production build passed | `mentalbridge-frontend/mentalbridge/README.md` |
| Frontend journeys | `npm run test:e2e` — passed; 37 Chromium tests | `mentalbridge-frontend/mentalbridge/tests/e2e/` |
| Content unit tests | `npm test` — passed; 3 files and 26 tests | `content-notification-service/src/__tests__/` |
| Content static/contract gates | lint, typecheck, contract and migration checks, build — passed; `format:check` remains red on 30 pre-existing files | `content-notification-service/README.md` |
| Identity service | `.\mvnw.cmd test` — passed; 28 tests, 0 failures/errors | `identity-service/target/surefire-reports/` |
| Care service | `.\mvnw.cmd test` — passed; 35 tests, 0 failures/errors | `care-service/target/surefire-reports/` |
| Docker local infrastructure | six containers running; five report healthy and Kafka running | `docker-compose.local.yml` |

## Acceptance traceability

| Acceptance criterion | Trace |
| --- | --- |
| Anonymous PHQ-9, reopen, expiry, resource fallback | `care-assessment.spec.ts` — Anonymous PHQ-9 journey |
| Authenticated profile, consent, PHQ-9, history, reassessment, resources | `care-assessment.spec.ts` — Authenticated Care journey |
| Cross-user access fails closed | `security-degradation.spec.ts` — AC1 |
| Explicit dependency degradation | `care-assessment.spec.ts` and `security-degradation.spec.ts` — AC2 |
| No browser-owned scoring or obsolete hotline/mock result data | Care component tests, contract tests, and AC3 security/degradation journeys |
| Review 1 definitions and approval states traceable | `care-service/README.md`, `docs/policies/`, and the Care OpenAPI contract |

## Deferred scope

GAD-7, consultation, specialist workflows, follow-up/progress, journal
frontend, AI runtime, production clinical approval, production retention, and
production support/emergency guidance remain deferred. They must not be
represented as completed by this evidence.

## Release review

Required before release:

1. retain the successful Identity and Care Maven results from a Docker-enabled host;
2. attach the successful CI/local run links to the pull request;
3. record reviewer approval for safety, privacy, consent, authorization, and
   fallback boundaries;
4. confirm no Critical or High issue remains open.

Pull-request links and reviewer approvals: to be added by the integrating branch
owner after Jira review. No link or approval is inferred from local test output.
