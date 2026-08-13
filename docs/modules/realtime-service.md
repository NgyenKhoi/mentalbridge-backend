# Realtime Service specification

## Business boundary

Realtime owns conversations, encrypted messages, attachments metadata, tombstones, receipts, client WebSocket sessions, presence, and owner-specific message moderation. MongoDB is durable truth; Redis holds only expiring presence/routing/fan-out/rate/idempotency state. Consultation/Care remain authorization authorities.

## Use cases and acceptance

| Capability | Main behavior | Acceptance |
| --- | --- | --- |
| Conversation eligibility | Create/open chat only for an eligible consultation relationship | Handshake token is insufficient; every subscribe/send reauthorizes; dependency uncertainty fails closed |
| Messaging | Persist message then acknowledge/fan out with client idempotency | Same sender/clientMessageId returns original; server assigns sender/context; Redis/Kafka failure cannot lose durable message |
| History/reconnect | Cursor list and resynchronize missed state | Stable ordering/tie-breaker; reconnect recovers through REST; back-pressure and size limits explicit |
| Receipts/presence | Maintain high-water delivered/read marks and TTL presence | Stale receipt cannot move backward; presence may become unknown on Redis failure; no durable content in Redis |
| Tombstone/moderation | Delete display content and process scoped report/action | Retention policy decides ciphertext removal; report grants no broad conversation access; evidence/action audited |
| Live notification | Consume safe `NotificationCreated` and emit to existing session | Durable notification remains owned elsewhere; duplicate event does not duplicate side effect beyond defined delivery semantics |

## Implementation design

- Feature slices: `conversations`, `messages`, `history`, `receipts`, `presence`, `websocket`, `notification-delivery`, `message-moderation`.
- OpenAPI owns history/recovery; versioned WebSocket schemas own commands/acks/errors; Kafka schemas own minimized integration facts.
- Mongo migrations enforce validators/indexes. Persist before ack, then Redis fan-out and recoverable Kafka publication. Attachments use private object storage, not Mongo blobs.
- Define heartbeat, reconnect, ordering, payload/back-pressure/rate limits and cross-instance failure semantics before gateway implementation.

## Ordered tasks

- [ ] RT-01 Scaffold strict NestJS and document configuration/library decisions.
- [ ] RT-02 Resolve chat eligibility duration, attachment, tombstone/retention and moderation policies.
- [ ] RT-03 Define conversation/history OpenAPI, WebSocket schemas and Kafka event contracts.
- [ ] RT-04 Add migrate-mongo validators/indexes and encrypted-content/data documentation.
- [ ] RT-05 Implement owner-authorized conversation lifecycle and REST history.
- [ ] RT-06 Implement authenticated WebSocket subscribe/send, idempotent persist-before-ack and Redis fan-out.
- [ ] RT-07 Implement receipts, presence, reconnect/resync and live notification delivery.
- [ ] RT-08 Implement tombstone, scoped reporting and message moderation.
- [ ] RT-09 Verify unauthorized subscription, duplicate send, ordering, reconnect, Redis loss, Kafka retry, dependency timeout and rate/back-pressure limits.
- [ ] RT-10 Add observability/configuration, README, and pass Node/contract/Mongo/Redis/Kafka gates.
