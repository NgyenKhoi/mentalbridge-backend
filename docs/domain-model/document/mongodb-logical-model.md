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

### Longitudinal Analysis Job — ACTIVE

Collection: `longitudinal_analysis_jobs`

Key fields: owner, keyed idempotency data, explicit previous/current period
bounds, optional excluded journal IDs, exact selected source revisions,
coverage decision, lifecycle, attempt/lease state, terminal reason, result ID,
immutable route snapshot, and timestamps. Raw journal text and end-user bearer
credentials are not fields.

Relationships:

- logical same-owner references to every selected Journal Entry exact revision;
- logical result reference to Longitudinal Analysis Result;
- deleting any selected source journal removes the dependent job and result.

### Longitudinal Analysis Result — ACTIVE

Collection: `journal_longitudinal_analysis_results`

Key fields: analysis/job/owner IDs, explicit periods, exact source revisions,
coverage counts/sufficiency, normalized contextual and emotional evidence,
direction vocabulary, provider/model/prompt/schema and entitlement-routing
provenance, execution metrics, and creation time. Raw source/provider text,
hidden reasoning, combined scores, and clinical-improvement conclusions are not
fields.

### Emotion Check-In — ACTIVE

Collection: `emotion_check_ins`

Key fields: owner/local date/timezone, optimistic revision, encrypted bounded
revisions containing required emotion/intensity plus optional factor codes and
private note, idempotent command records, deletion tombstone, and purge time.

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

Migration `011_bedrock_provider.cjs` extends exact-revision, longitudinal,
benchmark, and AI Companion provenance with `BEDROCK` while retaining existing
provider values. Benchmark runs may contain up to three pinned candidates; raw
provider output and credentials remain excluded from every collection.

### AI Companion Conversation — ACTIVE

Collection: `ai_companion_conversations`

Key fields: conversation/owner IDs, title, bounded encrypted messages,
owner-controlled context-source flags, up to 20 selected resource IDs, context
update time, assistant route provenance, context-kind labels (including
`RESOURCE`), activity timestamps, and retention expiry. Plaintext content,
assembled prompts, selected source plaintext, bearer credentials, raw provider
output, and hidden reasoning are not fields. Migration
`012_ai_companion_conversation_context.cjs` backfills the default context before
enforcing the strict validator. Migration `017_companion_message_suggestion.cjs`
adds an optional encrypted `suggestion` on assistant messages (destination,
Resource ID and title; ADR 0049). Under [ADR 0050](../../adr/0050-two-level-ai-consent.md),
MB-806 changes no persisted shape or validator and needs no Mongo migration. New
conversations select no sources; stored selections remain preferences and require
effective L2 plus fresh source authorization. All four existing context kinds are
personal. Memory excludes an entire pair with a disallowed kind before decryption;
new message labels union actual fetched and retained-history kinds without
rewriting historical labels or result provenance.

MB-810's [ADR 0021 amendment](../../adr/0021-ai-companion-conversation-and-quota.md#mb-810-title-amendment--owner-decision-2026-11-06)
adds optional `encryptedTitle`, using the existing AES-256-GCM envelope with
owner/conversation/title-purpose AAD. Newly explicit, automatic and renamed
titles are encrypted; mandatory `title` remains the neutral `Cuộc trò chuyện mới`
on new title writes. Only documents without the envelope use their existing
plaintext `title` as a legacy read fallback; a corrupt envelope reads neutral.
The opaque HMAC `titleVersion` is derived from title state, never stored, and
does not change when messages or context change. First-turn initialization is
conditional on the persisted empty/default state; any encrypted title blocks it.

The owner decision of 2026-11-06 authorizes migration
`018_companion_conversation_title.cjs`: one strict/error validator-only `collMod`
clones 017 and adds only the optional envelope. Existing documents, required
fields and indexes remain unchanged. Deploy it before title-writing code;
rollback refuses while any encrypted title exists and otherwise restores 017.
There is no backfill, startup repair or shared-database execution authorization.

### AI Companion Command — ACTIVE

Collection: `ai_companion_commands`

