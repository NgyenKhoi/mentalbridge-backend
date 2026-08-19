# content-notification-service

Node.js/TypeScript microservice — Content/Notification bounded context for MentalBridge.

Manages self-help resources, crisis hotlines, in-app notifications, and delivery preferences.

## Stack

- Node.js 24+ with TypeScript
- Express 5
- PostgreSQL (`mentalbridge_content_notification` database, `public` schema)
- Pino for structured logging
- Zod for configuration validation
- Jest for testing

## Environment Variables

Copy `.env.example` to `.env` and configure:

```env
# PostgreSQL
DB_HOST=localhost
DB_PORT=5432
DB_NAME=mentalbridge_content_notification
DB_USER=postgres
DB_PASSWORD=your_password
DB_POOL_MAX=10
DB_IDLE_TIMEOUT_MS=30000
DB_CONNECT_TIMEOUT_MS=2000

# App
PORT=3003
NODE_ENV=development
LOG_LEVEL=info

# JWT (public key for token verification)
JWT_PUBLIC_KEY=your_public_key
```

## Local Development

```bash
# Install dependencies
npm install

# Run migrations
npm run migrate

# Start development server
npm run dev

# Run tests
npm test

# Type checking
npm run typecheck

# Linting
npm run lint

# Format checking
npm run format:check
```

## Production Build

```bash
# Build TypeScript
npm run build

# Run compiled code
npm start
```

## Docker

```bash
# Build image
docker build -t content-notification-service .

# Run container
docker run -p 3003:3003 --env-file .env content-notification-service
```

## Endpoints

### Health
- `GET /health/live` — liveness probe
- `GET /health/ready` — readiness probe (checks PostgreSQL connectivity)
- `GET /metrics` — service metrics

### API (to be implemented in Story 209)
- `GET /api/v1/resources` — list published resources
- `POST /api/v1/resources` — create resource (admin)
- `GET /api/v1/hotlines` — list active hotlines

## Database

Uses `mentalbridge_content_notification` database with `public` schema.

Tables:
- `resource` — self-help content (reviewed)
- `hotline` — crisis support contacts (verified)
- `notification_preference` — per-user delivery settings
- `notification` — durable in-app notifications

## Scripts

- `npm run build` — compile TypeScript
- `npm start` — run compiled code
- `npm run dev` — development mode with ts-node
- `npm test` — run tests
- `npm run typecheck` — type check without emitting
- `npm run lint` — lint source code
- `npm run format:check` — check code formatting
- `npm run migrate` — run database migrations
- `npm run contract:check` — validate OpenAPI contract
- `npm run migration:check` — validate migration file exists
