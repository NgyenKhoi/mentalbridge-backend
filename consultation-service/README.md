# Consultation Service

## MB-795 staging scheduler hotfix

Each due video end and room closure runs independently, so one failing item
does not prevent later items or the closure phase; failure logs carry only the
appointment ID and error code/class. Under the existing appointment lock, an
existing `video-ended:<id>` history row recording `CONFIRMED`/`IN_PROGRESS` to
`SESSION_ENDED` with an approved end reason allows a reopened active appointment
to restore its ended status, policy and end timestamp without another history row
or outbox event (2026-11-03 demo ruling). Any other prior row fails that item in
isolation and leaves it unchanged. History remains immutable and timer end never
completes the appointment, consumes/releases its HELD credit or creates an
earning. Invalid-credit REQUESTED expiry remains fail-closed. The read-only
operator audit queries and the separately authorized repair rule are in
[MB-795 traceability](../docs/requirements-traceability/stories/mb-795.md).
No migration is required.

## PayOS pay-in (MB-786)

[ADR 0047](../docs/adr/0047-payos-active-gateway-momo-dormant.md) selects PayOS
for subscription purchase/upgrade while keeping MoMo selectable and dormant.
`DISABLED` remains the default; `FAKE` produces a deterministic simulated QR
without a provider call. The catalogue reports the selected provider (`PAYOS`
when disabled) and the authoritative checkout readiness flag.

Real PayOS collection requires the explicit Spring `staging` profile, with no
`prod`/`production` profile, all three collect credentials, the exact official
API origin and HTTPS webhook/return/cancel URLs on `staging.mentalbridge.online`
(default TLS port only). In Compose, `CONSULTATION_SPRING_PROFILES_ACTIVE` selects
that profile; standalone deployments use `SPRING_PROFILES_ACTIVE`. Payout keys
and MoMo production approval are not PayOS collect prerequisites. D1 permits
the unchanged PLUS 5,000 / PREMIUM 10,000 VND test catalogue and full-price,
zero-offset upgrade on staging only. Synthetic earnings remain non-redeemable;
production cutover, refunds and payout implementation are outside this story.

Checkout first commits the server-priced payment and numeric order code, then
uses official `vn.payos:payos-java:2.0.1` outside the transaction. There is no
automatic create retry; an uncertain command replay returns the same payment.
Confirmed PayOS/FAKE checkout exposes the in-app QR payload and transfer details;
the chosen local expiry remains authoritative if the provider omits expiry.
Legacy URL fields remain nullable, with HTTPS provider-host validation.

The public `POST /internal/v1/payments/payos/webhook` verifies the original body
through the SDK, then validates the complete signed data and successful envelope.
Only exact order/link/amount/reference evidence for an unexpired pending payment
can activate a period and credits through the shared locked settlement path.
Exact redelivery is idempotent. Signed mismatches, reused references, confirmation
samples and terminal late receipts are sanitized `VERIFIED_UNMATCHED` evidence
in commercial reconciliation, without raw payloads or bank-party details.

Owner payment GET reconciles pending PayOS payments with signed SDK status queries,
bounded cadence/backoff and no database transaction over HTTP. Only an exact
`PAID` result with one matching transfer settles; underpayment stays pending.
Expiry reconciles first, commits local finality/releases upgrade-held credits,
then cancels the provider link. Return URLs never settle or cancel payments.
Failed expiry cancellation retries with bounded backoff; six failed attempts
leave `PAYOS_CANCEL_REQUIRES_REVIEW` in the persisted reconciliation audit code.
The terminal payment and restored credits stay final throughout those retries.
`terminalReason` is nullable for legacy payments; new known failures persist
`EXPIRED`, `CANCELLED` or `PROVIDER_FAILED` alongside ledger status `FAILED`.

| Configuration | Meaning |
| --- | --- |
| `PAYOS_PAYMENT_CLIENT_ID` | Collect channel client ID; injected, empty by default |
| `PAYOS_PAYMENT_API_KEY` | Collect API key; injected, empty by default |
| `PAYOS_PAYMENT_CHECKSUM_KEY` | Collect signature key; injected, empty by default |
| `PAYOS_PAYMENT_BASE_URL` | `https://api-merchant.payos.vn`; other origins fail readiness |
| `PAYOS_PAYMENT_WEBHOOK_URL` | Public HTTPS staging callback; registration is MB-788 |
| `PAYOS_PAYMENT_RETURN_URL` | HTTPS staging browser return; never settlement evidence |
| `PAYOS_PAYMENT_CANCEL_URL` | HTTPS staging browser cancel return; never financial evidence |

SDK requests have a 10-second timeout, zero automatic retries and no payload
logging. `confirmWebhook()` is an explicit gateway operation, not a startup
side effect. MB-788 owns live registration, credentials and real-money evidence.

Focused verification from this module on Java 21 with `SPRINGDOTENV_ENABLED=false`:

```powershell
.\mvnw.cmd -q '-Dtest=BillingPropertiesTests,PayosCheckoutGatewayTests,SubscriptionBillingIntegrationTests,PayosPaymentIntegrationTests,ConsultationOpenApiContractTests,ConsultationLiquibaseMigrationTests,ConsultationLiquibaseChangelogTests,PayosPaymentMigrationTests,AdminCommercialReconciliationIntegrationTests' test
.\mvnw.cmd -q verify
```

Integration tests use isolated PostgreSQL Testcontainers, never shared databases.

MB-779 rechecks authenticated JWT subjects against Identity before protected
requests. Configure `IDENTITY_SERVICE_BASE_URL` and the dedicated
`CONSULTATION_IDENTITY_ACCOUNT_STATUS_SERVICE_TOKEN`; the read uses 250 ms
connect and 750 ms response defaults, no retry, no ACTIVE cache, and fails closed.

## Voice entitlement prerequisite (MB-756)

