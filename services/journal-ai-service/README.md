# Journal and AI Service

Node.js and Express service that owns private journal entries and coordinates AI analysis for MentalBridge.

## Current scope

This baseline provides:

- Node.js 24 runtime
- strict TypeScript with ESM
- Express application bootstrap
- graceful shutdown for `SIGINT` and `SIGTERM`
- lint, type-check, test, and build scripts
- production multi-stage Docker image

Journal APIs, health checks, configuration validation, OpenAPI, MongoDB migrations, and AI integrations are introduced by subsequent work items.

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
npm run build
npm start
```

## Configuration

| Variable | Required | Default | Purpose |
| --- | --- | --- | --- |
| `PORT` | No | `3000` | HTTP port, from 1 through 65535 |

Do not commit local `.env` files or secrets.

## Graceful shutdown

The service stops accepting new connections after receiving `SIGINT` or `SIGTERM`. Existing connections have up to 10 seconds to close before they are terminated.