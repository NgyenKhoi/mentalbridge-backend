# PostgreSQL Data Model

The cross-schema [database/postgresql/001_initial_schema.sql](../../database/postgresql/001_initial_schema.sql) is a non-executable, whole-system modelling artifact. Its schemas only make ownership and relationships readable in one file; it must never be run to provision any environment. Each module instead owns a separate PostgreSQL database and uses that database's default `public` schema. When implementation begins, the service's owner-specific migration history becomes its only executable database source of truth: Liquibase for Spring or `node-pg-migrate` for Node.js. The purpose of every conceptual table and field is explained in the human-readable [PostgreSQL field data dictionary](postgresql-field-data-dictionary.md).

## Ownership

| Service database | Owner | Main aggregates |
| --- | --- | --- |
| `mentalbridge_identity` | Identity Service | account, role, refresh session, verification/reset token |
| `mentalbridge_care` | Care Service | user profile, consent, assessment, risk, intervention, follow-up |
| `mentalbridge_consultation` | Consultation Service | specialist approval, plan/subscription/payment/upgrade, credit ledger, availability, appointment, earning/provider payout, review |
| `mentalbridge_content_notification` | Content/Notification Service | resource, hotline, notification preference/delivery |
| owner-local tables | each producer; Governance reads safe events | outbox, audit, deletion workflow, retention policy |
| `mentalbridge_journal_ai` | Journal/AI Service | analysis job metadata, dataset/benchmark metadata |

ADR 0005 assigns the billing bounded context to Consultation Service so subscription/payment/upgrade, credit reservation, appointment completion, and earning creation can use one local transaction. The baseline stores exact minor-unit snapshots and append-only histories; no other service may store a shadow financial balance. Provider credentials/contracts, VND pricing or a versioned FX policy, settlement delay, chargeback reconciliation, and retention must be finalized before real-money payout is enabled.

Cross-schema foreign keys in the logical baseline only make relationships visible. Executable service migrations replace them with immutable external UUIDs and validate through APIs/events. Do not emulate distributed joins on request paths.

## Shared column policy

- UUID primary keys are generated server-side using `gen_random_uuid()`.
- `created_at`/`updated_at` are UTC `timestamptz`; application code must update `updated_at`.
- `version` supports optimistic locking on concurrently edited aggregates.
- Status fields use `varchar` plus `CHECK`, allowing controlled migrations without PostgreSQL enum coupling.
- Email uses `citext` for case-insensitive uniqueness.
- Sensitive free text is absent from relational tables unless required for querying. Journal and chat bodies live in MongoDB; documents live in private object storage.
- Foreign keys use restrictive deletion by default. User deletion is orchestrated explicitly rather than cascaded accidentally.

## Key integrity decisions

### Identity

- `account.email` is unique and normalized by `citext`.
- Refresh tokens and one-time tokens store hashes only.
- Each account stores exactly one immutable `role_code`. Public accounts are `USER` or `SPECIALIST`; a partial unique index permits at most one dedicated, operator-provisioned `ADMIN` account. Runtime role promotion and replacement are not supported.

### Consent

- `consent_decision` records the legal/policy text version and evidence of each platform-level decision.
- `specialist_access_grant` is an explicit user-to-specialist grant with purpose and expiry.
- `specialist_access_scope` stores allowed scopes; selected journal IDs belong in `specialist_journal_access` because journal documents are external.
- Revocation timestamps preserve history while current authorization checks require `revoked_at IS NULL` and non-expired grants.

### Assessment and risk

- Definitions/questions are versioned and published immutably.
- Submission answers are constrained to 0..3 and unique per question.
- Stored total score is authoritative only after server validation; `scoring_version` records the algorithm.
- Risk results store policy version, reason codes and exact source IDs to make decisions reproducible.

### Booking

