# ADR 0021: AI Companion conversation and quota contract

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
Conversation append, quota consumption, and command completion share one Mongo
transaction. Exact replay returns the original response and conflicting reuse
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

## Rejected alternatives

- Count sends or tokens in the browser: rejected because retries, multiple
  devices, and modified clients bypass it.
- Count provider attempts as delivered answers: rejected because users would
  lose quota on timeouts and provider failures.
- Call Realtime for AI chat: rejected because Realtime owns specialist session
  messaging, while Journal/AI owns AI conversation content and provider work.
- Persist assembled prompts for debugging: rejected because it duplicates
  sensitive owner data and hidden provider material.
