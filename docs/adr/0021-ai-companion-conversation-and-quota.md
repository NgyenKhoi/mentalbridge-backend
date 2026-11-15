# ADR 0021: AI Companion conversation and quota contract

Prospective MB-806 consent amendment: [ADR 0050 / MB-TWO-LEVEL-AI-CONSENT-001](0050-two-level-ai-consent.md)
narrows existing current `AI_PROCESSING` v2 grants to L1 conversation and requires
new dependent `AI_CONTEXT_PROCESSING` v1 for personal sources, analysis and
accompaniment. Historical notices/decisions remain immutable. Voice retains L1
plus its separate unchanged Voice v2 grant and eligible empty-kind Text handoff.


- Status: Accepted
- Date: 2026-09-22
- Decision ID: `MB-AI-COMPANION-CHAT-001`
- Implements: MB-512, MB-527, MB-528, MB-529, MB-530
- Extends: [ADR 0015](0015-ai-companion-analysis-contract.md) and
  [ADR 0017](0017-product-scope-v2.md)

## Context

Exact-revision and longitudinal analysis already establish consent checks,
provider routing, provenance, and the boundary that AI supports while Care
decides. They do not define an actor-facing conversation, daily plan quotas,
retention, deletion, or safe assembly of several owner-verified context types.

Counting in the browser, trusting a package claim, or accepting raw context
objects from the browser would make quota and sensitive-data authorization
advisory. Storing prompts or raw provider responses would also expand the
privacy boundary beyond what the feature needs.

## Decision

Journal/AI owns AI Companion conversations, encrypted message content,
idempotency commands, daily quota ledgers, and short-lived rate ledgers in its
MongoDB database. Conversation message delivery is synchronous and bounded by
the provider timeout. Streaming is not approved in this version; the consumer
shows an explicit loading state.

The server resolves the current package from Consultation for every requested
answer. `FREE` defaults to five successfully delivered assistant responses per
server-authoritative local day. `PLUS` uses a higher configurable allowance.
`PREMIUM` returns no user-visible remaining count, but a configurable hidden
daily fair-use ceiling still applies. Every plan is also subject to per-minute
request and daily token guards. The quota policy is `companion-quota-v1`.

A command first creates an HMAC-protected idempotency record and atomically
reserves one daily answer slot. Provider or context failure releases that slot;
only a persisted assistant response increments the successful-answer count.
Conversation append, successful-answer count, and command completion share one
Mongo transaction. Exact replay returns the original response and conflicting reuse
is rejected. The stable reset instant is derived from the configured IANA
timezone, not a browser claim.

The browser can select at most three owned current Journal entries and one
owned longitudinal result. It may request the current SupportPlan; Journal/AI
forwards the verified bearer to Care and parses only status, version,
rationale, purpose, and selected reviewed-resource display text. Raw
assessment answers, scores, safety internals, eligibility inputs, and
unselected records are excluded. Reminder context fails closed until
Content/Notification publishes an approved owner contract. Context text exists
only in provider-call memory; persistence records only bounded context-kind
labels.

Messages are AES-256-GCM encrypted with owner, conversation, and message AAD.
The default retention is 90 days and a TTL index removes expired conversations.
User deletion hard-deletes the conversation and its command snapshots in one
transaction. Quota/rate ledgers retain no message or context and expire
separately for abuse/accounting control.

Provider prompts require a bounded normalized Vietnamese response and prohibit
diagnosis, assessment scoring, safety/eligibility decisions, SupportPlan
mutation, reminder scheduling, or third-party contact. No chain-of-thought,
raw provider response, bearer token, API key, or assembled prompt is persisted
or logged. Care-owned safety and the deterministic “Cần trợ giúp ngay” entry
remain available when AI fails.

## MB-829 synchronous TEXT reliability amendment — owner decision 2026-11-11

For synchronous TEXT companion sends only, at most two calls to the same pinned
provider/model are permitted. The second call may retry HTTP 429, provider 5xx,
or transport/timeout, or repair output that fails parsing, schema, or existing
message validation. These share one extra attempt; no rule is relaxed and no
cross-provider fallback occurs. Each call is capped at 10,000 ms and the whole
send at 24,000 ms; the second call is skipped when less than 3,000 ms remain.
Before each call, Journal/AI atomically reserves its input token estimate against
the daily budget and settles the difference after the attempt. Attempted token
use is charged to the daily ledger as reported by the provider. When no object
response body is parsed, only the attempted input estimate is charged; a parsed
object without usage is charged estimated input and output, including rejected
output. Only a committed assistant reply consumes an answer slot. Voice and
analysis are unchanged.

