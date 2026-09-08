# Journal and AI Service

NestJS service on Node.js that owns private journal entries and coordinates AI analysis for MentalBridge.

## Current scope

This baseline provides:

- Node.js 22 or newer runtime
- strict TypeScript with ESM
- NestJS application bootstrap
- local-only dotenv loading with Zod configuration validation
- Identity-issued RS256 JWT verification with exact issuer and audience checks
- MongoDB-aware liveness and readiness endpoints
- Pino request logs with correlation IDs
- Prometheus metrics endpoint
- graceful shutdown for `SIGINT` and `SIGTERM`
- lint, type-check, test, and build scripts
- production multi-stage Docker image

This baseline publishes the journal API contract and MongoDB validator/index migration before the handlers are introduced by MB-91. AI integrations remain deferred.

## Requirements

- Node.js 22 or newer
- npm 11
- Docker, when building the container image

## Commands

```bash
npm ci
npm run dev
npm run lint
npm run typecheck
npm test
npm run contract:check
npm run migration:check
npm run build
npm start
```

## Configuration

| Variable                                   | Required   | Default                                        | Purpose                                                                                 |
| ------------------------------------------ | ---------- | ---------------------------------------------- | --------------------------------------------------------------------------------------- |
| `JOURNAL_AI_PORT`                          | No         | `3000`                                         | HTTP port, from 1 through 65535                                                         |
| `NODE_ENV`                                 | No         | `development`                                  | Runtime environment: `development`, `test`, or `production`                             |
| `JOURNAL_AI_LOG_LEVEL`                     | No         | `info`                                         | Pino log level                                                                          |
| `JOURNAL_AI_MONGODB_URI`                   | Production | `mongodb://localhost:27017` outside production | MongoDB server used by the service and `migrate-mongo`                                  |
| `JOURNAL_AI_MONGODB_DATABASE`              | Production | `mentalbridge_journal_ai` outside production   | MongoDB database owned by this service                                                  |
| `JOURNAL_AI_MONGODB_CONNECTION_TIMEOUT_MS` | No         | `2000`                                         | MongoDB connect/server-selection timeout from 100 through 30000 milliseconds            |
| `IDENTITY_JWT_ISSUER`                      | Yes        | None                                           | Exact Identity issuer accepted by this resource service                                 |
| `IDENTITY_JWT_AUDIENCE`                    | Yes        | None                                           | Exact MentalBridge API audience accepted by this resource service                       |
| `IDENTITY_JWT_KEY_ID`                      | Yes        | None                                           | Exact active Identity signing-key identifier accepted by this resource service          |
| `IDENTITY_JWT_PUBLIC_KEY`                  | Yes        | None                                           | X.509 RSA public key matching the Identity signing key; the private key is never shared |

Local `.env` files are loaded only outside production and never override real environment variables. The repository and service examples use the same service-scoped keys. Copy `.env.example` to `.env` for local development, then replace placeholder JWT values with local credentials. Do not commit local `.env` files or secrets.

## Operations endpoints

| Method | Path            | Purpose                                                               |
| ------ | --------------- | --------------------------------------------------------------------- |
| `GET`  | `/health/live`  | Process liveness probe                                                |
| `GET`  | `/health/ready` | Readiness probe that returns success only when owned MongoDB responds |
| `GET`  | `/metrics`      | Prometheus metrics scrape endpoint                                    |

Incoming requests echo a valid bounded `x-correlation-id` or receive a generated one. Request logs include the same correlation ID and redact authorization and cookie headers. Non-public application routes require an Identity-issued RS256 bearer token; signature, issuer, audience, lifetime, subject, token ID, and roles are validated before a principal is attached to the request.

## Contracts and migrations

- Journal CRUD OpenAPI: `../contracts/openapi/journal-ai-service-v1.yaml`
- MongoDB migration baseline: `migrations/001_journal_entries_baseline.cjs`

Run `npm run contract:check` before implementing journal handlers. Run `npm run migration:check` for static migration validation, and `npm run migrate:up` against a local disposable MongoDB when database access is available.

## Graceful shutdown

The service stops accepting new connections after receiving `SIGINT` or `SIGTERM`. Existing connections have up to 10 seconds to close before they are terminated.