- Availability uses `[start_at, end_at)` semantics and validates start before end.
- A specialist publishes discrete slots from their working schedule in local time plus IANA timezone; the server converts to UTC and must validate the approved standard duration once defined. Booking copies start, end, timezone, and channel into the appointment; those snapshots do not move if the source slot is later edited.
- An exclusion constraint prevents overlapping active slots for the same specialist.
- A partial unique index permits only one active appointment per slot.
- A second partial unique index permits only one active appointment per credit; booking locks the slot and credit together.
- `appointment_status_history` provides an auditable state-transition timeline.
- `IN_APP_CHAT` is the only initially enabled channel. Join/send authorization is limited to `[scheduled_start_at, scheduled_end_at)`; conversation history may remain readable outside the window.
- `IN_APP_VIDEO` is reserved as a planned channel but cannot be enabled until a later call/signalling/provider/security contract is accepted. Slot/appointment tables contain no physical location, phone, or external meeting link.

### Subscription and settlement

- Published plan versions are immutable and store price, non-consultation allocation, per-credit allocation, credit count, and specialist share in integer minor units/basis points.
- A verified, deduplicated payment webhook activates a period and grants one credit row per entitlement exactly once.
- MoMo is the only production payment provider. Payment rows retain `orderId`, `requestId`, positive `transId`, `resultCode`, `payType`, and exact/parsed `responseTime` separately; `FAKE` is local/CI only and exercises the same shape.
- `momo_payment_ipn` snapshots the full required non-sensitive contract, contract/key versions and hashes of the payload plus sensitive/free-text signed fields. Its deterministic SHA-256 tuple key provides replay protection.
- `safe_optional_details` stores only versioned allow-listed non-sensitive optional MoMo fields. Raw IPN/signature, decoded `orderInfo`/`extraData`, and wallet identifiers are never persisted.
- Available credit count is derived from authoritative credit rows. The append-only ledger records reservations, releases, upgrade holds, consumption, expiry, forfeiture, and revocation.
- Care-to-Plus upgrade records actual total/remaining period seconds, rounded-down feature residual, held-credit value, offset, and amount due. Held credits prevent booking/upgrade double use.
- Downgrade and user-initiated refund are not represented. Provider chargeback remains an external reconciled payment outcome.
- Cancellation disables paid features immediately, cancels future appointments, and revokes their credits. A confirmed appointment already inside its scheduled window is the sole exception and may finish at its snapshotted end instant.
- Appointment completion creates one earning snapshot. The MoMo payout flow attaches available earnings to one idempotent logical payout and keeps each provider attempt separately. A definite failure may create a numbered retry; an `UNKNOWN` attempt is queried and blocks another transfer attempt.
- MoMo Disbursement is the only planned production payout provider, subject to M4B credentials. Local/CI uses a deterministic MoMo-shaped fake. Real payment/payout remains disabled while plan/earning currency is USD and no approved VND plan version or FX policy exists.

### Operations

- Each service writes its own local `outbox_event` row in the same transaction as its aggregate change.
- `audit_event` is append-only to application roles and must not store journal/chat bodies.
- Deletion tasks track completion per data owner and make retries idempotent.

## Recommended indexes and partitioning

The initial script contains query-driven indexes for account lookup, histories, active grants, slots, appointments, notifications, jobs, audit, and outbox polling. Add indexes only from observed query plans.

At capstone scale, do not partition by default. Consider monthly range partitioning only for `platform.audit_event`, `platform.outbox_event`, and high-volume notification delivery after measuring volume. Partitioning does not replace retention/deletion.

## Data not stored in PostgreSQL

- raw journal revisions and structured provider responses: MongoDB;
- conversation messages and receipts: MongoDB;
- evaluation dataset files and private chat attachments: private object storage; specialist verification documents are not collected;
- provider secrets: secret manager/environment injection;
- rate-limit counters, WebSocket presence/routing/fan-out, short-lived delivery/idempotency state, and expiring hashed OTP challenges: Redis; none is authoritative business data and Redis is not used to cache database-query results.

## Migration rules

1. Provision the owner database, then implement each logical baseline area in the owner-specific Liquibase or `node-pg-migrate` history before application implementation.
2. Apply expand/migrate/contract for changes used by multiple deployed versions.
3. Never edit an applied migration; add a new migration.
4. Seed questionnaire definitions, intervention templates, and resources through versioned reference-data migrations using reviewed content.
5. Use synthetic development data only.
6. Every table and column has a useful entry in `postgresql-field-data-dictionary.md`. The same change that adds or changes a field must explain its business purpose, ownership, sensitivity, nullability, and consistency role where applicable.
