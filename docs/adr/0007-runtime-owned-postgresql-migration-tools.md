# ADR 0007: Runtime-owned PostgreSQL migration tools

- Status: Accepted
- Date: 2026-08-22
- Supersedes: the Node.js PostgreSQL migration-tool wording in ADR 0003, ADR 0004, and ADR 0006

## Context

MentalBridge owns PostgreSQL databases from both Spring Boot and Node.js services. A single migration tool across runtimes would force the Node.js team to install or operate a JVM-oriented tool even though its application lifecycle and package verification are already npm-based. The repository owner requires the migration tool to follow the owning service runtime while preserving one database, one login, one append-only history, and the same data-dictionary discipline per service.

## Decision

- Spring Boot plus PostgreSQL uses Liquibase through the service's Maven/Spring tooling.
- Node.js/NestJS plus PostgreSQL uses `node-pg-migrate` through service-owned npm scripts.
- Node.js plus MongoDB continues to use `migrate-mongo`.

All three histories are append-only after merge. They run as an explicit deployment or operator step, never as uncontrolled schema auto-creation during application startup. Each migration connects only to the owning database's `public` schema and must not create a database, read another owner's tables, or introduce cross-service foreign keys.

A Node.js PostgreSQL service exposes `migrate:up`, `migrate:down`, `migrate:create`, and `migration:check`. `DATABASE_URL` is injected as a real process secret. CI validates the migration source and applies it to disposable PostgreSQL when the module has an integration suite. The field data dictionary and constraint tests change with the migration.

Rollback scripts are a development aid, not permission to reverse an applied shared migration. After merge, correct schema history with a new forward migration.

## Consequences

- Node.js developers use the same npm lifecycle for application and PostgreSQL schema work.
- CI and repository policy must select the migration tool by runtime instead of requiring Liquibase for every PostgreSQL owner.
- The repository has more than one PostgreSQL migration implementation, so canonical commands and paired-change checks are more important.
- Changing a service's runtime does not silently change its applied migration history; such a move requires a compatibility ADR and rehearsal.

## Rejected alternatives

- Liquibase for every PostgreSQL service: rejected because the owner selected runtime-aligned tooling and does not want JVM tooling imposed on Node.js modules.
- `node-pg-migrate` for Spring services: rejected because Spring already integrates Liquibase with its configuration and test lifecycle.
- ORM schema synchronization: rejected because it is not an explicit, reviewable, append-only deployment history.
