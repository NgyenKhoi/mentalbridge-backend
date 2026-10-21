# Content and Notification Service

MB-691 implements the accepted [Community chat policy](../docs/policies/community-chat-policy-v1.md)
through recipient-specific `MESSAGE_RECEIVED`, `DIRECT_REQUESTED`, `GROUP_INVITED`
facts and separate comment-reaction facts. Content owns durable notice jobs and
inbox state; Community retains exact target/lifecycle authorization. Existing
MB-580/617 post/comment producers remain unchanged. No chat body, sender/group
name or health data enters Kafka, jobs, logs or provider payloads.

## Community notification delivery (MB-691)

`CONTENT_COMMUNITY_NOTICE_CONSUMER_ENABLED=true` requires Kafka and a dedicated
Content-owned PKCS#8 `CONTENT_COMMUNITY_NOTIFICATION_PRIVATE_KEY` plus
`CONTENT_COMMUNITY_NOTIFICATION_KEY_ID`. Community receives only its matching
X.509 public key and opt-in `COMMUNITY_CHAT_NOTIFICATION_ENABLED` flag. Set
`COMMUNITY_SERVICE_URL` to the Community owner. Ordinary Identity or Realtime
credentials cannot authorize this boundary. Both new flags default false.

The new consumer group `content-notification-community-notices-v1` reads the
existing chat topic and `mentalbridge.community.comment-reaction.v1`; provision
the latter using the versioned Compose Kafka initializer before rollout. It
ignores message-save/read-state/authorization transport facts, strictly checks
aggregate/target/key bindings, and parks poison diagnostics as digests in the
existing Community dead-letter topic. Transient intake storage/DLQ failures
retain the offset with 1–30-second backoff and heartbeats. Kafka is acknowledged
only after the minimized job or its terminal cancellation is committed.

Migration `26_add_community_notice_delivery.sql` adds jobs/receipts and nullable
inbox `grouping_key`. The existing interaction-v1 consumer also feeds this queue.
A bounded one-second worker tick claims at most 20 jobs, one at a time, under
30-second expiring fencing tokens. Claims survive process loss. Remote owner
reads finish before the local transaction; inbox insertion/grouping and terminal
job outcome commit together. Denied/expired/revoked targets cancel; owner
uncertainty retries without a cached grant. Quiet-hour work is scheduled for a
new check after 60 seconds, subject to bounded queue/HTTP delays; preference and
target authorization are checked again.
Opt-out at intake or delivery records a terminal outcome, preventing stale
replay after opt-in. Expired receipts are removed in bounded batches; old source
occurrences remain expired and cannot recreate a notice.

Supportive reactions join one active unread notice per recipient/target, with no
public actor list or inferred engagement count. Reading ends that group; another
new actor can start a new one. Duplicates never bump unread or resurrect a soft
deleted item. Chat source identity is kind + exact target per recipient, so a
new transport message ID does not repeat the logical notice. Existing v1 event
identities and historical inbox items remain compatible; no history backfill or
regrouping is performed.

New closed actions are `OPEN_COMMUNITY_CONVERSATION`, `OPEN_COMMUNITY_REQUEST`,
`OPEN_COMMUNITY_INVITATION` with their respective opaque target UUID. Their
canonical hrefs are `/community/chat/conversations/{id}`, `/community/chat/requests/{id}`,
`/community/chat/invitations/{id}`. These are backend consumer contracts: the
current frontend baseline does not implement these routes. Device-2 must map
the descriptors, implement those routes and reauthorize in Community before
enabling the end-user journey. A pending request/invitation never grants history.
This slice makes no browser/mobile `ui-ready` claim.

`community-push-v1.schema.json` and `communityPushPayload` define only the generic
Vietnamese body plus opaque notification ID. PUSH remains preference/contract
only; this delivery does not register devices or send to a provider. Backend
device registration/provider delivery belongs to MB-675, with device permission,
token and safe-link handling in MB-679. Appointment chat, message retraction and
per-user conversation deletion remain independent.

Roll out migrations, the authorization provider, then compatible consumers;
enable opt-in flags only after credentials/topics and frontend handoff are ready.
Rollback consumers before providers. Retain additive migrations and terminal
receipts; do not drop delivery state to roll back a binary.

