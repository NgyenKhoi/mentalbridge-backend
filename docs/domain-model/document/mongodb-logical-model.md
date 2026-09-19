# MongoDB canonical logical model

This document is the canonical domain-level index for MongoDB-owned data. The
executable source of truth remains each owner's `migrate-mongo` history and
collection validators. Detailed field examples and index rationale remain in
[MongoDB collection definitions](../../database/mongodb.md).

MongoDB relationships below are logical identifiers or embedded-document
ownership. MongoDB does not enforce foreign keys, and cross-service identifiers
never authorize access by themselves.

## Journal/AI active model

### Journal Entry — ACTIVE

Collection: `journal_entries`

Key fields: `_id`, `ownerAccountId`, `clientEntryId`, `currentRevision`,
`occurredAt`, lifecycle timestamps, `deleted`, `analysisState`, `tags`,
`revisions`, `commands`, and deterministic cursor keys.

Relationships:

- embeds 1..200 encrypted Journal Revisions;
- embeds bounded idempotent command replay records;
- `ownerAccountId` is a logical/external Identity account reference;
- Analysis Job and Journal Analysis Result refer to an exact entry/revision by
  identifier, without a physical MongoDB foreign key.

### Journal Revision — ACTIVE

Storage: embedded in Journal Entry.

Key fields: `revision`, `createdAt`, encrypted content envelope, optional
encrypted mood envelope, byte length, keyed content digest, and analysis
invalidation time.

### Analysis Job — ACTIVE

Collection: `analysis_jobs`

Key fields: owner, exact journal/revision, keyed idempotency data, lifecycle,
attempt/lease state, terminal reason, result ID, immutable routing snapshot, and
timestamps. The end-user bearer credential is never persisted.

Relationships:

- logical same-owner reference to Journal Entry and exact revision;
- logical result reference to Journal Analysis Result;
- entitlement provenance is a snapshot of a Consultation decision, not a
  database relationship.

### Journal Analysis Result — ACTIVE

Collection: `journal_analysis_results`

Key fields: analysis/job/source IDs, owner, exact revision, provider/model/
prompt/schema and entitlement-routing provenance, normalized bounded result,
usage/cost metrics, latency, and creation time. Raw provider output and hidden
reasoning are not fields.

### Emotion Check-In — ACTIVE

Collection: `emotion_check_ins`

Key fields: owner/local date/timezone, optimistic revision, encrypted bounded
revisions, idempotent command records, deletion tombstone, and purge time.

### Benchmark Dataset — ACTIVE

Collection: `benchmark_datasets`

Key fields: dataset/version, workload, language, synthetic source, license,
content digest, case count, and registration time. Raw cases remain governed
version-controlled input.

### Benchmark Run — ACTIVE

Collection: `benchmark_runs`

Key fields: exact dataset provenance, prompt/schema, candidates, lifecycle,
aggregate quality/safety/latency/token/cost evidence, and timestamps.

### Benchmark Case Result — ACTIVE

Collection: `benchmark_case_results`

Key fields: run/case/provider/model identity, status/error classification,
quality/safety result, matched evidence counts, execution metrics, normalized
output, and creation time.

## Realtime active model

### Conversation — ACTIVE

Collection: `conversations`

Key fields: conversation ID, external appointment ID, exactly one USER and one
SPECIALIST participant snapshot, lifecycle, activity timestamps, and schema
version.

Relationships:

- `appointmentId` is a logical/external Consultation reference;
- participant account IDs are logical/external Identity references;
- Message documents refer to `conversationId` logically without a MongoDB FK.

### Message — ACTIVE

Collection: `messages`

Key fields: message/conversation/sender/client IDs, type, AES-256-GCM envelope,
key version, command fingerprint, lifecycle timestamps, moderation hold, and
schema version.

## Proposed model

| Entity | Status | Evidence and boundary |
| --- | --- | --- |
| Longitudinal Analysis Job/Result | PROPOSED | ADR 0015 defines a bounded comparison model, but no migration creates a collection. |
| Message Receipt | PROPOSED | Realtime module documentation defers high-water delivered/read persistence; no collection exists. |
| Message Attachment | PROPOSED | Deferred until object-storage, authorization, and retention contracts are accepted. |
| Moderation evidence | PROPOSED | No owner migration exists; `moderationHold` on Message is not a moderation-case aggregate. |

Do not create SQL tables for these document models. Do not mark a proposed
collection active until an owner migration, validator, implementation, and
tests exist in the same delivery lifecycle.
