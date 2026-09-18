# MB-510 daily emotion check-in evidence

## Delivered boundary

MB-510 adds Journal/AI-owned daily self-reported emotion check-ins and replaces
the dashboard's static selector with an authenticated BFF-backed flow. The
contract is additive to Journal CRUD and exact-revision analysis. No Kafka event,
clinical score, safety inference, recovery claim, reminder-consumer exposure, or
raw note logging is introduced.

The decision and invariants are frozen in ADR 0018 and policy
`MB-DAILY-EMOTION-CHECK-IN-001`. Production privacy/legal approval is not claimed.

## Verification evidence

Synthetic data is used throughout. The following commands were run from the
feature branch, with review follow-ups reverified on 2026-09-17:

| Evidence                     | Command                                                                                      | Result                                                                 |
| ---------------------------- | -------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| Formatting                   | `cd journal-ai-service && npm run format:check`                                              | Passed                                                                 |
| Lint                         | `cd journal-ai-service && npm run lint`                                                      | Passed, zero warnings                                                  |
| Type safety                  | `cd journal-ai-service && npm run typecheck`                                                 | Passed                                                                 |
| OpenAPI                      | `cd journal-ai-service && npm run contract:check`                                            | Passed; Journal/AI v1 contract validated                               |
| Mongo migration              | `cd journal-ai-service && npm run migration:check`                                           | Passed through append-only migration `006_daily_emotion_check_ins.cjs` |
| Unit and HTTP contract tests | `cd journal-ai-service && npm test`                                                          | Passed: 36 tests, 0 failed                                             |
| Real persistence integration | `cd journal-ai-service && npm run test:integration`                                          | Passed: 3 tests, 0 failed, using ephemeral MongoDB                     |
| Repository policy            | `powershell -ExecutionPolicy Bypass -File scripts/verify-repository.ps1 -BaseSha origin/dev` | Passed for all tracked files and 18 changed files                      |

The frontend repository independently passed 289 unit/BFF/component tests, a
production Next.js build, and 2 managed Playwright scenarios against its
same-origin BFF and synthetic provider fixture. A deployed live cross-stack run
was not available locally and is not claimed.

## Story and cross-repository traceability

- Parent Story: Jira MB-510.
- Backend owner delivery: Jira MB-524 and MB-525; branch
  [`feat/journal-ai-daily-emotion-check-in`](https://github.com/NgyenKhoi/mentalbridge-backend/tree/feat/journal-ai-daily-emotion-check-in),
  [PR #61](https://github.com/NgyenKhoi/mentalbridge-backend/pull/61).
- Frontend consumer delivery: Jira MB-526; branch
  [`feat/daily-emotion-check-in`](https://github.com/NgyenKhoi/mentalbridge-frontend/tree/feat/daily-emotion-check-in),
  commits
  [`149354e`](https://github.com/NgyenKhoi/mentalbridge-frontend/commit/149354e2101f414a87015fb5c2007e8a8f114051),
  [`75957e2`](https://github.com/NgyenKhoi/mentalbridge-frontend/commit/75957e27f16e1b6a31b31c1412e50c97216ba07f),
  and
  [`208b6e9`](https://github.com/NgyenKhoi/mentalbridge-frontend/commit/208b6e90614a2705ffb1e5db4fcfcdcbbf71d627).
- Decision slice: Jira MB-522, ADR 0018, and policy
  `MB-DAILY-EMOTION-CHECK-IN-001`.

The reminder projection is explicitly deferred by ADR 0018. There is no
accepted reminder-consent authorization contract, so this implementation fails
closed and exposes no check-in data to reminders. Enabling that consumer
requires a separately reviewed additive contract/policy; AI consent is not
silently reused.

## Jira-ready completion notes

The repository cannot assert that Jira was updated. The following text is the
verified payload for the authorized Jira editor to place on MB-510:

- Implemented behavior: one encrypted, owner-scoped self-reported emotion
  check-in per IANA local day; create/replay/update/history/delete; optimistic
  concurrency; consent-minimized note-free AI context; persisted dashboard
  loading/empty/saved/error states.
- Deferred work: reminder projection is deferred under ADR 0018 until a distinct
  reminder-consent contract is accepted. A deployed live cross-stack run was
  not available locally and is not claimed.
- Evidence: backend PR #61 and frontend branch/commits linked above. Exact local
  commands and results are recorded in this document; required remote CI is
  reported only from GitHub.

## Scenario matrix

| Scenario                 | Expected evidence                                                                       |
| ------------------------ | --------------------------------------------------------------------------------------- |
| Create/reload/history    | Owner receives the encrypted-at-rest record labelled self-reported                      |
| Duplicate retry          | Same idempotency key and input replay the original response                             |
| Concurrent update        | One writer succeeds and the stale writer receives `412`                                 |
| Timezone boundary        | Server instant maps to the submitted IANA local day; mismatches fail `400`              |
| Wrong owner              | Exact day read remains opaque with `404`                                                |
| Delete                   | Encrypted revisions are removed; exact delete retry replays the tombstone               |
| Consent active/withdrawn | Note-free AI projection succeeds only while Care authorizes it                          |
| Dependency failure       | Care/Mongo unavailability returns bounded `503` without unsafe fallback                 |
| Privacy inspection       | Raw optional note is absent from Mongo plaintext, logs, events, and consumer projection |

Review follow-up coverage additionally proves that an exact successful create
command replays after the owner's local day rolls over, while a new command for
the stale day still fails validation. The production Care adapter test verifies
that `policyVersion` and `decidedAt` survive parsing and are returned as consent
provenance in the minimized consumer context.