NestJS service that owns reviewed self-help resource definitions, immutable exact-version Resource Eligibility v1 provenance, notification preferences, and durable notification delivery state. ADR 0009 removes the hotline catalogue from product scope. ADR 0012 keeps final SupportPlan admission in Care and clarifies that review/publication does not make a resource universally plan-eligible.

MB-617 adds an asynchronous consumer for the minimized
`mentalbridge.community.interaction.v1` fact. It creates generic comment,
reply, and first-reaction inbox entries without reading Community content or
introducing any synchronous Community-to-Notification dependency.

MB-565 wires bounded assessment, SupportGuide, and account facts into the same
durable USER notification center. Care emits authenticated result availability
and newly persisted guide availability through its transactional outbox;
Identity emits USER activation/restoration through its existing outbox topic.
Content strictly validates the versioned contracts, owns generic Vietnamese
copy and safe internal actions, applies `screeningReassessment` or
`resourceSystem` preferences, and persists either `DELIVERED` or `CANCELLED`.
Logical `<fact-type>:<aggregate-id>:<aggregate-version>` identities collapse
retries even when a transport envelope is replayed with a different message ID.
Malformed Care facts are parked as digest-only metadata. Enable the Care path
with `CONTENT_CARE_USER_NOTIFICATION_CONSUMER_ENABLED=true`; Kafka remains
disabled by default for local development.

## Current capability

MB-564 materializes owner-visible Journal and emotion reminders plus factual
7/14/30-day streak milestones from a bounded periodic producer. Content pages
persisted channel/content-group preferences, applies quiet hours, and calls
Journal/AI's note-free projection with a dedicated service credential. Inbox
reads have no producer side effects. A failure for one owner is retried by a
later run without hiding persisted inbox history or stopping other owners.
Identity's transactional account lifecycle event initializes the default
preference aggregate for every newly registered `USER`. Duplicate delivery is
collapsed by the aggregate's unique owner key; specialist registrations do not
create user reminder preferences. The preference API retains `getOrCreate()` as
a compatibility repair path for accounts created before this consumer existed.

- `POST /api/v1/resources/{id}/versions/{contentVersion}/eligibility-publications` — ADMIN-only immutable eligibility publication with persisted idempotent replay
- `POST /api/v1/resources/{id}/versions/{contentVersion}/eligibility-publications/withdrawal` — ADMIN-only append-only eligibility withdrawal
- `POST /internal/v1/resource-eligibility:resolve` — authenticated USER-context batch resolution for Care with exact-version outcomes and no content or moderation payload
- MB-337 controlled-demo eligibility matrix — six explicit immutable `vi-VN` resource-version decisions with reviewed provenance and Care-compatible fixture evidence
- `POST /api/v1/safety-directory:lookup` — public coarse-area lookup returning only active reviewed records verified within 90 days; no coordinates or proximity claim
- `/api/v1/safety-directory/admin/entries` — ADMIN-only create, review/verify/activate, update/invalidate, deactivate, and audit-preserving lifecycle

- `GET /health/live` — liveness without a database dependency
- `GET /health/ready` — PostgreSQL readiness check
- `GET /api/v1/resources` — lists active reviewed published self-help resources; returns empty array when none match; returns neutral fallback when service is unreachable; no hotline number or emergency dispatch claim (ADR 0009)
- `GET /api/v1/resources/{id}` — without `contentVersion`, returns only the current active reviewed publication; an exact `contentVersion` may resolve its immutable publication snapshot after archive/correction without restoring catalogue visibility
- `GET /api/v1/resource-progress` and `PUT /api/v1/resource-progress/{resourceId}/{localDate}` — persist owner-scoped, non-clinical daily checklist/completion state without changing SupportPlan occurrences; a confirmed completion is terminal while checklist selections remain editable
- `GET|PUT|DELETE /api/v1/resource-bookmarks` — list, save the current exact reviewed version, or idempotently remove an authenticated owner's private bookmark
- `GET /api/v1/resource-bingo/{weekStart}` and `PUT /api/v1/resource-bingo/{weekStart}/items/{position}/self-confirmation` — read a retained Monday-based board and replace a reversible personal check under a strong ETag; exact-version Content verification remains a separate recomputed fact
- `GET /api/v1/notifications` — returns the authenticated owner's durable inbox newest first with bounded opaque cursor pagination and authoritative unread count
- `PATCH /api/v1/notifications/{id}/read`, `POST /api/v1/notifications/mark-all-read`, and `DELETE /api/v1/notifications/{id}` — persist idempotent read and tombstone lifecycle changes without exposing another owner's records
- MB-556 Review 1 catalogue — 15 Vietnamese resources with structured source metadata and verified YouTube actions for every `VIDEO`; legacy synthetic resources remain exact-ID compatible but are hidden from catalogue browsing
- `POST|DELETE /__test/content/outage` — test-only local outage switch; unavailable unless `E2E_TEST_MODE=true` and the exact `x-e2e-secret` is supplied
- Strict startup configuration, safe Problem Details, structured redacted request logs, CORS deny-by-default, and graceful NestJS shutdown
- `node-pg-migrate` baseline for the service-owned `mentalbridge_content_notification` database

