# Content and Notification Service

NestJS service that owns reviewed self-help resource definitions, immutable exact-version Resource Eligibility v1 provenance, notification preferences, and durable notification delivery state. ADR 0009 removes the hotline catalogue from product scope. ADR 0012 keeps final SupportPlan admission in Care and clarifies that review/publication does not make a resource universally plan-eligible.

## Current capability

- `POST /api/v1/resources/{id}/versions/{contentVersion}/eligibility-publications` — ADMIN-only immutable eligibility publication with persisted idempotent replay
- `POST /api/v1/resources/{id}/versions/{contentVersion}/eligibility-publications/withdrawal` — ADMIN-only append-only eligibility withdrawal
- `POST /internal/v1/resource-eligibility:resolve` — authenticated USER-context batch resolution for Care with exact-version outcomes and no content or moderation payload
- MB-337 controlled-demo eligibility matrix — six explicit immutable `vi-VN` resource-version decisions with reviewed provenance and Care-compatible fixture evidence
- `POST /api/v1/safety-directory:lookup` — public coarse-area lookup returning only active reviewed records verified within 90 days; no coordinates or proximity claim
- `/api/v1/safety-directory/admin/entries` — ADMIN-only create, review/verify/activate, update/invalidate, deactivate, and audit-preserving lifecycle

- `GET /health/live` — liveness without a database dependency
- `GET /health/ready` — PostgreSQL readiness check
- `GET /api/v1/resources` — lists active reviewed published self-help resources; returns empty array when none match; returns neutral fallback when service is unreachable; no hotline number or emergency dispatch claim (ADR 0009)
- `GET /api/v1/resources/{id}` — returns the full reviewed body, exact `contentVersion`, and structured source provenance; an optional `contentVersion` query fails closed when a persisted reference no longer matches
- `GET /api/v1/notifications` — returns the authenticated owner's durable inbox newest first with bounded opaque cursor pagination and authoritative unread count
- `PATCH /api/v1/notifications/{id}/read`, `POST /api/v1/notifications/mark-all-read`, and `DELETE /api/v1/notifications/{id}` — persist idempotent read and tombstone lifecycle changes without exposing another owner's records
- MB-556 Review 1 catalogue — 15 Vietnamese resources with structured source metadata and verified YouTube actions for every `VIDEO`; legacy synthetic resources remain exact-ID compatible but are hidden from catalogue browsing
- `POST|DELETE /__test/content/outage` — test-only local outage switch; unavailable unless `E2E_TEST_MODE=true` and the exact `x-e2e-secret` is supplied
- Strict startup configuration, safe Problem Details, structured redacted request logs, CORS deny-by-default, and graceful NestJS shutdown
- `node-pg-migrate` baseline for the service-owned `mentalbridge_content_notification` database

Resource Eligibility v1 independently validates target domain, `PRIMARY`/`ADJUNCT` role, compatible instrument bands, support tier, locale and effective window. Existing public resource reads remain unchanged. The current resource publish endpoint remains blocked by the separate MB-251 review-authority gate; eligibility publication can only target an already reviewed `PUBLISHED` exact version and does not bypass that gate.

Admin write operations (`POST`, `PATCH`, `DELETE`, publish, archive) are explicitly `planned` in `../contracts/openapi/content-notification-service.yaml`. Safety screening and versioned safety guidance remain Care-owned behavior.

MB-562 also implements `GET|PATCH /api/v1/notification-preferences` for the
authenticated owner. The atomic preference aggregate covers `IN_APP`, `EMAIL`,
and future `PUSH` channel choices, six independent content groups,
timezone-aware quiet hours, and email cadence/opt-ins. `PUSH` is persisted only;
device registration and provider delivery remain deferred.

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

Migrations run explicitly before deployment and never on application startup. The database and login are operator prerequisites; migrations do not create databases or schemas. Once merged, an applied migration is never edited or rolled back in a shared environment; add a forward migration instead. Migration `2_remove_hotline_catalogue.sql` removes the obsolete table after the historical baseline is applied; migration `6_add_resource_eligibility_v1.sql` adds immutable publications, declarations, withdrawals and command replay snapshots without changing existing resource rows; migration `9_add_resource_source_provenance.sql` adds structured source fields, catalogue visibility, and VIDEO URL constraints.

Migration `10_persist_notification_preferences.sql` converts the legacy
channel/category matrix into one atomic owner aggregate while preserving any
existing owner choices.

Migration `11_persist_notification_inbox.sql` completes the durable inbox
aggregate with source deduplication, occurred time, approved internal actions,
delivery state, optimistic lifecycle versioning, and a maximum 90-day retention
deadline. Inbox reads tombstone expired rows before returning active items.

The controlled Review 1 seed, MB-337 eligibility matrix, and visibly synthetic non-dialable safety-directory fixture are owner-module migrations with a separate ledger (`pgmigrations_review1`), so running normal schema migrations cannot accidentally mark controlled data as applied. The seeds reject drift from their reviewed decisions. Machine-readable resource inventory, reviewer rationale, explicit ineligible decisions, and Care requests are kept in `../contracts/fixtures/content/resource-eligibility-v1-controlled-demo.json`. No real safety contact is published by the controlled fixture. For the shared dev/staging database only, run:

```bash
npm run migrate:review1:up
```

Review 1 Compose runs schema migrations, controlled resources, the initial eligibility publication, and the MB-556 reviewed catalogue sequentially through `npm run migrate:review1-demo`. The MB-556 copy is an implementation draft and still requires the documented clinical, legal, and licensing review before production publication. Future production deployment must run `npm run migrate:up` only.

## Verification

```bash
npm run quality
```

From the repository root, the same required gate is
`npm --prefix content-notification-service run quality`.

The required module quality command runs formatting, lint, type checking, unit/HTTP tests,
contract and migration checks, the PostgreSQL integration suite, and the production build.
The integration suite requires Docker for its disposable PostgreSQL container. Individual
commands remain available for focused development:

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

## Production image

```bash
docker build -t content-notification-service .
docker run --env-file .env -p 3003:3003 content-notification-service
```

Production credentials must be injected by the deployment environment; do not use a committed `.env` file.
