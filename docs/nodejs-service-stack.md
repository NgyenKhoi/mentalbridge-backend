# Node.js service stack

This document is the source of truth for the three Node.js services. ADR 0003 records the decision to use Node.js libraries directly instead of NestJS.

## Runtime and language baseline

- Node.js 24 LTS with npm and committed lockfiles.
- TypeScript in strict mode, compiled to ESM JavaScript.
- `tsx` is used only for local development and scripts; production runs compiled output with `node`.
- Each service is an independently deployable package with its own `package.json`, configuration, database credentials, migrations, tests, Dockerfile, health endpoints, and README.
- Services may align dependency versions, but they do not import another service's source, domain model, persistence model, or environment configuration.

## Shared library decisions

| Concern | Library/tool | Rule |
| --- | --- | --- |
| REST server | Express 5 | Routes are thin adapters that call one application use case. |
| REST contract | OpenAPI 3.1, `express-openapi-validator`, `@apidevtools/swagger-parser` | OpenAPI is authored first; requests and responses are validated at the boundary. |
| Runtime/domain validation | Zod 4 | Use for configuration, provider output, Kafka payloads, and WebSocket messages; do not create a second conflicting REST contract. |
| Authentication | `jose` | Verify Identity-issued JWTs against configured public keys and enforce resource authorization in the data owner. |
| HTTP client | Node `fetch`/Undici plus Cockatiel | Every dependency has an explicit timeout, bounded safe retry, circuit breaker, and typed failure mapping. |
| MongoDB | Official `mongodb` driver | No ODM. Repositories map documents to domain types explicitly. |
| MongoDB migrations | `migrate-mongo` | Append-only validators, indexes, and controlled data migrations. |
| PostgreSQL | `pg` | Use parameterized queries and explicit transaction helpers; no shared database access. |
| PostgreSQL migrations | Liquibase CLI/container | Keep the repository's append-only changelog and data-dictionary convention for Node-owned PostgreSQL databases. |
| Kafka | KafkaJS | Validate versioned JSON messages, use stable aggregate keys, bounded retry/dead-letter topics, and idempotent consumers. |
| Redis | `redis` | Ephemeral presence, routing, fan-out, rate limits, and short-lived idempotency only. |
| WebSocket | Socket.IO 4 and `@socket.io/redis-adapter` | Only Realtime exposes client sockets; durable state is persisted before acknowledgement. |
| Security middleware | `helmet`, explicit CORS policy, rate-limit adapter | Defaults are deny-by-default and configuration is environment-specific. |
| Logging | Pino | Structured logs with correlation/trace IDs; redact tokens and sensitive content. |
| Metrics/tracing | `prom-client`, OpenTelemetry Node SDK | Expose liveness, readiness, metrics, and trace propagation without sensitive payloads. |
| Testing | Vitest, Supertest, Testcontainers for Node.js | Unit, HTTP contract, repository, MongoDB/PostgreSQL, Kafka, Redis, and WebSocket tests as applicable. |
| Code quality | ESLint flat config and Prettier | CI runs lint, typecheck, tests, contract/migration checks, and build. |

Do not add an IoC framework or an internal framework that recreates NestJS. Composition happens in one bootstrap module with ordinary constructors and explicit dependencies.

## Standard package scripts

Every Node.js service exposes the same command names:

```json
{
  "scripts": {
    "dev": "tsx watch src/main.ts",
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

```text
<service>/
├── package.json
├── package-lock.json
├── tsconfig.json
├── tsconfig.build.json
├── Dockerfile
├── migrations/
├── scripts/
├── src/
│   ├── <feature>/
│   │   ├── api/
│   │   ├── application/
│   │   ├── domain/
│   │   └── infrastructure/
│   ├── configuration/
│   ├── observability/
│   ├── shared/
│   └── main.ts
└── test/
    ├── contract/
    ├── integration/
    └── fixtures/
```

`shared` contains only stable technical primitives. Domain code imports neither Express nor database, broker, Redis, Socket.IO, or provider clients.

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

GitHub Actions is maintained as one repository-level flow by the repository owner and is not split into service-member tasks in the Sprint 1 Jira import. The flow may run install, format, lint, typecheck, tests, contract/migration checks, and builds for affected modules.

There is no cloud account, hosted server, deployment credential, CD workflow, or release automation in Sprint 1. Deployment work is added only after an environment and credentials are explicitly approved.
