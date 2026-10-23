# ADR 0042: Privacy-first Community conversation contract

- Status: Accepted by MB-684 PR #206, approved and merged at `318248c732008512d110935b9576516f9d4ca084`; runtime promotion remains story-scoped
- Date: 2026-10-20
- Decision ID: `MB-COMMUNITY-CHAT-001`
- Product baseline: [MB-683](https://vunguyenkhoi47.atlassian.net/browse/MB-683)
- Policy: [Community chat policy v1](../policies/community-chat-policy-v1.md)
- Extends: [Community boundary / MB-COMMUNITY-SERVICE-001](0027-community-service-boundary.md)
- Acceptance and permission tables: [MB-684 evidence](../story-mb-684-community-chat-contract-evidence.md)

## Context

The approved Epic separates private peer messaging from appointment chat and
requires Community-owned conversations, permissions and messages. The current
appointment feature instead persists encrypted messages in Realtime/MongoDB,
with eligibility owned by Consultation. Reusing that conversation type would
silently bypass either Community privacy or appointment eligibility.

MB-684 is a contract-only prerequisite. It adds no route handler, table,
migration, socket namespace, broker topic, provider call or frontend change.
The new OpenAPI operations remain `planned` in `contracts/proposals/`.
Tests validate the design artifacts; they do not certify a runnable chat feature.

## Decision

Community owns peer conversation identity, direct requests, private groups,
membership epochs, messages, private read cursors, chat privacy, bilateral
blocks and report-scoped moderation. Its future runtime uses its own
PostgreSQL/Liquibase boundary, not another service's storage.

Realtime owns `/community` socket delivery and ephemeral multi-device presence.
It obtains current authorization and authorized message projections through
Community REST. It does not persist a second Community message history, accept
Community message writes into MongoDB, or infer permissions from a room name.
Message writes and recovery use Community REST; WebSocket transports authorized
owner results and ephemeral events. SSE is not a parallel transport in v1.

This is a scoped exception to the general Realtime/MongoDB durable-chat rule
in the architecture and messaging guide, exclusively for Community peer chat.
Existing `/realtime`, appointment contracts, MongoDB messages, Consultation
eligibility, completion evidence and credits remain unchanged.

Content/Notification retains durable inbox, delivery, user preferences, quiet
hours and replay dedupe. Community emits minimized recipient-specific facts
through its transactional outbox; it never sends provider notifications itself.
Realtime fetches current authorized content for live delivery; chat bodies never
cross Kafka or Redis. Independent consumers use independent Kafka groups.

## Frozen v1 candidate

The [policy](../policies/community-chat-policy-v1.md) specifies default approval,
text-free requests, decline/block suppression, existing-conversation behavior,
group roles, membership history, opt-in presence/typing/receipts, monotonic
ordering, retry, retention, limits and least-privilege evidence review.

Canonical language-neutral artifacts:

- [OpenAPI](../../contracts/proposals/community-chat-v1.openapi.json)
- [Client commands](../../contracts/proposals/community-chat-command-v1.schema.json)
- [Namespace handshake](../../contracts/proposals/community-chat-handshake-v1.schema.json)
- [Private client events](../../contracts/proposals/community-chat-realtime-v1.schema.json)
- [Minimized durable facts](../../contracts/proposals/community-chat-fact-v1.schema.json)
- [Synthetic examples](../../contracts/proposals/community-chat-v1.examples.json)

Schemas close object fields and variants. Sender, roles, identity ownership,
sequence, visibility, unread count, delivery and completion are server facts.
Validation of shape is necessary but cannot establish authorization: runtime
providers must also enforce the semantic bindings listed in the policy.

Realtime's exact-message read is the planned internal message-projections REST
operation under a signed service plus end-viewer delegation, with target-bound
claims and current authorization checked alongside content. The boolean
realtime-authorizations response never grants a subsequent content read. USER
history authentication is unchanged. Wrong audience/action and inaccessible
message/membership targets fail closed; no actor selector is accepted in a body.

Global message sequences and outbox revisions remain private. Public conversations
omit lastSequence; messages/receipts/read acknowledgements use opaque message
IDs, with ordered REST history and opaque epoch-bound pagination. Public version
is a separate management revision that does not advance for hidden messages.
Private unread/read watermarks remain monotonic; visibility projections never
expose gaps from blocks or pre-join history. Archive/remove 204 responses require
an authorized getConversation refresh before another versioned group command;
invitation decision revisions remain distinct from group management revisions.

## Alternatives rejected

- Friends, follows, public groups and directory search: outside the product scope.
- Unrestricted reuse of appointment chat: different ownership and eligibility.
- Durable Community messages in Realtime plus Community: two authorities.
- Raw content in Kafka/Redis or notification previews: excessive exposure.
- Token-only room access or cached grants after block/removal: stale authority.
- Automatic request acceptance after changing a preference: no explicit acceptance.
- ADMIN transcript browsing: role does not establish a report-scoped purpose.
- E2EE/therapy/safety-monitoring claims: no supporting implementation or policy.

## Review and delivery

Approval and merge accept this v1 candidate, including the explicit numeric
defaults and shared-group block/history rules. No approval is fabricated by this
document. Until then downstream implementation must treat it as review input.

MB-685/687 implement Community transactions, migrations, security and provider
tests. MB-689/690 implement Realtime delegation, fanout and real Redis privacy
tests. MB-691 extends the existing notification pipeline; existing MB-580/617
post/comment facts are not republished or replaced. MB-686/688/692 consume the
accepted contract only after providers are compatible. MB-693 supplies cross-stack
acceptance. Each runtime story promotes only its delivered operations and updates
the canonical data model together with its owner migration.

MB-745 adds sender retraction without changing conversation ordering or report
evidence. The currently authorized sender may retract retained content with no
additional minute-based window. Community erases ciphertext and returns a
structured `RETRACTED` projection at the original message identity and position;
Realtime carries only the minimized change pointer and reauthorizes each viewer.
The transition creates no unread item, never moves a read watermark backward,
and cannot be reversed or resurrected by a send retry. Evidence already captured
for an authorized report remains isolated under the report case authority.

No clinical, consent, retention, permission or payload widening is an additive
implementation detail. Such a change requires explicit policy/schema review.
