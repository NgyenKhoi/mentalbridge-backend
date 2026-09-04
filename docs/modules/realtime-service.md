# Realtime Service specification

## Business boundary

Realtime owns conversations, encrypted messages, attachments metadata, tombstones, receipts, client WebSocket sessions, presence, and owner-specific message moderation. MongoDB is durable truth; Redis holds only expiring presence/routing/fan-out/rate/idempotency state. Consultation/Care remain authorization authorities.

## Use cases and acceptance

| Capability | Main behavior | Acceptance |
| --- | --- | --- |
| Conversation eligibility | Create/open exactly one conversation for an eligible confirmed `IN_APP_CHAT` appointment | No unrestricted specialist direct message; every open/subscribe/send reauthorizes appointment status and `[scheduledStartAt, scheduledEndAt)`; dependency uncertainty fails closed |
| Messaging | Persist message then acknowledge/fan out with client idempotency | Same sender/clientMessageId returns original; server assigns sender/context; Redis/Kafka failure cannot lose durable message |
| History/reconnect | Cursor list and resynchronize missed state | Stable ordering/tie-breaker; reconnect recovers through REST; back-pressure and size limits explicit |
| Receipts/presence | Maintain high-water delivered/read marks and TTL presence | Stale receipt cannot move backward; presence may become unknown on Redis failure; no durable content in Redis |
| Tombstone/moderation | Delete display content and process scoped report/action | Retention policy decides ciphertext removal; report grants no broad conversation access; evidence/action audited |
| Live notification | Consume safe `NotificationCreated` and emit to existing session | Durable notification remains owned elsewhere; duplicate event does not duplicate side effect beyond defined delivery semantics |

## Implementation design

- Feature slices: `conversations`, `messages`, `history`, `receipts`, `presence`, `websocket`, `notification-delivery`, `message-moderation`.
- Runtime: Node.js 22 or newer, strict TypeScript, NestJS 11, Socket.IO 4 through NestJS gateways, official MongoDB and Redis clients, `migrate-mongo`, KafkaJS, Pino, OpenTelemetry, Vitest, and Testcontainers as defined in `docs/nodejs-service-stack.md`.
- OpenAPI owns history/recovery; versioned WebSocket schemas own commands/acks/errors; Kafka schemas own minimized integration facts.
- Conversation history may remain read-only after a slot under retention policy, but join/send never becomes 24/7 specialist messaging. Subscription cancellation closes future conversations immediately; an already-started confirmed session remains writable only until its scheduled end.
- `IN_APP_VIDEO` is future intent only. Realtime does not implement signalling, rooms, provider credentials, presence evidence, recording, or fallback until a separate contract/ADR assigns those responsibilities.
- Mongo migrations enforce validators/indexes. Persist before ack, then Redis fan-out and recoverable Kafka publication. Attachments use private object storage, not Mongo blobs.
- Define heartbeat, reconnect, ordering, payload/back-pressure/rate limits and cross-instance failure semantics before gateway implementation.

## Ordered tasks

- [x] RT-01 Scaffold the NestJS/TypeScript service with bounded feature packages, Socket.IO gateway, typed configuration, health/readiness, lint, test, and build commands.
- [ ] RT-02 Resolve appointment join grace, read-only history, attachment, tombstone/retention and moderation policies; define video separately before enabling that channel.
- [ ] RT-03 Define conversation/history OpenAPI, WebSocket schemas and Kafka event contracts.
- [x] RT-04 Add migrate-mongo validators/indexes and encrypted-content/data documentation.
- [ ] RT-05 Implement owner-authorized conversation lifecycle and REST history.
- [ ] RT-06 Implement authenticated WebSocket subscribe/send, idempotent persist-before-ack and Redis fan-out.
- [ ] RT-07 Implement receipts, presence, reconnect/resync and live notification delivery.
- [ ] RT-08 Implement tombstone, scoped reporting and message moderation.
- [ ] RT-09 Verify unauthorized subscription, duplicate send, ordering, reconnect, Redis loss, Kafka retry, dependency timeout and rate/back-pressure limits.
- [ ] RT-10 Add observability/configuration, README, and pass Node/contract/Mongo/Redis/Kafka gates.

## Sprint 1 boundary

Sprint 1 covers RT-01, the foundational contract and migration parts of RT-03/RT-04, authenticated socket connection, TTL presence, and durable MongoDB message/history primitives. Appointment eligibility integration, user-visible production chat, Redis cross-instance fan-out, Kafka publication, receipts, moderation, and live notification delivery remain deferred until their owner contracts exist.

The current foundation publishes Realtime REST and WebSocket v1 contracts, validates Identity-issued RS256 access tokens, maintains bounded TTL presence, encrypts and idempotently persists messages before acknowledgement, and provides cursor history behind an eligibility port. The production eligibility adapter deliberately fails closed until Consultation publishes the current appointment authorization contract; integration tests supply only synthetic authorization and disposable MongoDB/Redis infrastructure.
