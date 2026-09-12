# Content and Notification Service

NestJS service that owns reviewed self-help resources, notification preferences, and durable notification delivery state. ADR 0009 removes the hotline catalogue from product scope.

## Current capability

- `GET /health/live` — liveness without a database dependency
- `GET /health/ready` — PostgreSQL readiness check
- `GET /api/v1/resources` — lists active reviewed published self-help resources; returns empty array when none match; returns neutral fallback when service is unreachable; no hotline number or emergency dispatch claim (ADR 0009)
- `POST|DELETE /__test/content/outage` — test-only local outage switch; unavailable unless `E2E_TEST_MODE=true` and the exact `x-e2e-secret` is supplied
- Strict startup configuration, safe Problem Details, structured redacted request logs, CORS deny-by-default, and graceful NestJS shutdown
- `node-pg-migrate` baseline for the service-owned `mentalbridge_content_notification` database

Admin write operations (`POST`, `PATCH`, `DELETE`, publish, archive) are explicitly `planned` in `../contracts/openapi/content-notification-service.yaml`. Safety screening and versioned safety guidance remain Care-owned behavior.

## Stack

- Node.js 22 or newer, strict TypeScript, NestJS 11
- PostgreSQL through `pg`
- `node-pg-migrate` for append-only PostgreSQL migrations
- Pino, Zod, Vitest, Supertest, and Testcontainers

## Local development

Copy `.env.example` to an untracked `.env`, provision the owned database, then run:

```bash
npm ci
npm run dev
```

The application loads `.env` only in development. Test and production environments require real process variables and never depend on a repository `.env` file.

For live local E2E, set `E2E_TEST_MODE=true` and a random `E2E_TEST_SECRET`.
The Playwright harness can then toggle the service itself:

```powershell
$headers = @{ 'x-e2e-secret' = $env:E2E_TEST_SECRET }
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:3003/__test/content/outage -Headers $headers
Invoke-RestMethod -Method Delete -Uri http://127.0.0.1:3003/__test/content/outage -Headers $headers
```

This changes the Content provider state and lets the real BFF receive a genuine
dependency-unavailable response; it does not intercept or rewrite browser traffic.

### JWT authentication

This service verifies RS256 JWTs issued by Identity Service. For local development, each developer generates their own RSA key pair:

```powershell
# Windows
.\scripts\generate-local-jwt-keys.ps1
```

```bash
# Linux/macOS
./scripts/generate-local-jwt-keys.sh
```

Keys are written to `.local/secrets/` (git-ignored). Copy the generated public key value into `.env`:

```env
IDENTITY_JWT_ISSUER=https://identity.local.mentalbridge
IDENTITY_JWT_AUDIENCE=mentalbridge-api
IDENTITY_JWT_KEY_ID=local-development-key
IDENTITY_JWT_PUBLIC_KEY=-----BEGIN PUBLIC KEY-----\n...\n-----END PUBLIC KEY-----
IDENTITY_JWT_CLOCK_TOLERANCE_SECONDS=60
```

Identity Service must be configured with the corresponding private key (`IDENTITY_JWT_PRIVATE_KEY`).

## Migrations

The application pool and `node-pg-migrate` share `DATABASE_URL`. Set `sslmode=require` or a stricter verified mode for remote PostgreSQL, then run:

```bash
npm run migration:check
npm run migrate:up
```

Migrations run explicitly before deployment and never on application startup. The database and login are operator prerequisites; migrations do not create databases or schemas. Once merged, an applied migration is never edited or rolled back in a shared environment; add a forward migration instead. Migration `2_remove_hotline_catalogue.sql` removes the obsolete table after the historical baseline is applied.

The controlled Review 1 seed is an owner-module migration with a separate ledger (`pgmigrations_review1`), so running normal schema migrations cannot accidentally mark the seed as applied. For the shared dev/staging database only, run:

```bash
npm run migrate:review1:up
```

Review 1 Compose runs schema migrations and this seed migration sequentially through `npm run migrate:review1-demo`. Future production deployment must run `npm run migrate:up` only.

## Verification

```bash
npm run format:check
npm run lint
npm run typecheck
npm test
npm run test:integration
npm run contract:check
npm run migration:check
npm run build
```

The integration suite requires Docker for its disposable PostgreSQL container.

## Production image

```bash
docker build -t content-notification-service .
docker run --env-file .env -p 3003:3003 content-notification-service
```

Production credentials must be injected by the deployment environment; do not use a committed `.env` file.
