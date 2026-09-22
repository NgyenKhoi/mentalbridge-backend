# PostgreSQL Data Model

Start with the [canonical domain model](../domain-model/README.md) and its
[PostgreSQL logical schema](../domain-model/relational/postgresql-logical-schema.sql)
for entities, ownership, and diagrams. That SQL is read-only documentation and
must never be executed. Each module owns a separate PostgreSQL database and its
default `public` schema; the service's migration history is the only executable
runtime source of truth. Exact field purposes are documented in the
[PostgreSQL field data dictionary](postgresql-field-data-dictionary.md).

## Ownership

| Service database | Owner | Main aggregates |
| --- | --- | --- |
| `mentalbridge_identity` | Identity Service | account, role, refresh session, verification/reset token |
| `mentalbridge_care` | Care Service | user profile, consent, anonymous session, questionnaire, assessment result, safety, SupportEvaluation, Support Guide, SupportPlan lifecycle/activity occurrences |
| `mentalbridge_consultation` | Consultation Service | specialist approval, current service entitlement, online availability, service-credit periods and ledger; payment/booking/settlement remain proposed |
| `mentalbridge_content_notification` | Content/Notification Service | reviewed resource definitions, immutable exact-version eligibility provenance, and notification preference/delivery |
| owner-local tables | each producer; Governance reads safe events | implemented owner outbox/audit tables only; deletion/retention projections remain proposed |

ADR 0005 assigns billing to Consultation; ADR 0017 amends the v2 catalogue to
`FREE`/`PLUS`/`PREMIUM` and real money to VND/MoMo only. MB-377 implements the
credit period, indivisible credit, and append-only transition ledger derived
from the current entitlement read model. Payment and subscription tables remain
`PROPOSED`. Exact VND prices, fixed
per-credit `creditAllocation`, provider credentials/contracts, settlement,
chargeback reconciliation, and retention must be finalized before real money
is enabled; runtime FX is prohibited.

Cross-owner identifiers in the canonical logical model make relationships visible but are not physical foreign keys. Executable service migrations store immutable external UUIDs and validate through APIs/events. Do not emulate distributed joins on request paths.

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

### Assessment, safety, and support

- The MB-88 executable Care migration separates questionnaire definitions, questions, score bands, submissions, answers, and results so client answers cannot become authoritative scoring fields.
- Definitions/questions are versioned; one published definition per instrument/locale is selected as current, and each version retains its source reference and scoring identity.
- Submission ownership is exclusive: one authenticated Care profile or one isolated anonymous session, never both. Anonymous sessions store a token hash and expiry but no account/claim field; anonymous submissions carry a required retention deadline.
- Submission answers are constrained to 0..3, unique per question, and use composite foreign keys so every answer belongs to the submission's exact questionnaire definition.
- Stored total score and screening band live in the one-to-one result and are authoritative only after server validation; `scoring_version` records the algorithm, `safety_item_positive` preserves the questionnaire fact, and the paired safety status/policy version records the independent response decision.
- Support-tier results store policy version, reason codes and exact source IDs to make decisions reproducible; safety status remains a separate assessment result.
- Existing `mb-support-routing-capstone-v1` rows remain immutable coarse evaluations. They preserve PHQ-9 and GAD-7 evidence separately and are not a global severity or sufficient plan-eligibility decision.
- Resource Eligibility v1 is Content-owned, append-only and exact-versioned under #50. Domain-aware SupportEvaluation v2 is additive Care-owned persistence under #48. MB-372 adds deterministic SupportPlan draft persistence/reload; MB-373 adds admitted-choice replacement with optimistic concurrency, audited idempotent activation, exact revalidation, and one-current-plan enforcement. MB-513 adds explicit lifecycle commands plus deterministic local-time schedules and persisted occurrences. MB-374 adds an optional constrained completion-reason code and a partial owner/time index for immutable terminal-plan history; it does not store free-text completion notes. MB-376 adds constrained mutable engagement fields to exact occurrences and a minimized atomic outbox fact; private reflection stays out of that event. None of these migrations rewrites historical v1 evaluation or reviewed-resource rows.

### Booking model status