`POST /internal/v1/ai-companion/voice-entitlement-authorizations` is a narrow
read-only Journal/AI prerequisite for [ADR 0046](../docs/adr/0046-ai-voice-companion-contract.md).
It delegates to `CurrentServiceEntitlementService.current(ownerId)`. Missing,
expired and not-yet-effective grants return `FREE` / `DEFAULT_FREE` without
`validUntil`; current PLUS/PREMIUM PAID/DEMO grants include their strict future
expiry. The response echoes the exact opaque owner/session/purpose binding and
returns policy/source/version provenance (for example
`service-entitlement-v1:PAID:7`) in the frozen `entitlementVersion` string field
(at most 128 characters). It contains no
payment, source-reference, credit, balance or profile data.

Inject `CONSULTATION_VOICE_JOURNAL_SERVICE_TOKEN` only into Consultation and
Journal/AI's service-owned secret configuration. The exact header is
`X-MentalBridge-Voice-Entitlement-Token`. An absent or empty configuration disables
access without preventing startup. A configured token must contain 32–256
printable ASCII characters without whitespace; malformed configuration fails
startup. Reuse of Consultation's evidence or appointment-reminder token also
fails startup. Use independent secrets for credentials held by other services;
Consultation cannot compare secrets that are not configured locally.
This credential is distinct from browser JWTs, worker, Care, reminder and
general internal credentials; do not forward a user Authorization header.
Only the trusted Journal caller may submit `ownerId`, `voiceSessionId` and
`purpose: AI_COMPANION_VOICE`. Consultation echoes this session reference; it
does not query Journal's session database or grant media access.

Success and all authentication/validation/dependency errors use `no-store`.
Voice errors retain the closed MB-755 Problem shape and codes with absolute
type URIs; legacy Consultation routes keep their existing error behavior.
No migration, financial mutation, provider call, voice admission, room or
deployment is introduced. Care's independent consent prerequisite and final
Journal integration remain separate owner deliveries.

Focused verification:

```sh
./mvnw -B -Dspringdotenv.enabled=false -Dtest=VoiceEntitlementPropertiesTests,VoiceEntitlementControllerTests,ConsultationOpenApiContractTests,AdminOperationsSecurityIntegrationTests test
```
The HTTP slice uses the real current-entitlement service, a mocked owner
repository and fixed time, without a database or external providers.

## Migration naming and applied history (MB-717)

New Consultation SQL migrations use UTC `yyyyMMddHHmmss-description.sql`, for
example `20261022210000-add-owner-audit.sql`. Use a real calendar instant, lowercase
kebab-case description and a timestamp not already used by another new file.
Append its explicit include to `db.changelog-master.yaml` after the existing
history; master include order, not filename sorting, determines execution order.
Use a new changeset ID such as `consultation-20261022210000-add-owner-audit`.

The 29 legacy changesets through `028` are frozen, including both `021` prefixes.
They are different full identities and have both been applied. Do not renumber,
rename, reorder or rewrite them, add `runAlways`/`runOnChange`, clear checksums,
or change shared `DATABASECHANGELOG` rows to make filenames look sequential.
The [MB-717 decision and evidence](../docs/story-mb-717-consultation-migration-names.md)
records the inspected metadata and compatibility reasoning.

`ConsultationLiquibaseChangelogTests` checks the ordered legacy identities and
version-9 checksums against `src/test/resources/db/changelog/consultation-legacy-v1.tsv`,
requires every SQL file to be included, and validates timestamp names/uniqueness
for all files outside that fixed baseline. Do not regenerate the legacy fixture
when adding a migration. `ConsultationLiquibaseMigrationTests` validates a fresh
disposable PostgreSQL history and proves a second update does not replay it.

MB-623 consolidates the existing online journey in the versioned
[state/settlement map](../docs/policies/consultation-journey-state-map-v1.md).
Its [verification runbook](../docs/runbooks/mb-623-consultation-journey.md)
distinguishes real owner/PostgreSQL tests, controlled production Web/BFF
verification and unverified live dependencies. No aggregate, migration, booking
rule, financial transition or provider enablement is added by this closure.

MB-622 earning materialization and withdrawal reuse the appointment dispute
gate. OPEN disputes and RELEASE_USER_CREDIT resolutions block payment, including
failed-payout retries. Ineligible AVAILABLE rows return to PENDING_SETTLEMENT
without changing amount/version/allocation snapshots; upheld resolutions can
restore eligibility after the original hold.

`GET /api/v1/admin/payments` is an ADMIN-only, read-only view of at most 200
canonical purchase/upgrade transactions, without checkout URLs or customer
profile data. Specialist earnings identify the configured payout adapter before
a destination exists. Both specialist and admin payout reads use the latest
attempt reference and count each earning once after retries.

Owns specialist approval and deterministic non-clinical discovery, 60-minute
in-app chat/video availability and appointments, session
evidence and user-visible summaries, subscription/payment/upgrade,
consultation credits, specialist earnings/provider payout reconciliation, and
reviews. Billing and booking invariants stay transactionally local in its
PostgreSQL data. Specialist verification documents are private Consultation-owned
records. The specialist uploads a file, an administrator reviews it, and only a
verified credential title selected by the specialist can appear publicly.