Key fields: owner and keyed idempotency identity, request fingerprint,
conversation reference, lifecycle, quota reservation reference, and immutable
success/failure response snapshot. Raw idempotency keys are not fields.

MB-810 reuses this aggregate for completed title renames. The owner-scoped key
hash is separated from send keys by the `rename-title:` prefix; its fingerprint
binds operation, conversation, normalized title and precondition. A rename's
result title is encrypted with owner/conversation/command/purpose AAD and keeps
its original title version for exact replay, even after a later rename. The
title update and completed command commit together. Send snapshots contain no
new plaintext title copy: title metadata is hydrated from the current owned
conversation and may be omitted together on read failure after success.

### AI Companion Daily Quota — ACTIVE

Collection: `ai_companion_quota_ledgers`

Key fields: owner, server-derived local day and timezone, plan/policy snapshot,
successful response count, in-flight reservations, token use, reset time, and
expiry. It contains no conversation content.

### AI Companion Rate Limit — ACTIVE

Collection: `ai_companion_rate_ledgers`

Key fields: owner, bounded request window, count, and TTL expiry. It contains no
message or context data.

### AI Companion Voice Session — ACTIVE

Collection: `ai_voice_sessions`

Owner: `journal-ai-service`. Migration
[`013_companion_voice_quota.cjs`](../../../journal-ai-service/migrations/013_companion_voice_quota.cjs)
implements the MB-756 admission/accounting foundation under
[`companion-voice-policy-v1`](../../policies/companion-voice-policy-v1.json).
Media and public session/worker/turn APIs remain separate delivery scopes.

Key fields: server session/owner IDs, keyed idempotency hash and intent
fingerprint, policy and frozen admission entitlement provenance, closed lifecycle,
frozen Vietnam quota day/admitted seconds/hard deadline, short authority lease,
dispatch/attempt/generation/sequence fences, first-publication flag, accumulated
ACTIVE milliseconds, timer cuts, terminal settlement, and creation-bound expiry.
MB-790 migration 016 adds optional `contextHandoffHash`: a 43-character keyed
reference consumed by a unique partial index during admission. It binds an
explicit selected-Text handoff to exactly one session without storing the ticket,
conversation selector, selected messages or summary. Ticket expiry is a transient
admission input, not a persisted field. The hash survives settlement until normal
creation-bound metadata cleanup; disabling the feature never frees it for reuse.
Authority renewal uses current owner decisions without rewriting admission
provenance. The first trusted connected-owner publication consumes one session;
settlement rounds summed ACTIVE time once and refunds unused reservations. An
undelivered session consumes no quota. Midnight or upgrade never extends a live
session.

Relationships:

- `ownerId` is a logical/external Identity reference;
- `quotaDay` and owner logically identify the Voice Daily Usage document;
- a held Voice Capacity slot logically identifies this session and its fence;
- effective L1 (`AI_PROCESSING`) plus separate Voice v2 consent and entitlement
  are current REST decisions from Care and Consultation; L2 cannot broaden Voice,
  not database relationships or reusable authorization cached in this document.

No audio, transcript, prompt, model reply, old Journal/text context, bearer or
participant token is stored. Metadata expires 30 days from creation, including
unsettled sessions. There is no session TTL: a transaction first scrubs any
identifying held-slot references, quarantines uncertain media, and deletes the
expired session.

### AI Companion Voice Daily Usage — ACTIVE

Collection: `ai_voice_daily_usage`

Key fields: unique owner/quota-day identity, used and reserved sessions/seconds,
daily and minute attempt counters, at most ten pending HMAC attempt identities, and
creation-plus-30-day expiry. The server uses `Asia/Ho_Chi_Minh`; charging stays
on the authorized day across midnight. Concurrent accepted-key retries add no
attempt or reservation. Pending attempt identities last at most ten seconds;
only the atomic claimant performs owner reads, and a unique owner/pending-key
index protects that claim across midnight. Admission and failure cleanup check
the matching expiry fence, so stale work cannot reserve or release a replacement
claim. Failed cleanup releases the identity without refunding the attempt count,
so later denied-key retries are counted again. Package upgrades raise ceilings
without resetting use. This ledger is independent of text Companion quotas. Its
TTL removes metadata only and never releases capacity.