## Consequences

- MongoDB deployment must support transactions (replica set or managed
  equivalent) before chat writes are enabled.
- Package and quota changes remain server configuration/version changes rather
  than frontend releases.
- Real Gemini/OpenAI chat still requires the existing pinned-route benchmark
  approval and external provider credential. Local, test, and CI use the
  deterministic fake.
- Reminder accompaniment remains unavailable rather than using notification
  data without an approved authorization contract.

## MB-810 title amendment — owner decision 2026-11-06

Journal/AI also owns the encrypted display title of a TEXT conversation. The
first committed answer may initialize a default, empty conversation with a
topic title from the same provider structured output; there is no second
provider call. The optional title is accepted independently of the required
answer in both TEXT schema versions. It is trimmed, whitespace-normalized,
bounded to 80 Unicode code points and six words. Missing or invalid titles use
the first 40 Unicode code points of the outer-trimmed first user message.
Existing custom titles, populated legacy histories, explicit create titles and
manual renames cannot be overwritten by automatic initialization. Voice and
the shared Voice response schema are unchanged.

The owner explicitly accepted topic titles and the first-message fallback
instead of neutral dated titles for unknown safety status. Prompt guidance
forbids crisis and diagnosis labels; it is not a trusted safety decision.
Provider `NONE` does not mean safe, and Journal/AI adds no detector or inference
from text, context or suggestions. **Accepted risk:** a risky first message can
still be exposed through its 40-character fallback. The named follow-up
`MB-810-CARE-SAFETY-TITLE` must first obtain a Care-owned trusted current-turn
safety source, then define and verify the neutral `Trò chuyện YYYY-MM-DD`
override using server time and the configured chat timezone. That override is
deferred, not part of MB-810's delivered behavior.

New explicit, automatic and renamed titles use the existing AES-256-GCM
envelope and key with purpose-separated AAD
`${owner}:${conversationId}:title`. The required legacy `title` stays neutral
(`Cuộc trò chuyện mới`) on new title writes, never a plaintext mirror. Reads
prefer `encryptedTitle`; only its absence permits the legacy plaintext fallback.
A present unreadable envelope produces the neutral title with content-free
diagnostics, without exposing a legacy value or failing the whole list.

Owner-only `PUT /api/v1/ai-companion/conversations/{conversationId}/title`
replaces a title without consent, entitlement, provider, quota, rate or message
effects. It requires an opaque title-state HMAC token in a strong quoted
`If-Match` and the existing idempotency key. Atomic compare-and-swap plus a
completed encrypted rename outcome share one Mongo transaction in the existing
command collection. Exact duplicates replay their original title/version;
changed path, title or precondition conflicts. Send responses and replays expose
current-at-read title metadata, which may be omitted together on hydration
failure without changing an already successful answer or quota. No sensitive
title is added to plaintext send snapshots. Deletion and command TTL remain
coupled to the existing lifecycle.

The owner overrides the former no-migration wording: migration
`018_companion_conversation_title.cjs` performs exactly one additive strict/error
`collMod`, cloning migration 017's complete validator and adding only optional
`encryptedTitle`. It changes no existing documents, required fields or indexes;
there is no backfill or startup repair. Deploy this validator before code can
write encrypted titles. Down refuses while any encrypted title exists and
otherwise restores 017's validator; application rollback must retain compatible
encrypted-title reads. Shared-database execution requires separate authorization.

The additive REST contract is Journal/AI `1.19.0` on `/api/v1`. MB-811 consumer
ngocquyen (`phanthi.ngquyen`) runs `npm run contracts:sync` against this backend
worktree while parallel, then merged `dev`, and preserves operation ordering or
refetches after overlapping send/rename results. Contract-ready details are
recorded in `.artifacts/mb-810-contract-ready.md` for the implementation PR.

## Rejected alternatives

- Count sends or tokens in the browser: rejected because retries, multiple
  devices, and modified clients bypass it.
- Count provider attempts as delivered answers: rejected because users would
  lose quota on timeouts and provider failures.
- Call Realtime for AI chat: rejected because Realtime owns specialist session
  messaging, while Journal/AI owns AI conversation content and provider work.
- Persist assembled prompts for debugging: rejected because it duplicates
  sensitive owner data and hidden provider material.