Story 6101 implements the first profile vertical slice: a specialist can save the six
approved public fields, submit the profile, and an administrator can inspect
and approve it. The approval history is audited. That initial slice did not accept
credential, license, or verification-document claims; the later credential review
flow is described below. MB-360 completes rejection,
same-profile resubmission, suspension, and restoration with closed reasons and
audited transitions. Suspension atomically withdraws future availability,
cancels future not-started appointments, and releases their exact held credits;
restoration never revives those records. MB-363 adds approved-only discovery,
public profile detail, deterministic explanations, and current exact-slot
handoff. Billing purchase and cross-service brief/chat integrations remain
later stories. MB-362 adds approved-specialist publication, owner listing, and
tombstone withdrawal of exact online slots; it does not create appointments.
MB-377 adds Consultation-owned plan-period credit rows, an append-only
transition ledger, and an authenticated owner balance. Provisioning is
idempotent and keeps `DEMO` distinct from `PAID`; it does not infer payment.
MB-378 adds the appointment request slice. A paid user selects one exact
online slot and Consultation atomically creates a `REQUESTED` snapshot while
holding one eligible credit. Slot/credit concurrency and command replay are
enforced locally. MB-379 lets only the assigned specialist accept or reject an
eligible request with optimistic concurrency and idempotent history. Acceptance
keeps the credit held; rejection and server-scheduled deadline expiry release
the exact credit once in the same local transaction.
The owner's 2026-10-18 MB-384 timing amendment removes advance booking delay:
current unexpired sixty-minute slots can be published, discovered and booked
immediately. For a short-notice request whose old decision buffer has passed,
the assigned specialist may confirm until slot end. Migration 027 expands only
the deadline upper bound to scheduled end; existing rows and older writers stay
valid. Session, approval, credit and financial checks remain authoritative.
MB-558 adds `consultation-credit-v2` for new periods (`FREE=0`, `PLUS=4`,
`PREMIUM=10`), preserves existing v1 periods as `0/1/3`, and exposes/enforces
separate active-reservation limits `0/2/4`. Replacement requests retain the old
appointment snapshot while moving its credit and reservation atomically.
Appointment existence alone does not grant sensitive data access;
Care owns the user-approved appointment-scoped `ConsultationBrief` and sharing
decision. MB-592 exposes a separate internal, audited continuity projection for
the authenticated currently approved specialist. It contains only assigned
`CONFIRMED`/in-flight appointments and appointments ending within the last 90
days, is capped at 200 rows, and never acts as a user directory or carries Care
content.
MB-643 lets an explicit user booking request retain an optional opaque
Care-owned `originatingPlanActionId`. It is a presentation correlation only:
there is no cross-service foreign key, Consultation never edits or completes a
SupportPlan, and opening specialist discovery alone creates no appointment or
data-sharing consent. Reschedule-as-new inherits the owned prior appointment's
reference when an older client omits it and rejects a conflicting explicit
reference. The owner appointment list accepts an optional exact action filter
that is applied before its 100-row bound.
MB-380 adds optimistic, idempotent owner cancellation and completes
reschedule-as-new audit. Requested cancellations and confirmations at least 24
hours before start release the held credit; later confirmed cancellations
forfeit it. Every cancellation records actor, stable reason, instant, exact
credit outcome, and append-only history. Replacement creation and old
appointment settlement remain one transaction.
MB-364 lets the appointment owner create and later edit one 1-5 rating only
after evidence-backed completion. The current rating and specialist count/sum
aggregate commit together; public discovery discloses average/count and uses
the aggregate only as the final `PREMIUM` tie-breaker.
MB-588 adds an ADMIN-only, read-only appointment operations projection with
bounded time/status/modality/account filters and cursor pagination. Consultation
remains the lifecycle authority; the projection carries operational timestamps,
reason codes, and credit settlement state but no brief, summary, journal,
assessment, chat, or private-note content. It grants no appointment mutation or
clinical authority.

MB-619 lets either assigned participant open one minimized dispute within 24
hours of an eligible settled session outcome. An open dispute blocks future
earning eligibility. Admin resolution is bounded to upholding the recorded
outcome or releasing user credit; a terminal credit release appends an explicit
`ADJUSTED_RELEASED` ledger fact and preserves prior settlement history.

## Integration

- Inbound REST: implemented specialist/admin APIs are defined in `../contracts/openapi/consultation-service-v1.yaml`.
- Outbound REST: discovery optionally calls Care for one exact owned
  SupportEvaluation through a narrow OpenFeign adapter. It reduces the response
  to domain/pathway priorities in memory; unavailable or malformed context
  produces explained neutral ranking and never exposes raw assessment data.
  Other consent/authorization integrations remain separately gated.
- Async: appointment lifecycle changes are written to the Consultation-owned
  transactional outbox and relayed to
  `mentalbridge.consultation.appointment-status.v1`. The minimized event carries
  appointment/version, owner, status, start, and modality only; it contains no
  health, Journal, assessment, brief, or chat content.
- Discovery: registers as `consultation-service` and resolves Spring providers through Eureka. Registry data does not grant authorization.

## Configuration

MB-384 uses the existing LiveKit Cloud `mentalbridge` Free project. Set backend
`LIVEKIT_URL` (wss Cloud origin), `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET` through
the existing local Spring configuration mechanism or injected environment.
`CONSULTATION_VIDEO_ENABLED=false` is the safe runtime default, independent of
slot publication. `LIVEKIT_TOKEN_TTL=PT2M` is bounded to 30 seconds–5 minutes.
For the approved local product demo, `CONSULTATION_DEMO_IMMEDIATE_VIDEO_ENABLED`
defaults false. An explicit local/dev/test profile and effective DEMO entitlement
plus held DEMO credit are required; enabled staging/production fails startup.
Booking keeps the current no-advance-delay, unexpired-slot contract. Both
assigned actors may join a qualifying demo immediately after Specialist acceptance. PAID,
FREE, chat, cancellation, completion and scheduled-end gates are unchanged.
Early media is not completion evidence: explicitly ending a pre-start demo
keeps verification pending and credit HELD, without earning or automatic release.
No secret/token may enter browser configuration, logs, URLs or source control.
Missing provider configuration fails video operations closed, not unrelated
appointment/manual brief workflows. See [ADR 0039](../docs/adr/0039-livekit-cloud-video-consultation.md)
and the [MB-384 local video runbook](../docs/runbooks/mb-384-livekit-video.md).

