# MB-567 emotion history and progress evidence

## Delivered boundary

Journal/AI now exposes an owner-scoped, note-free progress read model over the
existing daily emotion check-in aggregate. It derives the caller's current
local date from the server clock and a validated IANA timezone, counts one
active aggregate per local date, and returns current/longest streaks plus
factual 7-, 14-, and 30-day coverage and emotion-label counts.

No progress counter or trend score is persisted. Same-day revisions still count
once; missing and deleted dates break continuity. Journal activity remains
separate. The contract labels the result
`FACTUAL_COUNTS_NOT_DIAGNOSIS_OR_RECOVERY` and contains no raw note, clinical,
adherence, improvement, or recovery claim. ADR 0025 records these semantics.

## Automated verification

The following checks were run on 2026-09-27 with synthetic data:

| Evidence | Command | Result |
| --- | --- | --- |
| Formatting | `cd journal-ai-service && npm run format:check` | Passed |
| Lint | `cd journal-ai-service && npm run lint` | Passed, zero warnings |
| Type safety | `cd journal-ai-service && npm run typecheck` | Passed |
| OpenAPI | `cd journal-ai-service && npm run contract:check` | Passed; v1.5.0 validated |
| Mongo migrations | `cd journal-ai-service && npm run migration:check` | Passed; no new persistence migration |
| Unit and HTTP tests | `cd journal-ai-service && npm test` | Passed: 91 tests, 0 failed |
| Mongo integration | direct `node --test` over all six compiled Mongo integration suites with the local external URI disabled | Passed: 6 tests, 0 failed |

The focused progress matrix covers consecutive and broken runs, preservation of
the longest run, an open current day after yesterday's check-in, same-day
update, deletion, IANA timezone rollover, sparse 7/14/30 windows, and no-data
output. The real Mongo HTTP test verifies the
authenticated endpoint and current-revision distribution after a concurrent
update.

## Cross-stack evidence

The frontend consumes the generated v1.5.0 contract through bounded
same-origin history and progress handlers. Its 513 unit/BFF/component tests,
production build, and controlled Playwright dashboard journey passed. The
fixture browser screenshot is
`mentalbridge/docs/evidence/mb-567-emotion-progress.png` in the frontend
repository. This is synthetic fixture evidence, not a deployed live
cross-stack claim.