Resource Eligibility v1 independently validates target domain, `PRIMARY`/`ADJUNCT` role, compatible instrument bands, support tier, locale and effective window. MB-589 completes the separate ADMIN editorial gate: only Identity ADMIN accounts listed in `RESOURCE_REVIEWER_ACCOUNT_IDS` may record an exact-version review decision; review state is server-owned and an approved, source-complete decision is required before publication. Publication stores an immutable snapshot, archive stores an append-only withdrawal, and a correction draft may start only after the current publication is archived. Correction drafts cannot be physically deleted because the stable resource root retains immutable publication history. Publishing never creates Resource Eligibility.

Admin resource create, draft update/delete, exact-version review, publish, archive, and correction-version operations are implemented in `../contracts/openapi/content-notification-service.yaml`. Safety screening and versioned safety guidance remain Care-owned behavior.

MB-562 also implements `GET|PATCH /api/v1/notification-preferences` for the
authenticated owner. The atomic preference aggregate covers `IN_APP`, `EMAIL`,
and future `PUSH` channel choices, six independent content groups,
timezone-aware quiet hours, and email cadence/opt-ins. `PUSH` is persisted only;
device registration and provider delivery remain deferred.

MB-514 extends that same preference aggregate with the owner's local daily
digest and explicit resource-reminder times. The bounded wellbeing scheduler
builds only factual, approved resource and Journal activity projections,
respects email opt-in, cadence, timezone and quiet hours, and records a durable
per-owner/local-day delivery claim before calling Brevo. It never includes raw
Journal text, assessment answers, chat content, or automatic safety email.

Email delivery is disabled by default. To enable it, set
`WELLBEING_DIGEST_SCHEDULER_ENABLED=true` and provide
`IDENTITY_SERVICE_URL`, a shared 32-character-or-longer
`IDENTITY_NOTIFICATION_SERVICE_TOKEN`, plus `BREVO_API_KEY` and a verified
`BREVO_SENDER_EMAIL`. The same service token must be configured in Identity.
Keep the interval and database page size bounded with
`WELLBEING_DIGEST_SCHEDULER_INTERVAL_MS` and
`WELLBEING_DIGEST_BATCH_SIZE`. `BREVO_BASE_URL` and `BREVO_SENDER_NAME` are
optional operational overrides.
MB-548 adds a separate, opt-in appointment email reminder. A minimized
Consultation status event materializes at most one row per
recipient/appointment/version, and rejection, expiry, cancellation, reschedule,
or ended states invalidate pending older versions. The scheduler rechecks
authoritative appointment truth, preference, timezone/quiet hours, and verified
delivery address immediately before Brevo delivery. It never sends at or after
the appointment start and never combines this message with the wellbeing digest.
Production enablement also requires Brevo per-contact tracking consent to be
enabled with unknown-contact tracking disabled; the adapter sends
`contactPixelTrackingConsent=false` for every reminder recipient.

## Stack

- Node.js 22 or newer, strict TypeScript, NestJS 11
- PostgreSQL through `pg`
- `node-pg-migrate` for append-only PostgreSQL migrations
- Pino, Zod, Vitest, Supertest, and Testcontainers

## Local development

Copy `.env.example` to an untracked `.env`, provision the owned database, then run:

```bash
npm ci
npm run dev
```

The application loads `.env` only in development. Test and production environments require real process variables and never depend on a repository `.env` file.

When a local Docker Desktop container can reach the shared PostgreSQL port but
TLS 1.3 negotiation resets before authentication, set
`CONTENT_NODE_OPTIONS=--tls-max-v1.2` in the root Compose `.env` and recreate
only `content-notification`. Leave this option unset when TLS 1.3 works. The
remote database URL must still require TLS; this setting does not permit a
plaintext connection.