| Variable | Required | Purpose | Safe local example |
| --- | --- | --- | --- |
| `EUREKA_DEFAULT_ZONE` | Production | Eureka registry endpoint shared by Spring services | `http://localhost:8761/eureka/` |
| `CONSULTATION_DB_URL` | Yes | JDBC URL for Consultation-owned PostgreSQL | `jdbc:postgresql://localhost:5432/mentalbridge_consultation` |
| `CONSULTATION_DB_USERNAME` | Yes | Consultation database role | `mentalbridge_consultation` |
| `CONSULTATION_DB_PASSWORD` | Yes | Consultation database credential | local secret |
| `CONSULTATION_LIQUIBASE_ENABLED` | Deployment | Enables schema migration for the migration job/owner | `true` |
| `IDENTITY_JWT_ISSUER` | Yes | Accepted Identity token issuer | `https://identity.local.mentalbridge` |
| `IDENTITY_JWT_AUDIENCE` | Yes | Required API audience | `mentalbridge-api` |
| `IDENTITY_JWT_PUBLIC_KEY` | Yes | X.509 PEM public key used to verify tokens | local public key |
| `CONSULTATION_VIDEO_AVAILABILITY_ENABLED` | No | Enables `IN_APP_VIDEO` slot publication only after the provider contract gate passes | `false` |
| `CONSULTATION_VIDEO_ENABLED` | No | Enables authorized LiveKit connection issuance; not slot publication | `false` |
| `CONSULTATION_DEMO_IMMEDIATE_VIDEO_ENABLED` | Local demo only | Source-scoped early video join after confirmation; prohibited in staging/production | `false` |
| `LIVEKIT_URL` | Enabled video | Existing Cloud project's secure WebSocket origin; no path/query | `wss://<project-host>.livekit.cloud` |
| `LIVEKIT_API_KEY` / `LIVEKIT_API_SECRET` | Enabled video | Backend-only credentials for token issuance, room operations and signed webhooks | injected secrets |
| `LIVEKIT_TOKEN_TTL` | No | New participant-token validity, bounded 30 seconds–5 minutes and clipped to scheduled end | `PT2M` |
| `CONSULTATION_BILLING_MODE` | No | Subscription checkout mode: `DISABLED`, simulated `FAKE`, dormant approved `MOMO`, or staging-only `PAYOS` | `DISABLED` |
| `CONSULTATION_BILLING_CHECKOUT_TTL` | No | Maximum lifetime of a pending purchase or upgrade checkout | `PT15M` |
| `CONSULTATION_BILLING_EXPIRY_INTERVAL` | No | Scheduler interval for expiring abandoned checkouts and releasing upgrade-held credits | `PT1M` |
| `MOMO_PAYMENT_PRODUCTION_APPROVED` | Real checkout | Explicit operational gate required together with complete credentials and HTTPS URLs | `false` |
| `MOMO_PAYMENT_PARTNER_CODE` / `MOMO_PAYMENT_ACCESS_KEY` / `MOMO_PAYMENT_SECRET_KEY` | Real checkout | MoMo One-Time Payment credentials used to create and verify signed requests | injected secrets |
| `MOMO_PAYMENT_SIGNATURE_KEY_VERSION` | Real checkout | Non-secret identifier retained with verified IPN evidence for key rotation | `v1` |
| `MOMO_PAYMENT_BASE_URL` | Real checkout | Approved MoMo API origin | `https://test-payment.momo.vn` |
| `MOMO_PAYMENT_IPN_URL` / `MOMO_PAYMENT_REDIRECT_URL` | Real checkout | Public HTTPS callback and browser return URLs | deployment URLs |
| `APPOINTMENT_REMINDER_SERVICE_TOKEN` | When reminder flow enabled | Shared service credential for the narrow eligibility read | injected secret |
| `KAFKA_BOOTSTRAP_SERVERS` | When relay enabled | Broker list for appointment status events | `localhost:9092` |
| `CONSULTATION_APPOINTMENT_OUTBOX_RELAY_ENABLED` | No | Enables bounded appointment outbox relay | `false` |
| `CONSULTATION_PAYOUT_MODE` | No | `DISABLED`, deterministic `FAKE`, independently gated dormant `MOMO`, or staging-only `PAYOS`; unknown modes fail closed | `DISABLED` |
| `PAYOS_PAYOUT_CLIENT_ID` | PAYOS payout | Separate payout channel client ID; never borrowed from collect | empty; injected secret |
| `PAYOS_PAYOUT_API_KEY` | PAYOS payout | Separate payout channel API key | empty; injected secret |
| `PAYOS_PAYOUT_CHECKSUM_KEY` | PAYOS payout | Separate payout signing and response-verification key | empty; injected secret |
| `PAYOS_PAYOUT_BASE_URL` | PAYOS payout | Approved HTTPS payout origin; defaults to the official merchant API | `https://api-merchant.payos.vn` |
| `CONSULTATION_PAYOUT_STAGING_DEMO_ENABLED` | No | Default-off MB-812 funded-demo exception, effective only with staging and neither prod nor production | `false` |
| `CONSULTATION_PAYOUT_SETTLEMENT_HOLD` | No | Positive evidence-backed settlement/dispute hold; cannot be below P7D outside the effective staging demo gate | `P7D` (demo `PT10M`) |
| `CONSULTATION_PAYOUT_MINIMUM_WITHDRAWAL_VND` | No | Positive server-authoritative VND minimum; cannot be below 100000 outside the effective staging demo gate | `100000` (demo fallback `2000`) |
| `CONSULTATION_PAYOUT_ENCRYPTION_KEY` | Destination setup | Base64-encoded 32-byte AES key protecting payout destination data | local secret |
| `CONSULTATION_PAYOUT_ENCRYPTION_KEY_VERSION` | No | Key identifier retained with ciphertext for controlled rotation | `v1` |
| `MOMO_PAYOUT_PARTNER_CODE` / `MOMO_PAYOUT_ACCESS_KEY` / `MOMO_PAYOUT_SECRET_KEY` | Signed sandbox callback only | MoMo sandbox callback verification credentials; do not enable real payout | injected sandbox secrets |

