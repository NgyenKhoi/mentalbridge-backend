# Journal and AI Service

NestJS service on Node.js that owns private journal entries and coordinates AI analysis for MentalBridge.

## Current scope

The service provides:

- Node.js 22 or newer runtime
- strict TypeScript with ESM
- NestJS application bootstrap
- local-only dotenv loading with Zod configuration validation
- Identity-issued RS256 JWT verification with exact issuer and audience checks
- MongoDB-aware liveness and readiness endpoints
- Pino request logs with correlation IDs
- Prometheus metrics endpoint
- graceful shutdown for `SIGINT` and `SIGTERM`
- owner-scoped journal create, list, detail, revise, and tombstone deletion
- AES-256-GCM encrypted journal revisions with lifetime idempotency records
- optimistic concurrency through `If-Match` and deterministic cursor pagination
- lint, type-check, test, and build scripts
- production multi-stage Docker image

MB-236 implements the authenticated private journal CRUD contract. AI analysis, provider calls, specialist sharing, dataset import, and benchmarking remain deferred.

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
npm run test:integration
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
| `JOURNAL_AI_ENCRYPTION_KEY`                | Production | Local-only deterministic development key       | Canonical base64 encoding of the 32-byte AES-256-GCM journal encryption key             |
| `JOURNAL_AI_ENCRYPTION_KEY_ID`             | No         | `local-v1`                                     | Identifier of the single active key; ciphertext under another identifier fails closed   |
| `JOURNAL_AI_IDEMPOTENCY_HMAC_KEY`          | Production | Local-only deterministic development key       | Canonical base64 encoding of a separate 32-byte key for command hashes and fingerprints |
| `IDENTITY_JWT_ISSUER`                      | Yes        | None                                           | Exact Identity issuer accepted by this resource service                                 |
| `IDENTITY_JWT_AUDIENCE`                    | Yes        | None                                           | Exact MentalBridge API audience accepted by this resource service                       |
| `IDENTITY_JWT_KEY_ID`                      | Yes        | None                                           | Exact active Identity signing-key identifier accepted by this resource service          |
| `IDENTITY_JWT_PUBLIC_KEY`                  | Yes        | None                                           | X.509 RSA public key matching the Identity signing key; the private key is never shared |

Local `.env` files are loaded only outside production and never override real environment variables. The repository and service examples use the same service-scoped keys. Generate independent random encryption and HMAC keys for every deployed environment (for example, with `crypto.randomBytes(32).toString("base64")`). Do not commit local `.env` files or secrets.

## Operations endpoints

| Method   | Path                           | Purpose                                                               |
| -------- | ------------------------------ | --------------------------------------------------------------------- |
| `GET`    | `/health/live`                 | Process liveness probe                                                |
| `GET`    | `/health/ready`                | Readiness probe that returns success only when owned MongoDB responds |
| `GET`    | `/metrics`                     | Prometheus metrics scrape endpoint                                    |
| `POST`   | `/api/v1/journals`             | Create an encrypted journal entry with an idempotency key             |
| `GET`    | `/api/v1/journals`             | List the authenticated owner's entries by opaque cursor               |
| `GET`    | `/api/v1/journals/{journalId}` | Read one owner-scoped entry                                           |
| `PATCH`  | `/api/v1/journals/{journalId}` | Append a revision guarded by `If-Match`                               |
| `DELETE` | `/api/v1/journals/{journalId}` | Create an idempotent owner-scoped tombstone                           |

Incoming requests echo a valid bounded `x-correlation-id` or receive a generated one. Request logs include the same correlation ID and redact authorization and cookie headers. Non-public application routes require an Identity-issued RS256 bearer token; signature, issuer, audience, lifetime, subject, token ID, and roles are validated before a principal is attached to the request.

## Contracts and migrations

- Journal CRUD OpenAPI: `../contracts/openapi/journal-ai-service-v1.yaml`
- MongoDB migration baseline: `migrations/001_journal_entries_baseline.cjs`
- Journal mutation-command validator and unique index: `migrations/002_journal_mutation_commands.cjs`
- Immutable replay metadata and cursor-index alignment: `migrations/003_journal_replay_snapshots_and_cursor_index.cjs`

Run `npm run contract:check` and `npm run migration:check` for static validation. `npm run test:integration` builds the service and runs the HTTP CRUD/concurrency suite against the explicitly configured disposable MongoDB database; it covers cursor tie-breakers/index use, exact mutation replay after more than 32 later commands, conflicting key reuse, owner isolation, tombstones, revision concurrency, and bounded dependency failure. The test refuses a non-disposable database name and drops its database in cleanup.

The current runtime decrypts only the configured active key identifier. Deploying a new key therefore requires a separately reviewed keyring or re-encryption migration; changing `JOURNAL_AI_ENCRYPTION_KEY_ID` alone would make existing entries unreadable and is not a supported rotation procedure.

## Graceful shutdown

The service stops accepting new connections after receiving `SIGINT` or `SIGTERM`. Existing connections have up to 10 seconds to close before they are terminated.
