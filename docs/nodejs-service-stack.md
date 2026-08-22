# Node.js service stack

This document is the source of truth for the three Node.js services. ADR 0006 records the decision to standardize them on NestJS 11 and supersedes the Express-only choice in ADR 0003.

## Runtime and language baseline

- Node.js 22 or newer with npm and committed lockfiles.
- TypeScript in strict mode, compiled to ESM JavaScript.
- `tsx` is used only for local development and scripts; production runs compiled output with `node`.
- Each service is an independently deployable package with its own `package.json`, configuration, database credentials, migrations, tests, Dockerfile, health endpoints, and README.
- Services may align dependency versions, but they do not import another service's source, domain model, persistence model, or environment configuration.

## Shared library decisions

| Concern | Library/tool | Rule |
| --- | --- | --- |
| Application framework | NestJS 11 with `@nestjs/platform-express` | Feature modules own controllers, guards, providers, filters and interceptors; controllers remain thin. |
| REST contract | OpenAPI 3.1, NestJS OpenAPI integration, `@apidevtools/swagger-parser` | OpenAPI is authored first; controllers, DTOs, validation and responses conform to the published contract. |
| Runtime/domain validation | Zod 4 | Use for configuration, provider output, Kafka payloads, and WebSocket messages; do not create a second conflicting REST contract. |
| Authentication | `jose` behind NestJS guards | Verify Identity-issued JWTs against configured public keys and enforce actor/resource authorization in the data owner. |
| HTTP client | Node `fetch`/Undici plus Cockatiel | Every dependency has an explicit timeout, bounded safe retry, circuit breaker, and typed failure mapping. |
| MongoDB | Official `mongodb` driver | No ODM. Repositories map documents to domain types explicitly. |
| MongoDB migrations | `migrate-mongo` | Append-only validators, indexes, and controlled data migrations. |
| PostgreSQL | `pg` | Use parameterized queries and explicit transaction helpers; no shared database access. |
| PostgreSQL migrations | `node-pg-migrate` | Keep a service-owned append-only history, explicit npm commands, data-dictionary updates, and disposable-database integration tests. |
| Kafka | KafkaJS | Validate versioned JSON messages, use stable aggregate keys, bounded retry/dead-letter topics, and idempotent consumers. |
| Redis | `redis` | Ephemeral presence, routing, fan-out, rate limits, and short-lived idempotency only. |
| WebSocket | NestJS gateways, Socket.IO 4 and `@socket.io/redis-adapter` | Only Realtime exposes client sockets; durable state is persisted before acknowledgement. |
| Security boundary | NestJS guards/pipes/filters plus `helmet`, explicit CORS and rate limiting | Defaults are deny-by-default and configuration is environment-specific. |
| Logging | Pino | Structured logs with correlation/trace IDs; redact tokens and sensitive content. |
| Metrics/tracing | `prom-client`, OpenTelemetry Node SDK | Expose liveness, readiness, metrics, and trace propagation without sensitive payloads. |
| Testing | Vitest, Supertest, Testcontainers for Node.js | Unit, HTTP contract, repository, MongoDB/PostgreSQL, Kafka, Redis, and WebSocket tests as applicable. |
| Code quality | ESLint flat config and Prettier | Run lint, typecheck, tests, contract/migration checks and build locally; the same basic gates move into CI after the documented `dev` transition. |

Use NestJS dependency injection deliberately. Feature modules expose only the providers required by another feature. Do not create a global module that becomes a service locator, share business DTOs between deployables, or place business rules in controllers, guards, interceptors, filters, or persistence models.

## Standard package scripts

Every Node.js service exposes the same command names:

```json
{
  "scripts": {
    "dev": "nest start --watch",
    "build": "tsc -p tsconfig.build.json",
    "start": "node dist/main.js",
    "lint": "eslint .",
    "format:check": "prettier --check .",
    "typecheck": "tsc -p tsconfig.json --noEmit",
    "test": "vitest run",
    "test:integration": "vitest run --config vitest.integration.config.ts",
    "contract:check": "node scripts/validate-contracts.mjs",
    "migration:check": "node scripts/validate-migrations.mjs"
  }
}
```

