# Sprint 1 backend backlog guide

This guide explains `MentalBridge_Sprint1_Import_Template.csv`. Sprint 1 assumes five backend developers, ten working days, and five available hours per person per day. It plans 37â€“40 feature hours per person and leaves the remaining capacity for meetings, review, integration fixes, uncertainty, and the repository owner's shared CI work.

## Jira hierarchy

- An **Epic** is one service-owned Sprint outcome. It is not a coding task and has no estimate or Sprint value in the CSV.
- A **Story** is a reviewable capability with acceptance criteria and story points. Only Stories are assigned to `MB Sprint 1`.
- A **Sub-task** is concrete implementation work with an original estimate in seconds. Its CSV Description contains an explicit `DoD:` based only on successful manual checks or automated tests. No separate evidence attachment is required. The parent Story is accepted only when its complete behavior and checks pass.

## Team ownership

| Member | Stack | Service | Sprint outcome |
| --- | --- | --- | --- |
| Member 1 | Java/Spring Boot/PostgreSQL | Identity | registration and secure session foundation |
| Member 2 | Java/Spring Boot/PostgreSQL | Care | profile, consent, and PHQ-9 foundation |
| Member 3 | NestJS/TypeScript/MongoDB | Journal/AI | NestJS service plus private journal CRUD |
| Member 4 | NestJS/TypeScript/MongoDB/Redis | Realtime | NestJS service plus connection, presence, and message primitives |
| Member 5 | NestJS/TypeScript/PostgreSQL | Content/Notification | NestJS service plus reviewed resource/hotline APIs |

Consultation, AI provider calls, Kafka analysis, notification delivery, frontend, cloud deployment, and CD are not Sprint 1 work.

## Meaning of recurring tasks

### Define or publish an OpenAPI contract

Write the REST source of truth before handlers: paths, authentication, request/response fields, examples, validation, null/absent semantics, status codes, and RFC 9457 error codes. Done means the contract validates and tests can use it; it does not mean creating controller classes only.

### Add an owner migration baseline

Create append-only owner migrations, constraints/validators, indexes, and human-readable data descriptions. Liquibase is for Spring/PostgreSQL, `node-pg-migrate` is for Node.js/PostgreSQL, and `migrate-mongo` is for MongoDB. Done means a clean database can apply the migration and integration tests verify important constraints.

### Scaffold a NestJS package

Create an independently runnable service with `package.json`, lockfile, Node 22+ engine, strict TypeScript, NestJS 11 bootstrap, bounded feature modules, lint/format/typecheck/test/build scripts, Dockerfile, and graceful shutdown. Keep business behavior out of controllers, guards and global modules; OpenAPI remains the language-neutral REST contract.

### Add typed configuration and operations endpoints

Load `.env` only for local development, validate effective values with Zod, and fail startup safely on missing values. Add liveness, readiness, structured redacted logging, metrics, and correlation IDs. Business code receives typed configuration instead of reading `process.env` directly.

### Implement idempotency

The same logical client retry returns the original result without duplicating state. Identity refresh, PHQ submission, journal mutation, and message send each need a stable key and owner-level uniqueness.

### Test failure paths

Use real disposable PostgreSQL, MongoDB, or Redis through Testcontainers where applicable. Verify authorization, invalid input, duplicates, ordering/concurrency, rollback, dependency unavailability, and privacy-safe errors rather than testing only successful requests.

## Service-specific terms

### Identity

- **Refresh rotation** replaces a refresh token after use and stores only its hash.
- **Replay rejection** detects reuse of an already rotated token and revokes the affected session chain.
- **Generic credential error** avoids revealing whether an email exists.

### Care

- **Server-authoritative scoring** means the API accepts answers only; Care calculates and persists the PHQ-9 score.
- **Anonymous isolation** means a guest result is not silently attached to a later account.
- **Item-9 safety acceptance** is a product/supervisor decision required before final behavior; a developer must not invent crisis wording or policy.

### Journal/AI

- **Revision** is an immutable version created when journal content changes.
- **Cursor pagination** returns a stable bounded page plus a continuation token instead of an unbounded list.
- **Privacy boundary** keeps raw journal text out of logs, metrics, events, errors, and broad indexes.

### Realtime

- **Versioned socket envelope** defines event type, schema version, IDs, payload limits, acknowledgement, and safe errors.
- **TTL presence** is expiring online state in Redis; it is not durable user data.
- **Persist before acknowledgement** means MongoDB accepts the message before the server tells the sender it succeeded.
- Chat must not be exposed as an eligible production feature until Consultation provides the appointment/relationship authorization contract.

### Content/Notification

- **Reviewed current content** means public reads return only active locale/region records whose review and effective dates are valid.
- **Synthetic records** are safe local/test fixtures; they are not claims that an emergency number has been approved for production.
- Brevo, push delivery, Kafka-triggered notifications, retry/dead-letter behavior, and live delivery are deferred.

## Sprint acceptance

Sprint 1 is accepted when each Story's contracts, migrations, implementation, documentation and tests agree. A scaffold that merely starts or code that merely compiles does not complete a functional Story. Before the initial `dev` CI gate is enabled, the same quality commands are recorded as local review evidence; afterward the corresponding required CI status must also pass.

The repository owner creates one shared GitHub Actions CI flow outside this imported backlog after the current bootstrap integration is stable. No team member receives a separate CI/CD Jira task and Sprint 1 contains no deployment work.
