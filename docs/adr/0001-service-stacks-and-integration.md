# ADR 0001: Service Stacks and Integration Transports

- Status: Accepted
- Date: 2026-08-11
- Note: ADR 0006 supersedes the Node.js framework choice in this ADR and ADR 0003. Service boundaries and transport decisions in this ADR remain accepted.
- AI worker update: ADR 0011 supersedes the requirement to operate `phobert-worker` as a baseline deployable. PhoBERT is now an optional deferred benchmark baseline.
- Deployment update (2026-09-07): dev and staging share the existing service-owned cloud PostgreSQL databases and MongoDB deployment. Compose treats them as external dependencies; disposable local databases are limited to CI/integration tests. Production will receive a separate data plane when provisioned.

## Context

MentalBridge needs transactionally correct identity/care/booking behavior, I/O-heavy journal/AI/notification processing, live chat and notification delivery, and an isolated Python ML ecosystem. Multiple languages must interoperate without sharing framework-specific models or databases. Asynchronous work must survive restarts, support independent consumers, and be replayable for projections and audit. Live socket fan-out and presence need lower-latency ephemeral coordination than a durable event log.

## Decision

Use these deployable business modules:

- Spring Boot: `identity-service`, `care-service`, `consultation-service`;
- Node.js/TypeScript with NestJS: `journal-ai-service`, `realtime-service`, `content-notification-service`; their framework and supporting stack are fixed by ADR 0006;
- Python: `phobert-worker`.

Use these integration boundaries:

- REST with JSON DTOs and OpenAPI for synchronous business APIs and every service-to-service query;
- WebSocket only from client applications to `realtime-service` for chat, presence, receipts, and live in-app notification delivery;
- Kafka for durable asynchronous commands, integration events, task distribution, replayable projections, audit/reporting, and deletion workflows;
- Redis only for bounded ephemeral capabilities: TTL presence, socket/room mapping, cross-instance WebSocket fan-out, rate limiting, short-lived delivery/idempotency state, and hashed OTP challenges with expiry;
- PostgreSQL/MongoDB owned by services as authoritative durable business stores.

Use these infrastructure and provider adapters:

- Liquibase for append-only, service-owned PostgreSQL migrations in Spring Boot modules;
- `migrate-mongo` for versioned MongoDB migrations in Node.js modules that own MongoDB collections;
- Cloudinary for file/object storage, using private or authenticated assets and signed, time-limited access for sensitive files;
- Brevo API for outbound transactional email, including OTP delivery; the provider does not own OTP validity or verification state.

Kafka is not used as synchronous request/reply and Redis is not used as a durable source of truth. Redis must not cache general PostgreSQL/MongoDB query results, repository responses, durable messages, consent, risk, appointment, or notification facts. Database performance is addressed through query design, indexes, pagination, connection-pool sizing, and measured database tuning. Realtime message acknowledgment does not wait for unrelated Kafka consumers: `realtime-service` persists the message in MongoDB, acknowledges and performs Redis socket fan-out, then publishes durable integration facts through a recoverable outbox/publisher design.

## Rationale

Spring Boot provides a consistent security, transaction, migration, locking, and resilience model for the three rule-heavy relational services. NestJS with strict TypeScript provides a consistent application framework for the three I/O-heavy Node.js services while OpenAPI and JSON Schema prevent framework-specific cross-service coupling. Python remains isolated to the PhoBERT ecosystem.

Kafka is selected because MentalBridge has multiple independent asynchronous consumers, needs durable retention/replay for projections and audit, and benefits from partition ordering by aggregate. The decision is not based on Kafka being the lowest-latency transport. Redis/WebSocket handles ephemeral low-latency client delivery, while Kafka handles durable cross-service facts and tasks.

## Consequences

- The team operates Java, TypeScript, and Python toolchains plus Kafka and Redis locally.
- OpenAPI and JSON Schema compatibility tests are mandatory because implementation classes cannot be shared across languages.
- PostgreSQL state changes publish through a transactional outbox. MongoDB producers require an equivalent recoverable, idempotent publication design.
- Kafka consumers assume at-least-once delivery, deduplicate by message ID, and commit offsets only after local side effects succeed.
- Redis loss may temporarily degrade presence and cross-instance live delivery but cannot corrupt durable data; REST history/resynchronization repairs client state.
- Redis loss invalidates outstanding OTP challenges and other ephemeral state safely; it never makes a durable message or business record unavailable.
- Provider SDKs remain infrastructure adapters behind application ports. Cloudinary identifiers and Brevo delivery identifiers may be persisted where needed, but provider responses and credentials are not business contracts.
- Dev and staging use the shared external pre-production data plane, while CI/integration tests use disposable local database infrastructure. Every service scaffold includes validated production configuration from the beginning: external secret injection, TLS-capable URLs, bounded pools/timeouts, production-safe migration settings, health/readiness, metrics, and no dependency on repository `.env` files. Production credentials and separate endpoints are supplied only by the deployment environment in later phases.
- Current authorization and consent queries fail closed through owner REST APIs. Eventually consistent Kafka projections are used only where staleness is explicitly acceptable.
- The edge proxy remains infrastructure without business orchestration; adding another business service or transport requires a new ADR.

## Rejected alternatives

- RabbitMQ as the integration broker: not selected because the team chose Kafka and values retained/replayable event streams and consumer projections.
- Redis/BullMQ as the shared broker: rejected because it would make cross-language durable integration dependent on an internal Node.js job mechanism.
- WebSocket between services: rejected because it complicates contracts and resilience without improving synchronous domain queries.
- Direct cross-service database reads: rejected because they break ownership, independent deployment, authorization, and schema evolution.
