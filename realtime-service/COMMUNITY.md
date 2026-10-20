# Community realtime (MB-689)

Community owns messages, membership, blocks, read watermarks, unread and retention.
This module is an authenticated delivery edge. It never writes a Community
message, opens Community storage, or uses appointment chat eligibility.

## Configuration and rollout

Set `COMMUNITY_CHAT_ENABLED=true`, configure its Identity authorization credential
and encryption keys, and enable its existing outbox relay. Provision two independent
RS256 keypairs: one for Realtime-to-Community delegation and one for the trusted
Community BFF. Identity private keys are not reused. Deploy compatible providers
before enabling Realtime and the consuming client.

| Variable                                    | Owner / meaning                                                                                                             |
| ------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| `REALTIME_COMMUNITY_ENABLED`                | Realtime; defaults false, no namespace/controller/consumer when disabled                                                    |
| `REALTIME_COMMUNITY_BASE_URL`               | Community REST URL; local default `http://localhost:8084`, Compose `http://community:8084`; production cannot use localhost |
| `REALTIME_COMMUNITY_DELEGATION_PRIVATE_KEY` | Realtime-only PKCS#8 RSA private PEM; literal escaped newlines supported                                                    |
| `REALTIME_COMMUNITY_DELEGATION_PUBLIC_KEY`  | Community-only matching X.509 public PEM                                                                                    |
| `REALTIME_COMMUNITY_DELEGATION_KEY_ID`      | Both owner services; exact trusted signing-key ID                                                                           |
| `REALTIME_COMMUNITY_BFF_PUBLIC_KEY`         | Realtime-only trusted BFF X.509 public PEM                                                                                  |
| `REALTIME_COMMUNITY_BFF_KEY_ID`             | Exact trusted BFF signing-key ID                                                                                            |
| `COMMUNITY_CHAT_REALTIME_ENABLED`           | Community; defaults false, otherwise dedicated internal JWT chain is enabled                                                |
| `KAFKA_BOOTSTRAP_SERVERS`                   | Realtime broker addresses, required when enabled                                                                            |
| `REALTIME_COMMUNITY_KAFKA_GROUP`            | One logical group shared by all Realtime replicas; default `realtime-community-v1`; independent from Notification           |
| `REALTIME_COMMUNITY_REDIS_CHANNEL`          | Environment-specific minimized fanout channel shared by these replicas; examples use local/preprod prefixes                 |

Existing Realtime Redis settings supply ticket and routing storage. A routing
lease expires after 60 seconds and is renewed every 20 seconds; it is not an
online/presence signal. Five connections per subject are enforced atomically
across replicas without evicting an existing device. Existing service readiness
includes optional `community: connected|degraded` when this feature is enabled.

Precreate `mentalbridge.community.chat.v1` and
`mentalbridge.community.chat.realtime-dead-letter.v1`. Compose provisions both.
The latter contains only topic/partition/offset/error code, never the poison body.
Topic ACLs, replication and retention are deployment responsibilities. Deployment
changes here do not start, migrate or alter shared dev/staging databases.

## Credentials and owner delegation

The same-origin BFF verifies the current authenticated USER session and signs
these claims with its independent server-held private key:

```json
{
  "iss": "community-bff",
  "aud": "realtime-service",
  "sub": "11111111-1111-4111-8111-111111111111",
  "act": { "sub": "community-bff" },
  "scope": "community:socket:issue",
  "jti": "22222222-2222-4222-8222-222222222222",
  "iat": 1792512000,
  "exp": 1792512030,
  "identityExpiresAt": 1792515600
}
```

The example timestamps are synthetic. The BFF must derive `sub` and
`identityExpiresAt` exclusively from the verified Identity session; never accept
either from browser input or extend the Identity expiry. RS256, key ID,
issuer/audience/scope/actor, UUIDs, exact claims, a maximum 30-second delegation
lifetime and current original Identity expiry are verified by Realtime.

The BFF posts this delegation as Bearer to
`POST /internal/v1/community/socket-credentials`. The `no-store` result is
`{schemaVersion:1, namespace:"/community", credential, expiresAt}`. Community's
current account/profile gate runs both when issuing and consuming the ticket.
Only a hashed ticket key and bounded subject/expiry routing record enter Redis;
no original Identity bearer is stored. `GETDEL` consumes the ticket once. Tickets
cannot be exchanged with `/realtime` appointment credentials.

