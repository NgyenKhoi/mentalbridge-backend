# Journal AI Service

NestJS and TypeScript service for owner-scoped private journal CRUD.

## MB-112 endpoints

- `POST /v1/journals`
- `GET /v1/journals?limit=20&cursor=...`
- `GET /v1/journals/:journalId`

Requests must include the authenticated owner UUID in `x-owner-account-id`. In production this header is supplied by the trusted identity gateway after JWT verification; the journal service never accepts an arbitrary owner from the request body.

The MongoDB collection is `journal_entries`. Journal content is scoped by owner in every repository query and list results use bounded cursor pagination.