For live local E2E, set `E2E_TEST_MODE=true` and a random `E2E_TEST_SECRET`.
The Playwright harness can then toggle the service itself:

```powershell
$headers = @{ 'x-e2e-secret' = $env:E2E_TEST_SECRET }
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:3003/__test/content/outage -Headers $headers
Invoke-RestMethod -Method Delete -Uri http://127.0.0.1:3003/__test/content/outage -Headers $headers
```

This changes the Content provider state and lets the real BFF receive a genuine
dependency-unavailable response; it does not intercept or rewrite browser traffic.

### JWT authentication

This service verifies RS256 JWTs issued by Identity Service. For local development, each developer generates their own RSA key pair:

```powershell
# Windows
.\scripts\generate-local-jwt-keys.ps1
```

```bash
# Linux/macOS
./scripts/generate-local-jwt-keys.sh
```

Keys are written to `.local/secrets/` (git-ignored). Copy the generated public key value into `.env`:

```env
IDENTITY_JWT_ISSUER=https://identity.local.mentalbridge
IDENTITY_JWT_AUDIENCE=mentalbridge-api
IDENTITY_JWT_KEY_ID=local-development-key
IDENTITY_JWT_PUBLIC_KEY=-----BEGIN PUBLIC KEY-----\n...\n-----END PUBLIC KEY-----
IDENTITY_JWT_CLOCK_TOLERANCE_SECONDS=60
```

Identity Service must be configured with the corresponding private key (`IDENTITY_JWT_PRIVATE_KEY`).

## Migrations

The application pool and `node-pg-migrate` share `DATABASE_URL`. Set `sslmode=require` or a stricter verified mode for remote PostgreSQL, then run:

```bash
npm run migration:check
npm run migrate:up
```

Migrations run explicitly before deployment and never on application startup. The database and login are operator prerequisites; migrations do not create databases or schemas. Once merged, an applied migration is never edited or rolled back in a shared environment; add a forward migration instead. Migration `2_remove_hotline_catalogue.sql` removes the obsolete table after the historical baseline is applied; migration `6_add_resource_eligibility_v1.sql` adds immutable publications, declarations, withdrawals and command replay snapshots without changing existing resource rows; migration `9_add_resource_source_provenance.sql` adds structured source fields, catalogue visibility, and VIDEO URL constraints.

Migration `10_persist_notification_preferences.sql` converts the legacy
channel/category matrix into one atomic owner aggregate while preserving any
existing owner choices.

Migration `11_persist_notification_inbox.sql` completes the durable inbox
aggregate with per-recipient source deduplication, occurred time, approved internal actions,
delivery state, optimistic lifecycle versioning, and a maximum 90-day retention
deadline. Inbox reads tombstone expired rows before returning active items.

Migration `12_add_journal_emotion_notification_kinds.sql` adds the four MB-564
Journal/emotion reminder and milestone kinds without rewriting historical
generic notifications. The scheduled producer uses `JOURNAL_AI_SERVICE_URL`,
the bounded `JOURNAL_AI_SERVICE_TIMEOUT_MS`, and the shared
`JOURNAL_AI_REMINDER_SERVICE_TOKEN`. Enable it with
`REMINDER_SCHEDULER_ENABLED=true`; interval and database page size are bounded
by `REMINDER_SCHEDULER_INTERVAL_MS` and `REMINDER_SCHEDULER_BATCH_SIZE`.
`CONTENT_ACCOUNT_LIFECYCLE_CONSUMER_ENABLED=true` consumes
`mentalbridge.identity.account-lifecycle.v1` from `KAFKA_BOOTSTRAP_SERVERS`.
Invalid JSON/schema facts and conflicting logical identities are parked as
digest-only metadata in
`mentalbridge.content-notification.account-preference-dead-letter.v1` before
their offsets advance. Valid account facts and preference initialization retry
storage up to three times locally (100ms/200ms delay). A continuing database
failure throws a sanitized retriable error and leaves the Kafka offset
uncommitted; KafkaJS uses exponential backoff (1s initial, 30s cap, five
attempts per cycle) and restarts the consumer. Recovery replays the original
record and commits only after the Content transaction succeeds. There is no
digest-only conversion of valid facts and no raw event body in diagnostics.

