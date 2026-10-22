# MB-676 — authoritative mobile reminder overview

## Contract and ownership

Branch: `feat/content-notification-reminder-overview-(MB-676)`, based on backend
`origin/dev` at `d53fc722187f9f03c802c6b8cbe0b9fae79725ba`.

OpenAPI `contracts/openapi/content-notification-service.yaml` v1.10.0 adds
`GET /api/v1/reminder-overview`, payload `schemaVersion: 1.0`. Identity's JWT
determines the USER/owner. Other roles and actor/date/timezone overrides are
rejected. Success has `Cache-Control: private, no-store`.

Content owns composition and delivery facts; Journal/AI owns completion.
Settings stay at `PATCH /api/v1/notification-preferences`, with the existing
If-Match, validation and concurrency rules. No arbitrary cadence or scheduler is
added. Existing lazy default-preference repair may initialize preferences; this
read never creates activity, notifications or delivery claims, and never sends.

## Acceptance behavior

- Enabled Journal/Emotion categories show factual `DUE` or `COMPLETED` for the
  configured local day. Notification read/unread is never completion evidence.
- Five bounded previews cover in-app Journal/Emotion, daily digest, resource
  email and appointment-email ledger state. Preferences, channels/categories,
  approved email timing/cadence, runtime flags, quiet hours and existing delivery
  facts gate eligibility. Every preview is an estimate, never a send guarantee.
- Completed activity has no eligibility time. Already delivered, processing,
  terminal and uncertain states never invent a future send. Empty email content
  is `NOT_NEEDED`, not a claim of completed work.
- Ordered UTC instants handle DST gaps/repeats inside the current local date;
  tomorrow's incomplete activity is not predicted. Existing owner/day/kind
  durable dedupe remains unchanged.
- Appointment email remains a separate flow. Only its latest persisted delivery
  state is exposed; no appointment ID or guessed eligibility time is returned.
  The sending path still revalidates Consultation authority.
- SupportPlan, individual Resources, reassessment and PUSH scheduling are
  explicitly unavailable here. Existing approved resource email opt-in is
  preserved; no producer is broadened.

## Consistency and privacy

The service rechecks preference version after composition. Concurrent opt-out,
timezone or settings changes return `409 REMINDER_OVERVIEW_CHANGED`. Crossing
midnight, mismatched owner/date/timezone, storage or activity failure returns
`503 REMINDER_OVERVIEW_UNAVAILABLE`; query overrides return
`422 REMINDER_QUERY_INVALID`. Safe Problem Details retain correlation only.
Remote calls do not hold database transactions.

The response excludes private text, streaks, scores, addresses, credentials,
appointment IDs and provider payloads. Delivery timestamps are ledger record
times, not proof that someone read a message or completed an activity.

No migration, persisted domain/logical-model change, topic, environment variable
or dependency upgrade is required. SQL additions read existing owned tables.

## Verification

From `content-notification-service/`:

- `npm run lint`, `npm run typecheck`, `npm run contract:check`,
  `npm run migration:check`, `npm run build`: passed. The contract manifest now
  includes the new implemented controller and exact response codes.
- `npm test`: 40 files / 270 tests passed. The module-wide run covers app-module
  registration and existing HTTP consumers.
- `node node_modules/vitest/vitest.mjs run --config vitest.integration.config.ts src/__tests__/integration/reminder-overview.integration.test.ts`:
  3 tests passed on a disposable PostgreSQL 16 container with owner migrations.
  Covers recipient/day isolation, no read-side notification writes/resurrection,
  latest appointment version, concurrent claims and repeated DST local dates.
- A temporary harness ran actual Nest HTTP through Axios into the mobile A2
  parser. Due/completed, subsequent owner completion, pause and safe outage
  passed with synthetic sources. It did not access private accounts or execute
  Android. Temporary harness files were removed after verification.

Local Node: 24.16.0. Pinned mobile Node/native verification remains in CI/MB-666.
Unrelated broker/native suites were not rerun for this read-only feature.

## Consumer handoff and delivery order

`scripts/export-reminder-overview-contract.mjs <consumer-path>` exports only the
dereferenced overview schema/source/version. `--check <consumer-path>` verifies
semantic parity. MB-678 stores it in
`mobile/src/reminders/reminder-overview-v1.contract.json`.

Delivery slices: provider contract/read model/tests; separate mobile consumer
adoption; evidence/Maestro. Each repository publishes its own task branch and PR.
Review/merge/deploy the provider before the consumer. The approved public mobile
edge must route the new path to Content alongside existing inbox/preferences;
mobile never receives a private service URL. Physical Android journeys remain
authored and NOT_RUN for the MB-666 staging batch.

## Acceptance states

| State                                     | Expected response                                                 | Excluded behavior                                        | Authority                                      |
| ----------------------------------------- | ----------------------------------------------------------------- | -------------------------------------------------------- | ---------------------------------------------- |
| Enabled Journal/Emotion                   | Current owner/local-day DUE or COMPLETED and existing destination | Inbox read status, private content or inferred adherence | Journal/AI activity projection; MB-564         |
| Completed today                           | COMPLETED with no eligibility instant                             | Another unnecessary activity reminder                    | Existing reminder materialization policy       |
| Quiet hours or pause                      | QUIET_HOURS estimate or PAUSED with no send guarantee             | Creating jobs, sending on read or bypassing opt-out      | MB-562 preferences and scheduler               |
| Delivered or processing                   | Persisted delivery fact and no invented future instant            | Repeated claim or delivery                               | Existing Content delivery ledgers              |
| Unsupported category                      | Explicit unavailable capability                                   | Client scheduling or guessed activity state              | OpenAPI v1.10.0                                |
| Changed preferences or dependency failure | Safe 409 or 503                                                   | Stale owner facts or downstream payload exposure         | Version check and fail-closed service boundary |

Native Android evidence is a separate staging milestone under MB-662/666;
code-ready review does not claim STAGING_PASS or a deployed endpoint.