Production must override local URLs and secrets. Real payment and payout remain
off until a separate production approval and credentials. No refund adapter is
planned.

For container-based local/demo startup, copy `.env.example` to an untracked
`.env` and replace the database placeholder values. The root Compose profile
runs `consultation-migrate` first with Liquibase enabled, then starts the
`consultation` application container with runtime migrations disabled:

```powershell
docker compose --profile demo up consultation-migrate consultation
```

The committed Docker image contains the Maven-built service only. Database
provisioning remains an operator prerequisite; Compose never creates, resets,
or owns the shared dev/staging Consultation database.

## Implemented specialist lifecycle endpoints

- `GET|PUT /api/v1/specialist-profile`
- `POST /api/v1/specialist-profile/submit`
- `POST /api/v1/specialist-profile/resubmit`
- `GET /api/v1/admin/specialist-profiles?status=PENDING|APPROVED|REJECTED|SUSPENDED`
- `GET /api/v1/admin/specialist-profiles/{specialistAccountId}`
- `POST /api/v1/admin/specialist-profiles/{specialistAccountId}/approve`
- `POST /api/v1/admin/specialist-profiles/{specialistAccountId}/reject`
- `POST /api/v1/admin/specialist-profiles/{specialistAccountId}/suspend`
- `POST /api/v1/admin/specialist-profiles/{specialistAccountId}/restore`

## Implemented MB-362 endpoints

- `GET|POST /api/v1/availability-slots`
- `DELETE /api/v1/availability-slots/{slotId}`

## Implemented MB-363 owner endpoints

- `GET /api/v1/specialists`
- `GET /api/v1/specialists/{specialistAccountId}`

Discovery uses the existing profile, language/support-area, availability,
appointment-hold, and current-entitlement tables in one owner database; MB-363
requires no new persistence or migration. Every request rechecks current
`APPROVED` state and selectable slots. `FREE` receives `BROWSE_ONLY`, while
`PLUS`/`PREMIUM` receive an exact slot identity with
`BOOKING_POLICY_CHECK_REQUIRED`; MB-378 remains the booking authority and
revalidates all mutable state. Discovery policy v2 displays only authoritative
MB-364 rating aggregates and never fabricates a score.

## Implemented MB-377/MB-558 endpoint

- `GET /api/v1/service-credits`

## Implemented MB-515 endpoints

MB-622 replaces the active test offers with immutable PLUS 5,000/PREMIUM 10,000
VND sandbox versions. The owner-approved sandbox upgrade charges 10,000 VND
with zero offset and records `sandbox-full-price-v1` plus the nominal held-credit
value. Existing held appointment credits and ordinary/historical prices/offsets
are preserved. Mixed pricing policies fail closed. New paid credit periods
snapshot the exact purchased plan-version UUID and allocation. Legacy synthetic
sandbox earnings may use FAKE payout only; the prospective MB-812 verified-funded
exception below requires a new earning policy snapshot and exact PayOS evidence.
Apply migration 021 and drain old billing writers before enabling new offers.

Migration 022 permits a null checkout URL. Roll out the nullable-URL consumer
before the new checkout writer and drain old writers. Checkout commits its
canonical payment, quote and held credits before calling the provider outside
any database transaction. Only the first reservation calls create; same-key
replay returns the stored payment without re-sending a possible charge. A
provider timeout leaves an auditable pending record, recoverable by verified
IPN or existing expiry/reconciliation, never browser-side activation. There is
no automatic create retry or production payment/payout enablement in MB-622.
No runtime secret or production approval flag is changed by this story.

- `GET /api/v1/service-plans`
- `POST /api/v1/subscriptions/checkout`
- `POST /internal/v1/payments/momo/ipn`

The catalogue returns only currently effective immutable plan versions. Checkout
accepts the selected version and a user-scoped idempotency key; price, currency,
credit allocation, reservation limit, AI quota, and SupportPlan entitlement are
always loaded from Consultation-owned data. A verified, exactly matched MoMo IPN
activates the purchase or `PLUS`-to-`PREMIUM` upgrade once. Invalid, duplicate,
late, conflicting, or out-of-order callbacks cannot grant rights. Real checkout
remains disabled until the production approval flag, credentials, and HTTPS URLs
are all present. No downgrade or refund command is exposed.

## Implemented MB-378/MB-558 endpoints

- `GET /api/v1/bookable-slots`
- `GET|POST /api/v1/appointments`

## Implemented MB-380 endpoint

- `POST /api/v1/appointments/{appointmentId}/cancel`

## Implemented MB-364 endpoint

- `GET|PUT /api/v1/appointments/{appointmentId}/rating`

## Implemented MB-619 endpoints

- `GET|POST /api/v1/appointments/{appointmentId}/dispute`
- `GET|POST /api/v1/specialist/appointments/{appointmentId}/dispute`
- `GET /api/v1/admin/appointment-disputes?status=OPEN|RESOLVED`
- `POST /api/v1/admin/appointment-disputes/{disputeId}/resolve`

## Implemented MB-548 internal endpoint

- `GET /internal/v1/appointments/{appointmentId}/notification-eligibility?appointmentVersion={version}`

## Implemented MB-379 endpoints

- `GET /api/v1/specialist/appointments`
- `POST /api/v1/specialist/appointments/{appointmentId}/accept`
- `POST /api/v1/specialist/appointments/{appointmentId}/reject`
- `GET /internal/v1/appointments/{conversationId}/chat-eligibility` for participant-bound Realtime subscribe/send/history authorization

## Implemented MB-591 endpoint

- `GET /api/v1/specialist/dashboard`

