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
feature branch on 2026-09-16:

| Evidence                     | Command                                                                                   | Result                                                                 |
| ---------------------------- | ----------------------------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| Formatting                   | `cd journal-ai-service && npm run format:check`                                           | Passed                                                                 |
| Lint                         | `cd journal-ai-service && npm run lint`                                                   | Passed, zero warnings                                                  |
| Type safety                  | `cd journal-ai-service && npm run typecheck`                                              | Passed                                                                 |
| OpenAPI                      | `cd journal-ai-service && npm run contract:check`                                         | Passed; Journal/AI v1 contract validated                               |
| Mongo migration              | `cd journal-ai-service && npm run migration:check`                                        | Passed through append-only migration `006_daily_emotion_check_ins.cjs` |
| Unit and HTTP contract tests | `cd journal-ai-service && npm test`                                                       | Passed: 34 tests, 0 failed                                             |
| Real persistence integration | `cd journal-ai-service && npm run test:integration`                                       | Passed: 3 tests, 0 failed, using ephemeral MongoDB                     |
| Repository policy            | `powershell -ExecutionPolicy Bypass -File scripts/verify-repository.ps1 -BaseSha 4a735cf` | Passed for all tracked files and 17 changed files                      |

The frontend repository independently passed 289 unit/BFF/component tests, a
production Next.js build, and 2 managed Playwright scenarios against its
same-origin BFF and synthetic provider fixture. A deployed live cross-stack run
was not available locally and is not claimed.

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
