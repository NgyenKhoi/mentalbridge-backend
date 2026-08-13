# PostgreSQL Data Model

The executable baseline is [database/postgresql/001_initial_schema.sql](../../database/postgresql/001_initial_schema.sql). The purpose of every current table and field is explained in the human-readable [PostgreSQL field data dictionary](postgresql-field-data-dictionary.md). When services are scaffolded, split the DDL into service-owned Liquibase changelogs and database users, and keep the data dictionary synchronized.

## Ownership

| Schema | Owner | Main aggregates |
| --- | --- | --- |
| `identity` | Identity Service | account, role, refresh session, verification/reset token |
| `care` | Care Service | user profile, consent, assessment, risk, intervention, follow-up |
| `consultation` | Consultation Service | specialist, verification, availability, appointment, review |
| `content` | Content Service | resource, hotline, notification preference/delivery |
| `platform` | each producer; Governance reads | outbox, audit, deletion workflow, retention policy |
| `ai` | Journal/AI Service | analysis job metadata, dataset/benchmark metadata |

The updated project-tracking workbook requires subscriptions, payments, consultation credits, specialist earnings, and payouts, but the executable baseline contains no authoritative financial schema. Do not add these facts to an existing schema until an ADR establishes their bounded-context owner, immutable ledger, provider/webhook, booking compensation, settlement, reconciliation, and retention rules.

Cross-schema foreign keys in the baseline make invariants visible. In independently deployed databases, replace them with immutable external UUIDs and validate through APIs/events. Do not emulate distributed joins on request paths.

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
- Roles are many-to-many so an administrative role is not encoded as mutable profile text.

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
- An exclusion constraint prevents overlapping active slots for the same specialist.
- A partial unique index permits only one active appointment per slot.
- `appointment_status_history` provides an auditable state-transition timeline.

### Operations

- Each service writes its own `platform.outbox_event` row in the same transaction as its aggregate change. In a separated deployment, each database owns an equivalent outbox table.
- `audit_event` is append-only to application roles and must not store journal/chat bodies.
- Deletion tasks track completion per data owner and make retries idempotent.

## Recommended indexes and partitioning

The initial script contains query-driven indexes for account lookup, histories, active grants, slots, appointments, notifications, jobs, audit, and outbox polling. Add indexes only from observed query plans.

At capstone scale, do not partition by default. Consider monthly range partitioning only for `platform.audit_event`, `platform.outbox_event`, and high-volume notification delivery after measuring volume. Partitioning does not replace retention/deletion.

## Data not stored in PostgreSQL

- raw journal revisions and structured provider responses: MongoDB;
- conversation messages and receipts: MongoDB;
- verification files and evaluation dataset files: private object storage;
- provider secrets: secret manager/environment injection;
- rate-limit counters, WebSocket presence/routing/fan-out, short-lived delivery/idempotency state, and expiring hashed OTP challenges: Redis; none is authoritative business data and Redis is not used to cache database-query results.

## Migration rules

1. Split this baseline into one Liquibase changelog history per service before implementation.
2. Apply expand/migrate/contract for changes used by multiple deployed versions.
3. Never edit an applied migration; add a new migration.
4. Seed questionnaire definitions, intervention templates, and resources through versioned reference-data migrations using reviewed content.
5. Use synthetic development data only.
6. Every table and column has a useful entry in `postgresql-field-data-dictionary.md`. The same change that adds or changes a field must explain its business purpose, ownership, sensitivity, nullability, and consistency role where applicable.