- Availability is `ACTIVE`; later booking/appointment entities in this section are approved `PROPOSED` models until an owner migration exists.
- Availability uses `[start_at, end_at)` semantics and validates start before end.
- An approved specialist publishes discrete 60-minute online slots as UTC instants plus an IANA display timezone. MB-362 stores no PracticeLocation, phone, or external meeting link. A later booking flow snapshots start, end, timezone, and modality without mutating the source slot.
- An exclusion constraint prevents overlapping active slots for the same specialist.
- A partial unique index permits only one active appointment per slot.
- A second partial unique index permits only one active appointment per credit; booking locks the slot and credit together.
- `appointment_status_history` provides an auditable state-transition timeline.
- Historical v1 may retain `IN_PERSON` appointment provenance. New MB-362 slots
  accept only `IN_APP_CHAT` and capability-gated `IN_APP_VIDEO`; video session
  runtime remains unavailable until its detailed provider contract passes.
- At `scheduled_end_at`, v2 records `SESSION_ENDED` and closes the channel.
  Separate accepted server/provider evidence is required for `COMPLETED`.
- Appointment persistence must retain evidence, dispute,
  `SessionSummary`/`AgreedNextSteps`, reuse approval, and PlanChangeRequest
  provenance without rewriting historical snapshots.

### Subscription and settlement — PROPOSED

- Published plan versions are immutable and store price, non-consultation allocation, per-credit allocation, credit count, and specialist share in integer minor units/basis points.
- A verified, deduplicated payment webhook activates a period and grants one credit row per entitlement exactly once.
- MoMo is the only production payment provider. Payment rows retain `orderId`, `requestId`, positive `transId`, `resultCode`, `payType`, and exact/parsed `responseTime` separately; `FAKE` is local/CI only and exercises the same shape.
- `momo_payment_ipn` snapshots the full required non-sensitive contract, contract/key versions and hashes of the payload plus sensitive/free-text signed fields. Its deterministic SHA-256 tuple key provides replay protection.
- `safe_optional_details` stores only versioned allow-listed non-sensitive optional MoMo fields. Raw IPN/signature, decoded `orderInfo`/`extraData`, and wallet identifiers are never persisted.
- Available credit count is derived from authoritative credit rows. The append-only ledger records reservations, releases, upgrade holds, consumption, expiry, forfeiture, and revocation.
- `PLUS`-to-`PREMIUM` upgrade records actual total/remaining period seconds,
  rounded-down feature residual, held-credit value, offset, and amount due in
  VND. Held credits prevent booking/upgrade double use.
- Downgrade and user-initiated refund are not represented. Provider chargeback remains an external reconciled payment outcome.
- Cancellation disables paid features immediately, cancels future appointments, and revokes their credits. A confirmed appointment already inside its scheduled window is the sole exception and may finish at its snapshotted end instant.
- Evidence-backed appointment completion consumes one credit and creates one
  earning snapshot equal to 70% of its fixed `creditAllocation`.
  `SESSION_ENDED`, cancellation, no-show, and dispute create no earning.
- MoMo Disbursement is the only planned production payout provider, subject to M4B credentials. Local/CI uses a deterministic MoMo-shaped fake. Real payment/payout remains disabled while plan/earning currency is USD and no approved VND plan version or FX policy exists.

### Operations model status

- Implemented owner-local outbox/audit tables remain service-specific.
- Generic platform audit, deletion, retention, and moderation tables are `PROPOSED`; no shared platform database exists.

## Recommended indexes and partitioning

Owner migrations contain query-driven indexes for implemented lookups, histories, slots, notifications, jobs, audit, and outbox polling. Add indexes only from observed query plans.

At capstone scale, do not partition by default. Consider owner-local partitioning only for measured high-volume audit, outbox, or notification histories. Partitioning does not replace retention/deletion.

## Data not stored in PostgreSQL

- raw journal revisions and structured provider responses: MongoDB;
- conversation messages and receipts: MongoDB;
- evaluation dataset files and private chat attachments: private object storage; specialist verification documents are not collected;
- provider secrets: secret manager/environment injection;
- rate-limit counters, WebSocket presence/routing/fan-out, short-lived delivery/idempotency state, and expiring hashed OTP challenges: Redis; none is authoritative business data and Redis is not used to cache database-query results.

## Migration rules

1. Provision the owner database, then implement each approved area in the owner-specific Liquibase or current Node.js migration history before application implementation.
2. Apply expand/migrate/contract for changes used by multiple deployed versions.
3. Never edit an applied migration; add a new migration.
4. Seed questionnaire definitions and reviewed resources through versioned reference-data migrations; introduce any future template entity only with an approved owner migration and canonical-model update.
5. Use synthetic development data only.
6. Every table and column has a useful entry in `postgresql-field-data-dictionary.md`. The same change that materially changes persisted domain structure must also update `docs/domain-model/relational/postgresql-logical-schema.sql` while preserving migrations as runtime truth.
