# ADR 0003: Node.js library stack without NestJS

- Status: Superseded by ADR 0006
- Date: 2026-08-17
- Supersedes: the NestJS-specific parts of ADR 0001
- Superseded on: 2026-08-22
- Note: ADR 0007 supersedes this record's Node.js PostgreSQL migration-tool wording.

ADR 0006 replaces this framework decision. This record remains unchanged below to preserve the reason the explicit-library approach was previously selected.

## Context

ADR 0001 assigned Journal/AI, Realtime, and Content/Notification to TypeScript and NestJS. The backend team has three Node.js developers and has explicitly chosen not to use NestJS. The service boundaries, data ownership, and transport decisions remain valid, but the implementation stack must be understandable and operable without a framework-specific module, decorator, dependency-injection, or ORM model.

## Decision

Keep the three deployables and their existing ownership:

- `journal-ai-service`;
- `realtime-service`;
- `content-notification-service`.

Implement them with Node.js 24 LTS and strict TypeScript using explicit libraries and constructor composition. Use Express 5 for REST, OpenAPI boundary validation, Zod for non-REST runtime schemas and configuration, official database clients, Socket.IO for client realtime delivery, KafkaJS, the official Redis client, Pino, OpenTelemetry, Prometheus metrics, Vitest, Supertest, and Testcontainers.

Use the official MongoDB driver rather than an ODM. MongoDB-owning services use append-only `migrate-mongo` migrations. Node-owned PostgreSQL data uses `pg` at runtime and repository-standard Liquibase changelogs run through the CLI/container. Detailed library responsibilities, source structure, scripts, and module-specific choices are defined in `docs/nodejs-service-stack.md`.

Do not create a shared internal framework that recreates NestJS. Services may align tooling and dependency versions, but do not share business models, persistence models, source packages, or configuration.

The communication and storage decisions from ADR 0001 are unchanged: REST/JSON for synchronous calls, WebSocket only between clients and Realtime, Kafka for durable asynchronous messages, Redis for bounded ephemeral coordination, and owner-specific PostgreSQL/MongoDB stores.

## Consequences

- Developers must compose routes, use cases, repositories, clients, configuration, and lifecycle explicitly.
- TypeScript strict mode and runtime validation remain mandatory because framework decorators no longer provide boundary metadata.
- OpenAPI, Kafka JSON Schema, and WebSocket JSON Schema remain the language-neutral sources of truth.
- Each service owns a package lockfile, build, Dockerfile, migrations, observability, and tests.
- Sprint 1 can assign one independently reviewable Node service foundation to each Node.js developer.
- CI installs and verifies packages but does not deploy; CD requires a later approved environment and credentials.

## Rejected alternatives

- NestJS: rejected by the team for these services.
- One shared Node.js application containing all three domains: rejected because it erases ownership and independent deployment boundaries.
- Mongoose as a shared domain model: rejected because persistence documents must remain infrastructure details and MongoDB validation already has an explicit migration source.
- Rebuilding decorators and a dependency-injection container internally: rejected because it adds framework complexity without a product requirement.
