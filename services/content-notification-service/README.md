# content-notification-service

Plain Node.js microservice — Content/Notification bounded context.

## Stack
- Node.js + Express
- PostgreSQL (`content` schema)

## Local run

```bash
cp .env.example .env
# fill in DB credentials
npm install
npm run dev
```

## Health

- `GET /health/live` — liveness
- `GET /health/ready` — readiness (checks DB connection)