The dashboard is a bounded, read-only projection of Consultation-owned
specialist profile, appointment, and availability facts. Counts retain exact
totals while item previews are capped at five. Every section and item includes
its source and server as-of time. Missing, pending, rejected, or suspended
profiles fail closed with blocked workload sections. The projection contains
no client identity, check-in, clinical risk, recovery/adherence, journal,
assessment-answer, chat-content, or private-note fields, and it performs no
cross-service database query.

## Implemented MB-588 endpoint

- `GET /api/v1/admin/appointments`

The query requires an explicit range of at most 180 days, caps pages at 100,
and may filter by appointment status, modality, user account, or specialist
account. Responses identify Consultation as the authoritative source and mark
the data state explicitly. The operation is available only to `ADMIN` and is
not a substitute for any owner or specialist command endpoint.

## Implemented MB-593 endpoint

- `GET /api/v1/specialist/analytics?from={instant}&to={instant}`

The authenticated specialist receives Consultation-owned operational facts for
an explicit half-open period, defaulting to the previous 30 days and capped at
366 days. A requested end up to 30 seconds ahead is clamped to Consultation's
server time to tolerate cross-service clock skew; materially future ends remain
invalid. Appointment lifecycle totals come from append-only transition facts,
so a later cancellation or reschedule does not erase an earlier request or
acceptance. Slot utilization uses slots scheduled in the period and preserves
whether a slot was ever accepted. Current MB-364 rating aggregates are marked
with their own as-of time. MB-516 earning and successful payout facts are
summed within the same period without demo amounts. Suspended specialists retain
their own historical analytics with an explicit `SUSPENDED` operational state;
profiles that were never approved receive blocked sections. No user identity,
health outcome, Journal, emotion, assessment, adherence, recovery, chat, or
private-note data is exposed.

## Implemented MB-596 endpoint

- `GET /api/v1/admin/product-journey-metrics?from={instant}&to={instant}`

The ADMIN-only projection defines a request cohort from appointments requested in the bounded half-open window. Confirmation and completion count only members of that cohort that reached the corresponding immutable lifecycle-history milestone before the window ended. It returns cohort counts and source timing/version only, with no participant, brief, summary, chat, clinical, or private-note fields.

## Implemented MB-592 endpoint

- `GET /internal/v1/specialist/client-relationships`

## Implemented MB-516 endpoints

- `GET /api/v1/specialist/earnings`
- `PUT /api/v1/specialist/payout-destination`
- `POST /api/v1/specialist/payouts`
- `GET /api/v1/admin/payouts`
- `POST /internal/v1/payouts/momo/ipn`

Evidence-backed `COMPLETED` and exact credit `CONSUMED` create one immutable
210,000 VND earning snapshot from the fixed 300,000 VND allocation. Earnings
remain pending for seven days. Local/CI payout is deterministic and fake;
destinations are encrypted, withdrawal amount is always server-derived, the
minimum is 100,000 VND, and one specialist may request at most one payout per
day. The MoMo route verifies signed sandbox callbacks only; it does not enable
real transfer submission.

## MB-787 specialist bank payouts

