# MB-517 / MB-550 appointment reminder verification

Evidence class: local source review, synthetic unit/contract tests, real PostgreSQL Testcontainers integration tests, and fixture-browser tests. The live cross-service third-party email-provider send was stubbed with synthetic recipients; no real recipient or email was used.

## Covered behavior

| Boundary | Evidence | Result |
| --- | --- | --- |
| Confirmed appointment/version, duplicate/reordered event, cancel/reschedule replacement | Content repository SQL plus `database.integration.test.ts` cases; live Consultation truth check in owner service | Passed; real PostgreSQL execution with Testcontainers verified in `database.integration.test.ts` |
| One minimized delivery, independent of digest cadence | `appointment-reminder.service.test.ts`, `appointment-email.delivery.test.ts` | Passed with synthetic data |
| Rejection/expiry/cancellation, stale version, late submission | Eligibility and before-start owner tests; submission-time clock recheck | Passed in owner unit and integration tests; clock and state rechecks fail closed |
| Timezone and quiet hours | Owner unit tests including defer, suppress and quiet-window entry during dependency calls | Passed |
| Provider rejection/unknown outcome | Owner and adapter tests for HTTP 400/429/500 and lost response | Passed with stubbed provider; no live Brevo call |
| Consumer preference and in-app entry | Frontend Playwright fixture journey on production build | 2 passed; synthetic fixture-browser tests |

The consumer propagates temporary persistence failures instead of acknowledging them as malformed events. The checkpoint upsert updates only for a newer appointment version, serializing simultaneous first events. The owner checks the authoritative appointment after address resolution and checks the clock and quiet hours again immediately before provider submission. These changes narrow cancellation/late-execution races; they fail closed rather than sending outdated reminders.

The versioned appointment event and provider payload contain only IDs, status/version, time, modality and safe route data. The email template contains local appointment date/time, timezone, modality and `/appointments/{appointmentId}`. No raw health, Journal, assessment, ConsultationBrief or chat content is sent or logged by this flow. The reminder is separate from MB-514 digest cadence and preview.

## Exact local checks

From `content-notification-service/` in the MB-517 backend worktree:

```text
npx.cmd vitest run --config vitest.integration.config.ts src/__tests__/integration/database.integration.test.ts -t "appointment email reminder tables"
PASS: 4 passed / 30 skipped (8.97s); verified newest checkpoint under simultaneous/duplicate/reordered events, replacement intent and single claim across workers, recipient/appointment/version deduplication, and sixty-minute target constraint.

npx.cmd vitest run --config vitest.integration.config.ts src/__tests__/integration/database.integration.test.ts
PASS: 1 file / 34 passed (7.61s).

npm.cmd run quality
PASS: format:check, lint, typecheck, unit suite (25 files / 150 tests), contract:check, migration:check, test:integration (4 files / 59 tests including real PostgreSQL Testcontainers), and build all passed cleanly.
```

From `consultation-service/` in the MB-517 backend worktree:

```text
mvn.cmd test -Dtest=AppointmentDecisionIntegrationTests,ConsultationOpenApiContractTests,ConsultationLiquibaseChangelogTests -Duser.timezone=UTC
PASS: 3 test classes / 22 tests (01:01 min).
```

From `identity-service/` in the MB-517 backend worktree:

```text
mvn.cmd test -Dtest=DeliveryAddressControllerTests,IdentityOpenApiContractTests -Duser.timezone=UTC
PASS: 2 test classes / 4 tests (24.13s).
```

From repository root in the MB-517 backend worktree:

```text
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-repository.ps1 -BaseSha origin/dev
PASS: 932 tracked files and 68 changed files passed repository and paired-change policy.
```

From `mentalbridge/` in the MB-517 frontend worktree:

```text
npx.cmd playwright test tests/e2e/appointment-reminder.spec.ts --reporter=dot --workers=1 --timeout=15000 --global-timeout=45000
PASS: 2 fixture-browser tests with approved Chromium execution (13.1s).

npm.cmd run format:check; npm.cmd run lint; npm.cmd run typecheck
PASS: all three commands.

npm.cmd test
PASS: 130 files / 628 tests.
```

## Completion boundary

MB-550 verification criteria are verified locally across real owners, PostgreSQL Testcontainers persistence, and frontend consumers. Duplicate events, reordered delivery, reschedule races, cancellation invalidation, expiration at start time, timezone calculation, quiet-hour deferral/suppression, and provider failure classification (HTTP 400/429/500/unknown outcome) are fully exercised and pass. Payloads in events, database records, and email adapters were inspected and confirmed free of raw health, brief, Journal, assessment, or chat content. Live third-party Brevo delivery was simulated using synthetic recipients without invoking real external mailboxes.
