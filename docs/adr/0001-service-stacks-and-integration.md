# ADR 0001: Service Stacks and Integration Transports

- Status: Accepted
- Date: 2026-08-11

## Context

MentalBridge needs transactionally correct identity/care/booking behavior, I/O-heavy journal/AI/notification processing, live chat and notification delivery, and an isolated Python ML ecosystem. Multiple languages must interoperate without sharing framework-specific models or databases. Asynchronous work must survive restarts, support independent consumers, and be replayable for projections and audit. Live socket fan-out and presence need lower-latency ephemeral coordination than a durable event log.

## Decision

Use these deployable business modules:

- Spring Boot: `identity-service`, `care-service`, `consultation-service`;
- NestJS/TypeScript: `journal-ai-service`, `realtime-service`, `content-notification-service`;
- Python: `phobert-worker`.

Use these integration boundaries:

- REST with JSON DTOs and OpenAPI for synchronous business APIs and every service-to-service query;
- WebSocket only from client applications to `realtime-service` for chat, presence, receipts, and live in-app notification delivery;
- Kafka for durable asynchronous commands, integration events, task distribution, replayable projections, audit/reporting, and deletion workflows;
- Redis for TTL presence, socket/room mapping, short-lived cache, rate limiting, and cross-instance WebSocket fan-out;
- PostgreSQL/MongoDB owned by services as authoritative durable business stores.

Kafka is not used as synchronous request/reply and Redis is not used as a durable source of truth. Realtime message acknowledgment does not wait for unrelated Kafka consumers: `realtime-service` persists the message, acknowledges and performs Redis socket fan-out, then publishes durable integration facts through a recoverable outbox/publisher design.

## Rationale

Spring Boot provides a consistent security, transaction, migration, locking, and resilience model for the three rule-heavy relational services. NestJS aligns the three I/O-heavy services around strict TypeScript, MongoDB/provider clients, Kafka consumers, and WebSocket infrastructure. Python remains isolated to the PhoBERT ecosystem.

Kafka is selected because MentalBridge has multiple independent asynchronous consumers, needs durable retention/replay for projections and audit, and benefits from partition ordering by aggregate. The decision is not based on Kafka being the lowest-latency transport. Redis/WebSocket handles ephemeral low-latency client delivery, while Kafka handles durable cross-service facts and tasks.

## Consequences

- The team operates Java, TypeScript, and Python toolchains plus Kafka and Redis locally.
- OpenAPI and JSON Schema compatibility tests are mandatory because implementation classes cannot be shared across languages.
- PostgreSQL state changes publish through a transactional outbox. MongoDB producers require an equivalent recoverable, idempotent publication design.
- Kafka consumers assume at-least-once delivery, deduplicate by message ID, and commit offsets only after local side effects succeed.
- Redis loss may temporarily degrade presence and cross-instance live delivery but cannot corrupt durable data; REST history/resynchronization repairs client state.
- Current authorization and consent queries fail closed through owner REST APIs. Eventually consistent Kafka projections are used only where staleness is explicitly acceptable.
- The edge proxy remains infrastructure without business orchestration; adding another business service or transport requires a new ADR.

## Rejected alternatives

- RabbitMQ as the integration broker: not selected because the team chose Kafka and values retained/replayable event streams and consumer projections.
- Redis/BullMQ as the shared broker: rejected because it would make cross-language durable integration dependent on an internal Node.js job mechanism.
- WebSocket between services: rejected because it complicates contracts and resilience without improving synchronous domain queries.
- Direct cross-service database reads: rejected because they break ownership, independent deployment, authorization, and schema evolution.