The accepted [ADR 0047 payout amendment](../docs/adr/0047-payos-active-gateway-momo-dormant.md#mb-787-payout-amendment-2026-11-04)
permits independently ready PAYOS payout on the approved staging deployment.
Complete separate payout credentials, valid existing encryption configuration
and the official HTTPS provider origin are required. Collect readiness does
not depend on payout credentials. Existing attempts retain their original
provider for authenticated queries even if the configured submission mode
changes. Production remains blocked; synthetic fixture earnings cannot be
redeemed through either real provider. MB-788 owns funded live interoperability.

The existing specialist destination PUT preserves bankCode/accountReference/
accountHolderName. PAYOS requires a six-digit listed bank BIN; the account and
holder stay encrypted. Any material edit, including holder-only edits or
returning to an older account, creates a new current pending revision. Existing
verified history cannot bypass confirmation. An exact unchanged save is a
no-op. Lists, specialist earnings and payout history expose only masked hints.

Additional provider operations are:

- `GET /api/v1/specialist/payout-banks`: SPECIALIST-only backend VietQR proxy,
  returning BIN/name/shortName with a 24-hour in-process cache; no fresh list
  on upstream failure returns 503 without hiding saved destinations.
- `GET /api/v1/admin/payout-destinations`: masked current revision queue,
  default PENDING_CONFIRMATION, limit 50 and offset 0.
- `GET /api/v1/admin/payout-destinations/{destinationId}`: audited ADMIN-only
  exact account/holder inspection for manual checking, returned with no-store.
- `POST /api/v1/admin/payout-destinations/{destinationId}/confirm`: keyed ADMIN
  confirmation of expectedVersion with reasonCode ACCOUNT_HOLDER_CHECKED.
- `POST /api/v1/admin/payouts/{payoutId}/retry`: keyed ADMIN retry naming the
  latest definitely FAILED expectedAttemptId; retains original provider,
  unchanged confirmed destination and frozen earning lineage.

Manual confirmation applies to the exact current revision and creates no
transfer. Subsequent specialist-initiated eligible withdrawals to that account
dispatch automatically; no per-withdrawal admin approval is added. The server
derives the full amount from eligible unlinked earnings and retains the
baseline seven-day hold, 100,000 VND minimum and one logical request per server
day. MB-812 below permits only the explicit funded staging exception to the
minimum/hold and synthetic exclusion.
Failed-payout earnings remain linked to their original logical payout even
when AVAILABLE and appear as retryPendingVnd, excluded from new withdrawals.

Persist an immutable provider attempt/key before submitting one validated
one-item PayOS batch through the pinned SDK, explicitly passing the UUIDv4
x-idempotency-key. SDK timeout/retry/logging/redirect settings match collect.
Outer batch ID/reference and child attempt reference are distinct. Only signed
authenticated matched child SUCCEEDED evidence pays; outer approval and
balance are not proof. Known definite rejection, transfer-balance insufficiency
and service-credit insufficiency are safe stored failures for operator action.
Timeout or possible acceptance preserves uncertainty and reserved funds.

Reconciliation queries the stored provider outside transactions with persisted
schedule/lease, bounded batches and 5s/15s/30s/60s/2m/5m backoff. At the absolute
24-hour horizon, stop automatic polling and retain UNKNOWN/NEEDS_REVIEW plus
reserved funds. No automatic resubmission or payout webhook is introduced.
Specialist replay observes the original outcome, including FAILED; only
explicit provider-affine ADMIN retry can create the next attempt after
revalidating original confirmation, eligibility, hold/dispute and fixture gates.
The owner funds provider balance/service credits manually.

Deploy the additive owner migration before new writers, synchronize consumers
from Consultation OpenAPI 1.21.0 and drain legacy writers before PAYOS is
enabled. Preserve MoMo history/IPNs and old FAKE/MOMO verification semantics.
Local tests use SDK fixtures and disposable PostgreSQL only; never query real
provider balance, submit real payouts or migrate shared databases as a gate.

The endpoint returns appointment/user identifiers, status, modality, exact
schedule, appointment version, projection time/window, and policy version only.
Suspended, rejected, pending, or missing specialist profiles fail closed and
every list attempt records a content-free audit fact.

Availability accepts only exact future 60-minute `IN_APP_CHAT` and gated
`IN_APP_VIDEO` slots. It stores UTC instants and an IANA display timezone,
rejects active overlap transactionally, and retains withdrawn slots as
tombstones. Practice locations, phone calls, external meeting links,
recurrence, time off, booking, and video-room authorization are not part of
this flow.

Updates and decisions use the returned `ETag` in `If-Match`. Editing a
submitted pending profile withdraws it from the review queue until the
specialist explicitly submits it again. Rejected profiles retain their reason
while being edited and require explicit resubmission. Approved and suspended
profiles cannot be directly edited. MB-635 permits approved specialists to use a
separate reviewed amendment without changing the current public profile.
Rejection and suspension accept only their documented
stable reason sets. A suspension response reports the exact number of future
slots withdrawn, appointments cancelled, and credits released.

## Private specialist credential review

Credential documents are a separate Consultation-owned review flow: specialists
list and upload private PDF/JPEG/PNG files through `/api/v1/specialist-profile/documents`,
open originals through five-minute signed links, and enable a public badge only
after ADMIN verification. ADMIN uses `/api/v1/admin/specialist-profiles/documents`
to list pending documents, inspect an original, and verify or reject with an
ETag. Originals and Cloudinary keys never appear in discovery. Set
`CONSULTATION_CREDENTIAL_STORAGE_ENABLED=true` and the four
`CONSULTATION_CLOUDINARY_*` values to enable upload and original-file access;
reads and profile edits remain available when storage is disabled.
Apply Consultation migration `026-specialist-language-and-credentials.sql`
before enabling the feature. The Compose application container has Liquibase
disabled, so its database migration must run through the deployment migration
step before that container is restarted.

## MB-635 reviewed profile amendments

- `GET /api/v1/specialist-profile/amendments/current`: live profile plus latest private amendment.
- `POST /api/v1/specialist-profile/amendments`: start/reuse a draft with the live profile ETag.
- `PUT /api/v1/specialist-profile/amendments/{amendmentId}`: save the six proposed fields.
- `POST /api/v1/specialist-profile/amendments/{amendmentId}/submit|resubmit`: explicit review submission.
- `POST /api/v1/specialist-profile/amendments/{amendmentId}/cancel`: owner cancellation of an open amendment, retaining audit and public content.
- `GET /api/v1/admin/specialist-profiles/amendments?limit=50&page=0`: bounded pending-review queue, separate from initial submissions.
- `GET /api/v1/admin/specialist-profiles/amendments/{amendmentId}`: proposed versus approved values.
- `POST /api/v1/admin/specialist-profiles/amendments/{amendmentId}/approve|reject`: version-checked ADMIN decision.

Commands other than start use the amendment ETag. Approval and the immutable
approved snapshot/history commit together. Rejection and draft operations never
change the live profile, appointments or availability. Suspension is serialized
with these commands and blocks them. Review provenance includes each exact
proposed payload; no unrestricted review notes or verification documents are stored.

Apply migration 018 before owner rollout. It backfills one approved-content
baseline for existing approved/suspended profiles without inventing older content
versions. Drain old owner instances before enabling amendment writes. Never run
the documentation logical schema against a database. See
[ADR 0033](../docs/adr/0033-reviewed-specialist-profile-amendment.md).

The follow-up cancellation requires migration 019 before the updated owner
starts and the 1.12.0 consumer to be deployed alongside it. `CANCELLED` is
terminal; a new edit starts a fresh amendment from the public snapshot. The
published profile and approved history are not reverted or deleted.

Focused owner verification:

```powershell
.\mvnw.cmd '-Dtest=ProfileAmendmentIntegrationTests,SpecialistProfileFlowIntegrationTests,ConsultationOpenApiContractTests,AccountLifecycleConsumerTests' test
```

## Run and test

```powershell
.\mvnw.cmd spring-boot:run
.\mvnw.cmd test
```

The generated context test uses PostgreSQL and Kafka Testcontainers and disables
live Eureka registration. The appointment relay remains disabled unless its
versioned topic has been provisioned.


MB-787 failed-destination recovery amendment (2026-11-04): an explicit keyed
ADMIN POST `/api/v1/admin/payouts/{payoutId}/close-failed` checks the latest
FAILED/COMPLETE attempt and fixed DESTINATION_UNUSABLE reason, records
`closed_at`/`closed_by`, releases active item ownership and audits
`consultation.payout.closed` / `PAYOUT_CLOSED` atomically without a provider call.
The payout remains FAILED and its original destination, provider, amount and
item history stay immutable. `closedAt` is exposed on specialist/admin payout
projections and forbids retry. Partial unique active earning ownership preserves
one live allocation while retaining released historical links. The next
withdrawal uses the current confirmed destination and all normal eligibility,
minimum and daily-cap gates; closure never releases UNKNOWN/active/review money.
Migration `20261104090000-close-failed-payout.sql` must precede new writers, and
legacy writers must be drained before closure is enabled. Claude must sync the
contract and add the admin action; MB-788 still owns live interoperability.

## MB-812 funded staging demo payout

Approved authority is [ADR 0032](../docs/adr/0032-payment-and-specialist-payout-baseline.md#mb-812-funded-staging-demo-amendment-2026-11-04)
and [ADR 0047](../docs/adr/0047-payos-active-gateway-momo-dormant.md#mb-812-funded-staging-demo-amendment-2026-11-04).
Production/default rules retain 300,000 VND nominal allocation, 210,000 VND
earning, at least 100,000 VND withdrawal and P7D hold. The flag defaults false
and is effective only with `staging` and neither `prod` nor `production` active.
All synthetic baseline/FAKE/legacy earnings remain excluded from real payout.

New completed `sandbox-full-price-v1` credits can instead create
`staging-demo-paid-v1` earnings with a `funding_payment_id` only when their
exact original PAID period, frozen purchased plan and succeeded PAYOS payment
match a processed successful verified receipt. A later upgrade does not change
an older held credit's plan or funding. Allocation is
`floor(actual collected VND / frozen plan credits)`, then specialist amount is
`floor(allocation * 7000 / 10000)`. Both round down to whole VND; remainders
are not distributed and nominal period allocation is unchanged. No existing
earning is revalued or made redeemable retrospectively. Prepare and explicit
retry recheck the effective staging gate, PAYOS payout and exact funded lineage.

For later authorized staging activation, set these non-secret values:

| Environment name | Value |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `staging` (no `prod`/`production`) |
| `CONSULTATION_SPRING_PROFILES_ACTIVE` | `staging` (when using the existing Compose mapping) |
| `SPRINGDOTENV_ENABLED` | `false` |
| `CONSULTATION_BILLING_MODE` | `PAYOS` |
| `CONSULTATION_PAYOUT_MODE` | `PAYOS` |
| `CONSULTATION_PAYOUT_STAGING_DEMO_ENABLED` | `true` |
| `CONSULTATION_PAYOUT_MINIMUM_WITHDRAWAL_VND` | `2000` |
| `CONSULTATION_PAYOUT_SETTLEMENT_HOLD` | `PT10M` |

Existing independent collect/payout credentials, approved HTTPS URLs, encryption
and exact current ADMIN-confirmed bank revision remain required. Apply additive
`20261104100000-staging-demo-payout.sql` before new writers and drain older
earning/payout writers before enabling the exception. Its positive database
floor permits small staging amounts; service validation preserves the configured
minimum in every environment. No shared database or provider action is part of
this implementation job.

| Purchase | Per completed session | Two sessions | First qualifying balance at 2,000 minimum | All credits |
| --- | ---: | ---: | ---: | ---: |
| PLUS 5,000 VND / four credits | 875 | 1,750 | Three: 2,625 | 3,500 |
| PREMIUM 10,000 VND / ten credits | 700 | 1,400 | Three: 2,100 | 7,000 |

The pinned offline SDK does not establish the PayOS payout minimum. 2,000 VND
is the owner-directed fallback; MB-788 must confirm provider acceptance before
live money movement. Neither one nor two approved demo sessions reaches that
fallback, so QA requires three. PT10M only shortens the hold; dispute, daily
cap, destination confirmation, idempotency, polling and close/retry stay intact.

The API returns dynamic minimum/hold and optional exact `settlementHoldSeconds`;
`settlementHoldDays` remains integer whole days (PT10M reports zero). Configured
holds must be positive whole seconds. Web
follow-up is required before screen-based QA: synchronize the owner contract,
remove fixed 100000/7 validator constants and display sub-day hold accurately.
No mobile earnings/payout surface is affected. Automated evidence uses only
Testcontainers and synthetic provider transport; MB-788 owns live evidence.

## MB-813 definite PayOS submission rejection

An HTTP 401 or 403 from the initial PayOS payout submission is a definite
access rejection: store `FAILED / PAYOUT_PROVIDER_ACCESS_DENIED` and reconciliation
`COMPLETE`. The existing terminal-failure transaction returns reserved earnings
to AVAILABLE while retaining their original payout ownership; only the existing
explicit ADMIN retry can create another attempt after all original gates pass.
This classification relies on one submission per persisted attempt, disabled SDK
and HTTP retries/redirects, and command replay observing the original attempt.

Query authentication failures and an empty authenticated `list(referenceId)`
remain UNKNOWN with funds reserved. The [published PayOS API](https://payos.vn/docs/api/)
defines the reference filter and separate idempotency header without a bounded
list-visibility guarantee; no grace window proves non-creation. The existing
24-hour UNKNOWN/NEEDS_REVIEW behavior and prohibition on retrying uncertainty
remain in force. No manual resolution endpoint is added.

Provider diagnostics record operation, payout/attempt UUIDs, HTTP status and a
bounded sanitized PayOS code. They exclude exception messages, provider text,
payloads, credentials and bank details. HTTP status and code are available from
the pinned [SDK APIException](https://raw.githubusercontent.com/payOSHQ/payos-lib-java/v2.0.1/src/main/java/vn/payos/exception/APIException.java).
The existing OpenAPI `failureCode` string accepts the new safe value; response
shapes, consumers and generated contracts require no change. MB-813 has no
migration or web slice and deploys only consultation. MB-787/MB-812 eligibility,
destination confirmation, provider affinity and exact signed-child proof remain
unchanged; MB-788 continues to own funded live interoperability.
