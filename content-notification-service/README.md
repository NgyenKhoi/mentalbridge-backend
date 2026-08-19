# content-notification-service

Plain Node.js/TypeScript microservice — Content/Notification bounded context for MentalBridge.

Manages self-help resources, crisis hotlines, in-app notifications, and delivery preferences.

## Stack

- Node.js 24+ with strict TypeScript (ESM)
- Express 5
- PostgreSQL (`mentalbridge_content_notification` database, `public` schema)
- Pino structured logging
- Zod 4 for configuration validation
- Vitest for testing
- node-pg-migrate for migrations

## Environment Variables

Copy `.env.example` to `.env` and configure:

```env
DB_HOST=localhost
DB_PORT=5432
DB_NAME=mentalbridge_content_notification
DB_USER=postgres
DB_PASSWORD=your_password
DB_POOL_MAX=10
DB_IDLE_TIMEOUT_MS=30000
DB_CONNECT_TIMEOUT_MS=2000

PORT=3003
NODE_ENV=development
LOG_LEVEL=info

CORS_ORIGINS=http://localhost:3000

DATABASE_URL=postgres://postgres:your_password@localhost:5432/mentalbridge_content_notification
```

> `.env` is only loaded in `development` — never in `production` or `test`.

## Local Development

```bash
# Install dependencies
npm install

# Run migrations
npm run migrate:up

# Start development server
npm run dev

# Run unit tests
npm test

# Type checking
npm run typecheck

# Linting
npm run lint

# Format checking
npm run format:check

# Validate OpenAPI contract
npm run contract:check
```

## Production Build

```bash
npm run build
npm start
```

## Docker

```bash
docker build -t content-notification-service .
docker run -p 3003:3003 --env-file .env content-notification-service
```

## Endpoints

### Health
- `GET /health/live` — liveness (no DB dependency)
- `GET /health/ready` — readiness (checks PostgreSQL connectivity)

### API
Routes will be implemented in Story 209+. See `contracts/openapi/content-notification-service.yaml`.

## Database

Database: `mentalbridge_content_notification`, schema: `public`.

Migration tool: `node-pg-migrate` (see `docs/adr/001-node-pg-migrate.md`).  
Migrations are **append-only** and run explicitly — never on app startup.

Tables: `resource`, `hotline`, `notification_preference`, `notification`.

## Scripts

| Script | Description |
|---|---|
| `npm run build` | Compile TypeScript to `dist/` |
| `npm start` | Run compiled output |
| `npm run dev` | Development with `tsx` |
| `npm test` | Run unit tests (Vitest) |
| `npm run typecheck` | Type check without emitting |
| `npm run lint` | ESLint |
| `npm run format:check` | Prettier check |
| `npm run migrate:up` | Apply migrations |
| `npm run migrate:down` | Rollback last migration |
| `npm run contract:check` | Validate OpenAPI 3.1 spec |
| `npm run migration:check` | Verify migration file exists |
