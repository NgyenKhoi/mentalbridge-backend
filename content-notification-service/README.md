# Content and Notification Service

NestJS service that owns reviewed self-help resources, crisis hotlines, notification preferences, and durable notification delivery state.

## Current capability

The current baseline implements only:

- `GET /health/live` without a database dependency;
- `GET /health/ready` with a PostgreSQL readiness check;
- strict startup configuration, safe Problem Details, structured redacted request logs, CORS deny-by-default, and graceful NestJS shutdown;
- the `node-pg-migrate` baseline for the service-owned `mentalbridge_content_notification` database.

Resource and hotline operations in `../contracts/openapi/content-notification-service.yaml` are explicitly `planned`; they are not available until a vertical feature PR adds handlers and contract tests.

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

## Migrations

`node-pg-migrate` reads append-only SQL files from `migrations/`. Supply `DATABASE_URL` as a real process variable, then run:

```bash
npm run migration:check
npm run migrate:up
```

Migrations run explicitly before deployment and never on application startup. The database and login are operator prerequisites; migrations do not create databases or schemas. Once merged, an applied migration is never edited or rolled back in a shared environment; add a forward migration instead.

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
