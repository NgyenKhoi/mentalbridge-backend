# ADR 0043: Native Community credential exchange

- Decision ID: `MB-COMMUNITY-NATIVE-CREDENTIAL-001`
- Date: 2026-10-21
- Status: Owner accepted a separate backend prerequisite; provider implemented in MB-749, deployment/consumer rollout separate
- Delivery: [MB-749](https://vunguyenkhoi47.atlassian.net/browse/MB-749), consumed by MB-692
- Amends only native ticket ingress of [ADR 0042](0042-community-chat-contract.md)

## Context and decision

MB-692 is a React Native consumer with an Identity session and one public API
edge. The existing internal Community ticket route requires trusted BFF-signed
delegation. A native application cannot hold that signing key or copy a web BFF.
The owner chose a separate backend prerequisite on 2026-10-21.

Add `POST /api/v1/community/socket-credentials`, owned by Realtime and routed
exactly through the public edge before a broad Community prefix rule. Reuse the
existing Identity JWT verifier; only verified USER subject and original expiry
select the session. The request is absent or an empty JSON object. Client
actor/role/expiry selectors, arrays and other non-object bodies are rejected.

Community's current session authorization runs before ticket issue and again
on consume. Denied eligibility is generic unavailable; uncertainty fails closed.
The response reuses the closed opaque one-use `/community` ticket shape,
`Cache-Control: no-store`, and an expiry no later than either 30 seconds from
issuance or the original Identity expiry. Redis contains only the minimized
subject/expiry record keyed by ticket hash; no raw Identity bearer is retained.
Namespace, revocation, connection caps and exact target authorization remain
the existing Community rules.

Each explicit new connection attempt obtains a fresh ephemeral ticket. There is
no automatic POST retry, durable idempotency ledger or business transition here.
An uncertain issuance can leave only an unused short-lived ticket; it cannot
create a conversation, message, membership or durable permission grant.

## Compatibility and rollout

The trusted BFF internal operation, appointment `/realtime` exchange, one-use
consume semantics, WebSocket schemas and Community ownership are unchanged.
The new route exists only when the existing Community transport flag is enabled.
No migration, new secret, provider or durable model is introduced.

Deploy provider and exact edge routing before MB-692 connects. Native sessions
must use the shared credential injection seam and keep tickets in memory.
Rollback the native consumer before removing its public route; do not weaken
the BFF route to accept an ordinary bearer as a fallback.

## Verification boundaries

Signed HTTP tests cover actor/role/expiry and body validation, exact OpenAPI
responses and failures. Real Redis tests cover single consume under concurrency,
namespace separation, current eligibility on consume and dependency loss.
Existing BFF/appointment suites remain compatibility evidence. Backend tests do
not prove mobile UI, native Android execution or `ui-ready`.