### AI Companion Voice Capacity — ACTIVE

Collection: `ai_voice_capacity`

One singleton contains exactly two bounded `FREE`, `HELD`, or `QUARANTINED`
slots. Each has a random generation; only HELD slots contain owner/session
references. Admission and settlement transact across session, daily usage, and
this singleton, enforcing one session per owner and two global slots across
processes. No all-user history, content, provider token, or TTL exists here.

First settlement requires provider proof fenced by the current attempt, lease,
generation, and a sequence newer than accepted progress. Completed settlement
replay is stable. Expired leases and uncertain teardown keep capacity occupied.
Before the session's creation-plus-30-day privacy deadline is swept, identifying
slot references are scrubbed; unresolved media leaves only an anonymous blocked slot
and random fence. Provider-verified namespace isolation is required for future
reuse, and this foundation exposes no quarantine-clearing operation.

### AI Companion Voice Media — ACTIVE

Collection: `ai_voice_media`, migration 014, Journal/AI owner.

One logical reference to a Voice Session/attempt, unique opaque AI room and
logical UUID dispatch identity, separate opaque provider dispatch ID and two
provider participant identities. It contains the immutable hard stop, bounded
creation/dispatch/teardown phase, worker claim and independent worker/backend
sequence fences, latest content-free control replay and latest server-issued
turn/reply identifier proof. It contains no audio, transcript, answer, credential
or participant-token snapshot. New media effects require both persisted mapping
and current owner authorization. Unknown provider outcomes retain capacity;
only confirmed cleanup permits settlement. An uninitialized ledger fence can
be compensated only while ENDING and after transactionally proving this media
document is absent. Such a proof cannot come from worker input.

Creation and expiry follow the original session's 30-day metadata window.
Deleting this mapping never clears a capacity permit or anonymous quarantine.
The model has no relationship to Consultation appointments, attendance or credits.

### AI Companion Voice Media Command — ACTIVE

Collection: `ai_voice_media_commands`, migration 014, Journal/AI owner.

Keyed owner/operation/command identity binds connection/end to one Voice Session.
Only the digest, operation, logical session reference and creation/expiry are
stored. Connection replay reauthorizes instead of replaying a saved token; end
is naturally terminal/idempotent. Retention is capped by the original session
deadline, without extension. TTL removes bindings only.

MB-758 migration 015 adds a closed `turn` variant within this same aggregate.
It binds the keyed owner/turn intent to admitted session/attempt, unique opaque
turn ID and ordered turn sequence, originating RAM-context UUID, keyed intent
fingerprint and approved pinned route/version hash. Unique partial session/
sequence and session/turn-ID indexes fence concurrent generation. It is written
before the provider call; missing RAM or a different context process cannot
regenerate an accepted turn. Only the latest live RAM reply is replayable.

Turn fences contain no transcript, model reply, prompt, token or context content.
Creation is turn admission; expiry remains capped by the original session's
30-day window. Cancellation/end/revocation/expiry clears RAM without clearing
the durable fence prematurely. Existing TTL/sweep applies, and 015 rollback
refuses surviving turn fences before restoring the legacy command validator.
This adds no new aggregate owner or Text-quota relationship.

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

### Appointment Message Report — ACTIVE

Collection: `appointment_message_reports`

Key fields: report/conversation/message/reporter IDs, bounded reason, optional
encrypted context, review state/resolution, resolver, timestamps, and schema
version. `appointment_moderation_commands` owns actor-scoped retry fences and
`appointment_moderation_audit` owns content-free action evidence.

## Proposed model

| Entity             | Status   | Evidence and boundary                                                                             |
| ------------------ | -------- | ------------------------------------------------------------------------------------------------- |
| Message Receipt    | PROPOSED | Realtime module documentation defers high-water delivered/read persistence; no collection exists. |
| Message Attachment | PROPOSED | Deferred until object-storage, authorization, and retention contracts are accepted.               |

Do not create SQL tables for these document models. Do not mark a proposed
collection active until an owner migration, validator, implementation, and
tests exist in the same delivery lifecycle.
