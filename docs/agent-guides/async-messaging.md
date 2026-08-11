# Kafka, Redis, and Realtime Messaging

Kafka is the durable integration broker. REST remains the mechanism for synchronous queries and immediate business commands. WebSocket is the client delivery channel owned by `realtime-service`. Redis provides ephemeral coordination for realtime replicas; it is not a durable broker or business database.

## Responsibility split

| Technology | Use it for | Do not use it for |
| --- | --- | --- |
| Kafka | durable domain events, asynchronous commands, task distribution, replayable projections, audit/reporting feeds | synchronous queries, request/reply, presence, direct browser connections |
| Redis | online presence with TTL, socket/room routing, short-lived cache, rate limits, cross-instance WebSocket fan-out | authoritative consent, messages, notifications, appointments, durable job state |
| WebSocket | authenticated chat, presence changes, receipts, and live in-app notification delivery to clients | service-to-service queries, durable storage, business transactions |
| REST/JSON | synchronous APIs, current authorization/consent queries, command status polling | background fan-out or replayable event distribution |

Kafka is not placed in the critical response path merely to claim that an API is asynchronous. `202 Accepted` is used only when the product operation genuinely completes later. Realtime chat may acknowledge after its authoritative message write and then publish integration facts; Kafka consumers must not delay the sender acknowledgment unless the domain explicitly requires their outcome.

## Message categories and contract

- **Integration event**: immutable past-tense fact, for example `AppointmentStatusChanged`.
- **Asynchronous command**: imperative request with exactly one logical owner, for example `AnalyzeJournalRevision`.

Do not publish vague CRUD messages such as `EntityUpdated`. Messages carry the minimum data needed and stable identifiers, never access tokens, passwords, raw journal/chat bodies, assessment answer text, or provider secrets.

Required envelope:

```json
{
  "messageId": "UUID",
  "messageType": "AppointmentStatusChanged",
  "schemaVersion": 1,
  "occurredAt": "2026-08-11T08:30:00Z",
  "producer": "consultation-service",
  "correlationId": "UUID",
  "causationId": "UUID-or-null",
  "aggregateId": "UUID",
  "aggregateVersion": 3,
  "data": {}
}
```

JSON Schema in `contracts/events/` is the source of truth and is compatible with both Java and TypeScript. A Schema Registry may enforce compatibility when Kafka infrastructure is introduced. Additive fields are optional; breaking semantics require a new version/topic migration plan.

## Kafka topic and consumer rules

- Create topics through version-controlled deployment configuration, never divergent auto-creation in each application.
- Use explicit names such as `mentalbridge.consultation.appointment-status.v1` and `mentalbridge.ai.analyze-journal.v1`.
- Key aggregate events by `aggregateId` so changes for one aggregate stay in one partition. Never claim ordering across partitions.
- Each logical consumer owns a consumer group. Independent projections use different groups; horizontally scaled replicas of one handler share a group.
- Production topics are durable, replicated, and configured with reviewed retention. Producers use `acks=all` and idempotence; tune batching/`linger.ms` from measurements without weakening durability.
- Use bounded retry topics with increasing delay and a dead-letter topic. Do not retry poison messages in the main partition indefinitely.
- Set message size, poll interval, batch size, concurrency, and back-pressure limits. Large files and raw journal/chat bodies belong in owned storage, not Kafka.
- Use compacted topics only for deliberate latest-state projections. Business event history uses retention appropriate to audit, replay, privacy, and deletion policies.

## Producer guarantees

When a Kafka record represents a PostgreSQL change, write the aggregate and outbox record in the same local transaction. A relay claims unpublished rows with safe concurrent locking, publishes using an idempotent producer, and marks them published only after Kafka acknowledges the record. Relay retries are bounded and observable.

Kafka and the service database do not form one transaction. Duplicate delivery is expected. Never implement “save, then publish” as two uncoordinated success steps.

MongoDB-owned workflows require an explicitly documented equivalent: an owned outbox collection with a unique `messageId`, or a design whose state transition can be safely rediscovered and republished. Do not claim exactly-once business processing from Kafka producer settings alone.

## Consumer guarantees

1. Validate the envelope and schema version before business handling.
2. Deduplicate `messageId` in the same local database transaction as the side effect when possible.
3. Commit the Kafka offset only after the side effect commits.
4. Treat an already processed message as success and allow the offset to advance.
5. Classify errors as transient or permanent. Transient failures go through bounded retry topics; permanent schema/business failures go to dead letter with safe diagnostics.
6. Make handlers resilient to duplicate, delayed, reordered, and replayed records.

If aggregate order matters, compare `aggregateVersion`, ignore stale versions, and detect gaps. A replay must not resend user-facing email/push or repeat a non-idempotent external side effect without an explicit replay policy.

## WebSocket and Redis rules

- `realtime-service` authenticates the handshake and authorizes every room subscription and command. A valid token alone does not grant conversation access.
- WebSocket inbound/outbound JSON is defined in `contracts/websocket/` with event name, schema version, correlation/message IDs, acknowledgments, errors, size limit, and compatibility policy.
- The server assigns authoritative sender and conversation context; never trust client-supplied owner, role, delivery state, or specialist approval.
- A client supplies `clientMessageId`; retries/reconnects return the original message instead of creating duplicates.
- Persist durable chat/message state in MongoDB before acknowledging accepted content. Redis contains only connection and presence state with TTL.
- Use Redis pub/sub or the approved Socket.IO Redis adapter for cross-instance fan-out. Kafka carries durable integration facts to other services; Redis carries low-latency ephemeral socket delivery.
- On Redis failure, durable writes and REST reads remain correct. Presence may degrade to unknown and cross-instance live delivery may pause; reconnect/history retrieval repairs the client view.
- `content-notification-service` persists the notification and publishes `NotificationCreated`; `realtime-service` consumes it and emits a minimal client notification over the user's existing socket. Email/push provider delivery remains owned by `content-notification-service`.
- Define reconnect, heartbeat, session expiry, back-pressure, maximum payload, rate limit, ordering, and resynchronization behavior. Clients recover missed data through REST using a cursor/version.

## Consistency and observability

- Local aggregate invariants use one database transaction plus constraints/locking.
- Cross-service workflows use Kafka and compensating actions; do not attempt distributed transactions.
- A projection is eventually consistent and exposes its freshness/version where relevant.
- Safety-critical paths do not wait for Kafka, Redis, or WebSocket. Assessment scoring, safety flags, immediate guidance, and authoritative consent decisions remain local to their owners.

Record safe metrics for producer acknowledgments, outbox lag, consumer lag, handler latency, retries, dead letters, Redis availability, active sockets, reconnects, fan-out failures, and delivery delay. Propagate correlation/causation IDs. Never log sensitive payloads.
