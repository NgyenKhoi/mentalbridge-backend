# Sprint 2 integrated journey and release evidence

## Scope

This evidence covers the controlled Sprint 2 vertical slice:

- anonymous PHQ-9 submission, result reopening, expiry handling, and reviewed-resource fallback;
- authenticated session, Care profile, privacy consent, PHQ-9 submission, history, reassessment, and reviewed resources;
- cross-user authorization and explicit Identity, Care, and Content/Notification degradation states;
- server-owned scoring, consent, privacy, and resource publication boundaries.

The final closure result is recorded in [Sprint 2 status](sprints/sprint-2-status.md).

The browser journey uses the deterministic fixture at
`mentalbridge-frontend/mentalbridge/scripts/identity-e2e-server.mjs`. It uses
synthetic identities, profiles, consent decisions, answers, and reviewed
resources only. It does not connect to production services or contain
production personal or clinical data.

## Reproducible commands

Run from the repository root in PowerShell. Docker Desktop must be running for
the Spring Testcontainers suites:

```powershell
Set-Location ./mentalbridge-backend
.\scripts\docker-local.ps1 status
```

The command above validates that the Docker daemon is reachable. Do not start
the compose stack solely for Maven tests: Testcontainers creates isolated
PostgreSQL/Kafka/Redis containers for each Spring test context. Use
`.\scripts\docker-local.ps1 up` when manually running services against shared
local infrastructure.

```powershell
# Frontend quality and integrated browser journeys
Set-Location ../mentalbridge-frontend/mentalbridge
npm ci
npm run quality
npm run test:e2e

# Identity service
Set-Location ../../mentalbridge-backend/identity-service
.\mvnw.cmd test

# Care service
Set-Location ../care-service
.\mvnw.cmd test

# Content/Notification service
Set-Location ../content-notification-service
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

`npm run test:e2e` is fixture-browser verification only. It starts synthetic
Identity/Care/Content fixtures and a production-mode Next.js server; it does
not prove a live cross-stack journey. Failed browser tests retain only bounded
correlation IDs in `correlation-evidence.json`; tokens, cookies, request
bodies, and response content are not attached.

Live cross-stack evidence was rerun locally on 2026-09-12 against controlled
real Identity, Care, and Content services: 6 passed in 1.4 minutes, with no
skips. It covers anonymous/authenticated journeys, owner-boundary denial,
Content/Identity/Care outage states, and a revoked-refresh expired-session
path. The evidence PR is [#35](https://github.com/NgyenKhoi/mentalbridge-backend/pull/35); CI/reviewer approval remain administrative follow-ups.

## Verification record

| Boundary | Command/result | Evidence |
| --- | --- | --- |
| Frontend quality | Previously observed pass: 29 files, 159 tests, production build. Rerun required on the synchronized branch. | `mentalbridge-frontend/mentalbridge/README.md` |
| Frontend fixture journeys | Previously observed: 6 passed, 30 skipped. This is not live cross-stack evidence or a release pass. | `mentalbridge-frontend/mentalbridge/tests/e2e/` |
| Frontend live cross-stack journeys | 2026-09-12 local controlled Docker rerun: 6 passed in 1.4m, 0 skipped. | `E2E_CONTROL_DOCKER=true npm.cmd run test:e2e:live -- tests/e2e/live-cross-stack.spec.ts --workers=1` |
| Content unit tests | 2026-09-12 local rerun: 3 files, 24 tests passed. | `npm.cmd test` in `content-notification-service` |
| Content static/contract gates | 2026-09-12 local rerun: format check, lint, typecheck, contract check, migration check, build, and integration passed. The integration suite passed 2 files and 25 tests in 13.72 seconds on a Docker-enabled host. | `npm.cmd run format:check`, `lint`, `typecheck`, `test`, `test:integration`, `contract:check`, `migration:check`, and `build` |
| Identity service | Previously observed pass: 28 tests. Clean synchronized rerun and evidence link required. | `identity-service/target/surefire-reports/` |
| Care service | Previously observed pass: 35 tests. Clean synchronized rerun and evidence link required. | `care-service/target/surefire-reports/` |
| Docker local infrastructure | Compose syntax has been validated; service startup/readiness evidence is pending. | `docker-compose.local.yml` |

## Acceptance traceability

| Acceptance criterion | Current evidence state |
| --- | --- |
| Anonymous PHQ-9, reopen, expiry, resource fallback | Controlled live suite passed. |
| Authenticated profile, consent, PHQ-9, history, reassessment, resources | Controlled live suite passed. |
| Cross-user access fails closed | Controlled live suite passed with Care `403/404` owner-boundary denial. |
| Explicit dependency degradation | Controlled live suite passed with Content, Identity, and Care outages plus revoked refresh. |
| No browser-owned scoring or obsolete hotline/mock result data | Static/component evidence exists; re-verify against synchronized frontend revision. |
| Review 1 definitions and approval states traceable | Source links exist; final Jira/PR/reviewer closure remains pending. |

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