Realtime signs a separate maximum-30-second owner JWT with issuer/actor
`realtime-service`, audience `community-service`, exact USER subject and action.
Subscribe/replay claims bind conversationId; message projection additionally
binds messageId. The scopes are `community:session:authorize`,
`community:subscribe`, `community:replay:project`, and `community:message:project`.
Community validates signature/key/claims/expiry, then current Identity eligibility
before its local permission/visibility transaction. A boolean authorization
result is not retained as a permission grant. `decisionVersion:"0"` intentionally
exposes no private message/outbox revision. Owner HTTP uses a 500ms connect limit,
2s total deadline, bounded pool/response and a short failure circuit; no stale
authorization or content fallback and no mutation retry.

## Client protocol and recovery

Connect Socket.IO over WebSocket to `/community` with handshake
`{schemaVersion:1, credential}`. Use `community.command` and an ACK callback:

```json
{
  "schemaVersion": 1,
  "commandId": "33333333-3333-4333-8333-333333333333",
  "correlationId": "44444444-4444-4444-8444-444444444444",
  "commandType": "community.conversation.subscribe",
  "payload": { "conversationId": "55555555-5555-4555-8555-555555555555" }
}
```

Only subscribe is activated by MB-689. There are at most 30 conversations and
30 subscribe attempts per socket/minute. A successful ACK is a versioned
`community.command.ack` event with `status: ACCEPTED`; it grants no delivery/read
claim. Errors use the closed `community.command.error` variant. Message sends
and read acknowledgements use the existing Community REST operations and their
idempotency/monotonic cursor rules. There is no Community `message.send` command.

Listen on `community.event`. A subscription establishes a private owner tail,
then emits `community.resync.required` and absolute `community.read-state.changed`.
Fetch fresh public ascending REST history from cursor=null, follow its opaque
nextCursor until null, and merge by messageId. Preserve server order; random
UUIDs and timestamps are not sort counters. A stale visibility cursor requires
a fresh authorized read. This repair also covers an event racing subscription.

Subsequent MESSAGE_SAVED facts resolve ordered eligible ID pages at the owner.
Each exact message is fetched again for the current viewer immediately before
`community.message.created`. Duplicate/reordered facts cannot add messages above
the same delivery cursor. Clients still deduplicate by messageId, never eventId,
and replace unread/read state with the absolute owner result rather than adding
one per event. A read on another device emits a recipient-self transport fact;
it does not publish an opted-in shared read receipt. Only subscribed conversations
are delivered; other history/list state is recovered through REST.

On `community.conversation.invalidated`, discard that conversation's cached view.
Known revocation invalidates queued projections before emission. Hidden shared
group messages cause no event, unread change or resync for the blocked viewer.
Unblock/rejoin establishes a fresh visibility/membership boundary and never
rebroadcasts retained history as new messages.

On every reconnect, obtain a fresh BFF ticket and subscribe again. Do not reuse
the consumed credential. Server disconnects also require an explicit reconnect
with a fresh credential after the BFF revalidates the session. Stop on expired
or unavailable authentication. The server disconnects at original Identity
expiry, on dependency uncertainty and on bounded queue/socket backpressure.

## Guarantees, failure and evidence

Kafka facts are replayable and offsets advance after successful minimized Redis
publication or safe dead-letter publication. Redis pub/sub and WebSocket are
ephemeral; neither proves recipient receipt. Subscriber loss disconnects affected
sockets so they recover through authorized REST. A failed/slow owner emits no
cached content. Subscription work is serialized per device/conversation; pending
work is capped at 64 per device, outgoing buffered events at 32, and one catch-up
at ten 50-ID pages before forcing repair. No throughput or exactly-once delivery
SLA is claimed.

Contracts are `contracts/openapi/community-realtime-v1.json`, the canonical
Community OpenAPI/fragment and `contracts/websocket/community/`. The accepted
proposal artifacts and appointment WebSocket schemas stay unchanged. The only
persisted extension is a minimized fact from the existing watermark transaction;
no new table, field or migration is needed. See
[MB-689 evidence](../docs/story-mb-689-realtime-community-evidence.md) for exact
verification, load/reconnect coverage and consumer handoff. MB-690 owns presence,
typing and shared receipt behavior; MB-691 owns durable notifications. Frontend,
BFF key provisioning, shared-environment activation and epic acceptance remain
their owning delivery lanes, and no `ui-ready` claim is made here.
