# Content and Notification Service

NestJS service that owns reviewed self-help resources, notification preferences, and durable notification delivery state. ADR 0009 removes the hotline catalogue from product scope.

## Current capability

The current baseline implements only:

- `GET /health/live` without a database dependency;
- `GET /health/ready` with a PostgreSQL readiness check;
- strict startup configuration, safe Problem Details, structured redacted request logs, CORS deny-by-default, and graceful NestJS shutdown;
- the `node-pg-migrate` baseline for the service-owned `mentalbridge_content_notification` database.

Resource operations in `../contracts/openapi/content-notification-service.yaml` are explicitly `planned`; they are not available until a vertical feature PR adds handlers and contract tests. Safety screening and versioned safety guidance remain Care-owned behavior.

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

`node-pg-migrate` reads append-only SQL files from `migrations/`. Supply `DATABASE_URL` as a real process variable, then run:

```bash
npm run migration:check
npm run migrate:up
```

Migrations run explicitly before deployment and never on application startup. The database and login are operator prerequisites; migrations do not create databases or schemas. Once merged, an applied migration is never edited or rolled back in a shared environment; add a forward migration instead. Migration `2_remove_hotline_catalogue.sql` removes the obsolete table after the historical baseline is applied.

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