## Standard source structure

The tree below is the maximum expected shape for a feature with real boundary complexity, not a scaffold checklist. Start with the shallow NestJS feature shown in `docs/reference-implementations.md`; create `api`, `application`, `domain`, or `infrastructure` only when that directory contains a meaningful boundary rather than one wrapper file.

```text
<service>/
├── package.json
├── package-lock.json
├── nest-cli.json
├── tsconfig.json
├── tsconfig.build.json
├── Dockerfile
├── migrations/
├── scripts/
├── src/
│   ├── <feature>/
│   │   ├── <feature>.module.ts
│   │   ├── api/
│   │   ├── application/
│   │   ├── domain/
│   │   └── infrastructure/
│   ├── configuration/
│   ├── observability/
│   ├── shared/
│   ├── app.module.ts
│   └── main.ts
└── test/
    ├── contract/
    ├── integration/
    └── fixtures/
```

`shared` contains only stable technical primitives. Domain code imports neither NestJS nor database, broker, Redis, Socket.IO, or provider clients. Nest modules wire dependencies; they do not own business behavior.

## Service-specific modules

### `journal-ai-service`

Initial feature packages: `journals`, `analysis-jobs`, `analysis-results`, `llm-providers`, `datasets`, `benchmarks`, and `consented-access`.

- MongoDB owns journal revisions and structured analysis documents.
- PostgreSQL owns durable analysis job, dataset/run metadata, inbox/outbox, and reconciliation state.
- Provider adapters use official OpenAI and Google GenAI SDKs behind application ports.
- Sprint 1 implements service scaffolding and private journal CRUD only. AI calls, Kafka analysis jobs, benchmarks, and provider billing are deferred.

### `realtime-service`

Initial feature packages: `conversations`, `messages`, `history`, `receipts`, `presence`, `websocket`, `notification-delivery`, and `message-moderation`.

- MongoDB owns conversations, messages, receipts, tombstones, and recoverable publication state.
- Redis owns only TTL presence, socket/room routing, cross-instance Socket.IO fan-out, rate limits, and short-lived delivery state.
- Sprint 1 implements the service baseline, versioned socket envelope, authenticated connection, presence lifecycle, and MongoDB message persistence/history foundation. Appointment eligibility integration and production chat activation wait for the Consultation contract.

### `content-notification-service`

Initial feature packages: `resources`, `hotlines`, `preferences`, `notifications`, `templates`, and `provider-delivery`.

- PostgreSQL owns reviewed resources/hotlines, preferences, notifications, templates, delivery attempts, inbox, and outbox.
- Brevo and push clients are replaceable adapters; no provider account is required in ordinary local development or CI.
- Sprint 1 implements the service baseline and reviewed resource/hotline CRUD with synthetic local data. Email/push delivery and Kafka-triggered notifications are deferred.

## Configuration baseline

Each service loads a local `.env` only in development through `dotenv`, then validates the resulting process environment with Zod. Real environment variables take precedence. CI and production do not depend on repository `.env` files.

Required keys are service-scoped, documented in `.env.example`, and bound once at startup. Tests inject configuration directly and must never connect to a developer database.

## Repository CI ownership

GitHub Actions is maintained as one repository-level flow by the repository owner and is not split into service-member tasks in the Sprint 1 Jira import.

`.github/workflows/quality-gate.yml` runs install, formatting, lint, typecheck, unit/HTTP tests, contract/migration validation and build for current Node.js modules on pull requests targeting `dev`. Content/Notification's PostgreSQL Testcontainers suite also runs there. The stable branch-protection check is the final `quality-gate` job; enable it after the workflow is merged and has completed successfully on `dev`.

A missing, skipped, cancelled, unavailable, or red CI status is not evidence that a pull request passed. Reviewers still record local verification and report environment-only blockers explicitly.

There is no cloud account, hosted server, deployment credential, CD workflow, or release automation in Sprint 1. Deployment work is added only after an environment and credentials are explicitly approved.
