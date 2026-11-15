# ADR 0050: Two AI consent levels

- Decision ID: `MB-TWO-LEVEL-AI-CONSENT-001`
- Status: Accepted by Product Owner for MB-806, 2026-11-06
- Owners: Care (consent authority); Journal/AI (source and provider enforcement)
- Policy: [Care consent and retention policy 1.2-capstone](../policies/care-consent-and-retention-policy.md)

## Decision and prospective amendment

This decision prospectively narrows the AI-purpose clauses of
[MB-AI-COMPANION-001](0015-ai-companion-analysis-contract.md),
[MB-AI-COMPANION-CHAT-001](0021-ai-companion-conversation-and-quota.md),
[MB-AI-VOICE-COMPANION-001](0046-ai-voice-companion-contract.md),
[MB-TEXT-VOICE-HANDOFF-001](0048-opt-in-text-voice-context.md), and
[MB-AI-COMPANION-FRIEND-001](0049-ai-companion-friend-voice-and-suggestions.md).
It also governs their existing saved-brief, Guide phrasing, plan companion and
emotion-projection consumers. Historical notices, decisions, results and source
labels retain the exact versions and content under which they were recorded.

`AI_PROCESSING` is L1, “AI trò chuyện”. Its current immutable disclosure remains
`ai-processing-capstone-v2`; a current grant already recorded at v2 stays valid
for L1 without reconsent. This is a narrowing of the former purpose, not a new
grant. L1 permits processing the text the owner actively sends and bounded
eligible same-conversation memory. It never permits retrieving personal records.

`AI_CONTEXT_PROCESSING` is the new L2, “AI dùng bối cảnh”, at
`ai-context-processing-capstone-v1`. Nobody receives L2 automatically. Effective
L2 requires its own current true decision and effective L1. A new L2 true command
without effective L1 returns `409 AI_PROCESSING_CONSENT_REQUIRED` without an
appended decision; L2 withdrawal is always permitted. L1 withdrawal suspends an
existing current true L2 decision (`DEPENDENCY_REQUIRED`), without inventing a
withdrawal. Explicit L1 regrant restores that L2 decision's effectiveness.

L2 covers bounded previously approved personal sources, exact and longitudinal
Journal analysis, Care's AI-evidence reuse projection, personal-context selection,
plan companion, saved-brief AI draft, Guide AI phrasing and emotion AI projection.
It grants neither source ownership/access nor raw assessment answers, unlimited
records, specialist sharing, research or marketing. Each source keeps its current
ownership, minimization, range, count and publication restrictions. Deterministic
screening, safety, Guide and plan fallbacks remain available without either level.

## Authoritative reads and writes

Care returns raw latest decisions separately from the effective snapshot
`GET /api/v1/consents/ai-authorizations`. The snapshot reads both levels in one
repeatable-read transaction and returns `chat`, `context`, and server `checkedAt`.
Missing decisions use null version/time. Each level's own missing, revoked or
outdated reason wins over dependency; `DEPENDENCY_REQUIRED` applies only to a
current true L2 whose L1 is ineffective. `context.authorized` implies
`chat.authorized`; authorization is true exactly when its reason is `GRANTED`.

The existing `/api/v1/consents/ai-processing/authorization` keeps its shape and
authorizes effective L1, including existing current v2 grants. All consumers that
need personal context must use the L2 snapshot check. Consent responses and
errors are non-cacheable (`Cache-Control: no-store`). The new notice follows the
existing public disclosure read; decisions/snapshots remain USER-subject scoped.

Both AI streams serialize on the existing Care profile lock. Decisions use
strictly increasing PostgreSQL-microsecond timestamps across AI streams, even
with equal or backward wall clocks. Owner/type/idempotency-key uniqueness and
intent digest remain. Exact replay resolves before policy/dependency validation,
returns the original `201` decision and cannot reinstate authority; changed intent
returns `409 IDEMPOTENCY_KEY_REUSED`. Historical known AI v1 requests are accepted
only to resolve an existing exact replay; a new key must use current v2. No remote
call occurs inside the Care database transaction.

## Request, source and attempt enforcement

Journal/AI uses one strict Care snapshot adapter with a verified ephemeral bearer,
the current bounded deadline and no token persistence, consent cache or shadow
grant. Malformed/unknown/inconsistent snapshots, non-success responses and
timeouts fail closed as dependency unavailable; the caller's own invalid bearer
retains `401`. A restarted job without the bearer fails instead of acquiring new
authority. Request, redispatch, reclaim and every provider retry reauthorize.

Effective L2 is checked before personal source query/decryption/projection and
again immediately before every provider attempt after routing/assembly. An owned
metadata lookup may precede it to preserve not-found behavior. Chat checks L1 at
request and final attempt. Known L2-off skips personal assembly entirely. If L2
is lost during assembly, discard personal source text/references and history,
rebuild the plain payload and token estimate without another source fetch, and
dispatch only after the final successful authoritative L1 read. Uncertainty or
L1 loss refuses dispatch through the existing reservation-release path. A later
withdrawal cannot unsend an already dispatched request but blocks further work.

All existing `JOURNAL`, `SUPPORT_PLAN`, `REASSESSMENT` and `RESOURCE` context kinds
are personal. Memory selection drops a whole adjacent USER/ASSISTANT pair if
either message has disallowed kinds, before decrypting it. Bounded empty-kind
pairs remain eligible under L1. New message/response labels are the union of
actually fetched source kinds and retained-history kinds, so personal memory
cannot become an empty-kind response. Old messages/results are immutable. An old
assembled prompt or command snapshot is never reused as provider input.

New conversations select no sources: `{plan:false,diary:false,screening:false,
resourceIds:[]}`. Persisted selections are preferences, not authority; ignore them
without L2. Selecting any personal source requires effective L2 and existing
source checks; clearing remains available. Regrant selects nothing new; saved
preferences can resume only after effective L2 and fresh source validation.

Voice continues to require L1 plus the separate immutable Voice v2 grant,
entitlement, quota and current owner/session/lease checks at admission, reconnect,
renewal, every provider turn and publication. The existing bounded explicit Text
handoff permits only eligible empty-kind pairs. L2 neither enables additional
Voice sources nor replaces microphone consent. Current-call RAM purge, cutoff,
worker envelopes, media authority and provider rollout gates remain unchanged.

Encryption, retention, no saved prompts/bearers/raw provider output, AI-support-only
and Care-decides rules, public-catalogue suggestions, quotas, source limits and
deterministic fallbacks remain unchanged.

## Compatibility and rollout

Care OpenAPI advances from 1.10.0 to 1.11.0; Journal/AI from 1.17.0 to 1.18.0.
One append-only Care constraint-expansion migration adds only the new L2 type;
it never rewrites/backfills historical decisions. Journal's persisted shapes and
validators do not change; selections/provenance are interpreted under this new
authorization rule without rewriting history or running a Mongo migration.

Deploy the Care expansion/snapshot provider before the Journal consumer and
before MB-805/807 frontend contract sync. Coordinate the consumer cutover so no
old broad `AI_PROCESSING` consumer continues personal-context processing after
this policy becomes effective: L1 remains a valid endpoint and cannot protect
an old L2 consumer. Unknown/absent L2 authority fails closed. The frontend renders
Care's immutable notices and current authorization, does not preselect either
checkbox and never derives L2 from an existing L1 grant. ngocquyen owns MB-805/807
and runs `npm run contracts:sync` from the merged backend contract manifest.