Migration `13_add_resource_daily_progress.sql` adds MB-603's owner-scoped daily
resource progress. It stores only resource/version references, a bounded action
identifier set, status, and timestamps; it never stores free-text reflection or
redefines SupportPlan occurrence tracking.

Migration `16_add_wellbeing_digest_delivery.sql` adds the MB-514 delivery-time
preferences and the durable wellbeing email delivery ledger. Its unique
owner/local-day/kind key prevents duplicate default digests, while bounded
claim, retry, provider outcome, and cancellation metadata make crashes,
provider outages, late runs, opt-out, and deleted recipients recoverable
without persisting message bodies or sensitive source content.
Migration `17_add_appointment_email_reminders.sql` adds the explicit appointment
email opt-in, version-deduplicated delivery state, and per-appointment consumer
checkpoint. The rows contain only safe scheduling/delivery metadata; recipient
email and rendered message bodies are not persisted.

Migration `18_add_community_interaction_notifications.sql` adds the Community
in-app content-group preference, three presentation kinds, and the approved
`OPEN_COMMUNITY_POST` action. Enable the consumer with
`CONTENT_COMMUNITY_INTERACTION_CONSUMER_ENABLED=true`. Strictly malformed or
incompatible facts are parked as digest/offset metadata in
`mentalbridge.content-notification.community-interaction-dead-letter.v1`; raw
event bodies are never copied to that topic or logs. Persistence failures remain
retryable and do not acknowledge the source offset.

Migration `19_complete_resource_editorial_lifecycle.sql` adds immutable exact-version
review decisions and publication snapshots plus append-only withdrawals. Existing
published and archived rows are backfilled without changing their content version.

Migration `20_add_resource_reward_visibility.sql` stores only the owner's optional
`VISIBLE` or `HIDDEN` reward presentation choice. MB-659 XP remains a deterministic,
bounded read model over exact reviewed completion and verified practice evidence;
source outages return 503 and are never represented as an authoritative zero.

Migration `21_persist_resource_engagement.sql` adds exact-version private
bookmarks plus versioned weekly Bingo self-confirmation. Verified Bingo evidence
is not copied into the board: reads reconcile current exact-version progress and
eligible plan-bound practice, so corrections cannot leave a fabricated mark.

Migration `22_add_private_achievement_preference.sql` stores only the owner's
versioned `VISIBLE` or `HIDDEN` gallery preference. `GET /api/v1/achievements`
calculates `achievement-rules-v1` from exact reviewed Resource facts and the
owner-bound minimized Journal/AI projection. Journal/AI failure is an explicit
per-domain unavailable state; it never becomes zero or stale progress.

The controlled Review 1 seed, MB-337 eligibility matrix, and visibly synthetic non-dialable safety-directory fixture are owner-module migrations with a separate ledger (`pgmigrations_review1`), so running normal schema migrations cannot accidentally mark controlled data as applied. The seeds reject drift from their reviewed decisions. Machine-readable resource inventory, reviewer rationale, explicit ineligible decisions, and Care requests are kept in `../contracts/fixtures/content/resource-eligibility-v1-controlled-demo.json`. No real safety contact is published by the controlled fixture. For the shared dev/staging database only, run:

```bash
npm run migrate:review1:up
```

Review 1 Compose runs schema migrations, controlled resources, the initial eligibility publication, and the MB-556 reviewed catalogue sequentially through `npm run migrate:review1-demo`. The MB-556 copy is an implementation draft and still requires the documented clinical, legal, and licensing review before production publication. Future production deployment must run `npm run migrate:up` only.

## Verification

```bash
npm run quality
```

From the repository root, the same required gate is
`npm --prefix content-notification-service run quality`.

The required module quality command runs formatting, lint, type checking, unit/HTTP tests,
contract and migration checks, the PostgreSQL integration suite, and the production build.
The integration suite requires Docker for its disposable PostgreSQL container. Individual
commands remain available for focused development:

```bash
npm run format:check
npm run lint
npm run typecheck
npm test
npm run test:integration
npm run contract:check
npm run migration:check
npm run build
```

## Production image

```bash
docker build -t content-notification-service .
docker run --env-file .env -p 3003:3003 content-notification-service
```

Production credentials must be injected by the deployment environment; do not use a committed `.env` file.
