# ADR 0006: NestJS framework for Node.js services

- Status: Accepted
- Date: 2026-08-22
- Supersedes: ADR 0003 and the Node.js framework-specific wording in ADR 0001
- Note: ADR 0007 supersedes this record's Node.js PostgreSQL migration-tool wording; the NestJS decision remains accepted.

## Context

MentalBridge has three independently deployable Node.js/TypeScript services: Journal/AI, Realtime, and Content/Notification. ADR 0003 previously selected Express with manual composition and explicitly rejected NestJS. The repository owner has now selected NestJS as the common application framework so these services share one module, dependency-injection, controller, guard, interceptor, configuration, lifecycle, and testing model.

This decision changes the application framework only. Service ownership, storage authority, synchronous and asynchronous transports, safety boundaries, and language-neutral contracts remain unchanged.

## Decision

Implement `journal-ai-service`, `realtime-service`, and `content-notification-service` with Node.js 22 or newer, strict TypeScript, and NestJS 11. Use the supported Nest Express platform adapter for HTTP unless a later ADR approves a different adapter. Framework decorators and dependency injection remain transport/composition mechanisms and must not become cross-service business contracts.

Keep these supporting decisions:

- OpenAPI 3.1 is the REST source of truth; NestJS controllers, DTOs, validation pipes, guards, responses, and error filters must conform to it.
- Zod validates configuration, provider output, Kafka payloads, and WebSocket envelopes. REST validation must not introduce rules that conflict with OpenAPI.
- Identity-issued JWTs are verified in each data-owning service; NestJS guards enforce actor and resource authorization and fail closed.
- MongoDB services use the official driver and append-only `migrate-mongo` migrations rather than sharing Mongoose domain models.
- Node-owned PostgreSQL services use `pg` and repository-standard Liquibase execution rather than schema auto-creation.
- Realtime client delivery uses Socket.IO through NestJS gateways; service-to-service queries remain REST and durable asynchronous work remains Kafka.
- Pino, OpenTelemetry, Prometheus, Vitest, Supertest, and Testcontainers remain the standard observability and verification tools.

Organize code by feature module. Controllers/gateways stay thin, application providers coordinate use cases, domain code remains framework-independent where domain behavior warrants it, and database/provider clients stay in infrastructure providers. A global/shared module contains only stable technical primitives and never shared business DTOs, persistence models, or authorization decisions.

The canonical dependency responsibilities, source structure, scripts, and module-specific choices are maintained in `docs/nodejs-service-stack.md`.

## Migration and compatibility

- Existing Node.js work based on ADR 0003 must first synchronize with `dev`, then migrate deliberately to NestJS rather than copying files between branches.
- A feature branch owned by one developer may rebase onto `origin/dev` and push with `--force-with-lease`; a shared feature branch merges `origin/dev` to avoid rewriting collaborators' history.
- Each deployable remains a top-level repository directory such as `journal-ai-service/`. NestJS adoption does not introduce a parallel `services/` wrapper without a repository-wide migration decision.
- OpenAPI paths and payloads do not change merely because the implementation framework changes.
- Existing Express implementations may be replaced service by service, but a PR must not leave two production bootstraps or two conflicting REST contracts for one service.
- Framework migration does not authorize changing service ownership, database shapes, encryption, retention, consent, risk, or authorization policy.

## Consequences

- The Node.js team maintains one NestJS major version and aligned core/platform packages across all three services.
- Service scaffolds include modules, controllers or gateways, guards, interceptors/filters, typed configuration, health/readiness, observability, graceful shutdown, and automated tests.
- Reviewers reject direct framework DTO sharing, business logic in controllers, unbounded global modules, or persistence models exposed as REST/event contracts.
- NestJS reduces manual composition differences but adds framework lifecycle and dependency-injection behavior that must be covered by component tests.
- CI installs and verifies packages but does not deploy; CD still requires a later approved environment and credentials.

## Rejected alternatives

- Continue the ADR 0003 Express-only stack: rejected by the repository owner in favor of NestJS standardization.
- One shared NestJS application for all three domains: rejected because it erases independent deployment and ownership boundaries.
- Shared framework entities/DTO packages across services: rejected because OpenAPI and JSON Schema remain the language-neutral contracts.
- Mongoose models as shared domain contracts: rejected because MongoDB documents remain owner-specific infrastructure details.
