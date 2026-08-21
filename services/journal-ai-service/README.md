# Journal and AI Service

NestJS service on Node.js that owns private journal entries and coordinates AI analysis for MentalBridge.

## Current scope

This baseline provides:

- Node.js 24 runtime
- strict TypeScript with ESM
- NestJS application bootstrap
- local-only dotenv loading with Zod configuration validation
- liveness and readiness endpoints
- Pino request logs with correlation IDs
- Prometheus metrics endpoint
- graceful shutdown for `SIGINT` and `SIGTERM`
- lint, type-check, test, and build scripts
- production multi-stage Docker image

Journal APIs, configuration validation, OpenAPI, MongoDB migrations, and AI integrations are introduced by subsequent work items.

## Requirements

- Node.js 24
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

| Variable | Required | Default | Purpose |
| --- | --- | --- | --- |
| `PORT` | No | `3000` | HTTP port, from 1 through 65535 |
| `NODE_ENV` | No | `development` | Runtime environment: `development`, `test`, or `production` |
| `LOG_LEVEL` | No | `info` | Pino log level |
| `SERVICE_NAME` | No | `journal-ai-service` | Service label used in logs and metrics |
| `MONGODB_URI` | No | `mongodb://localhost:27017` | MongoDB server used by `migrate-mongo` |
| `MONGODB_DATABASE` | No | `mentalbridge_journal_ai` | MongoDB database owned by this service |

Local `.env` files are loaded only outside production and never override real environment variables. Do not commit local `.env` files or secrets.

## Operations endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/health/live` | Process liveness probe |
| `GET` | `/health/ready` | Readiness probe for the current baseline |
| `GET` | `/metrics` | Prometheus metrics scrape endpoint |

Incoming requests echo or receive an `x-correlation-id` response header. Request logs include the same correlation ID and redact authorization and cookie headers.

## Contracts and migrations

- Journal CRUD OpenAPI: `contracts/openapi/journal-ai-service-v1.yaml`
- MongoDB migration baseline: `migrations/001_journal_entries_baseline.cjs`

Run `npm run contract:check` before implementing journal handlers. Run `npm run migration:check` for static migration validation, and `npm run migrate:up` against a local disposable MongoDB when database access is available.

## Graceful shutdown

The service stops accepting new connections after receiving `SIGINT` or `SIGTERM`. Existing connections have up to 10 seconds to close before they are terminated.
