# ADR 001 — Use node-pg-migrate instead of Liquibase

**Status:** Accepted  
**Date:** 2026-08-20  
**Scope:** content-notification-service (Node.js)

## Context

The project initially referenced Liquibase as the migration tool for all services. Java services (care-service, consultation-service, identity-service) use Liquibase natively via Spring Boot. The content-notification-service is a **plain Node.js service**, not a Java/Spring Boot application.

## Decision

Use **node-pg-migrate** for this service instead of Liquibase:

- Runs natively in Node.js — no JVM required in CI or Docker image
- SQL-based migrations, append-only, compatible with `public` schema
- Supports `migrate:up` / `migrate:down` via npm scripts
- Migration is a separate explicit step — never runs automatically on app startup

## Consequences

- Migrations live in `migrations/` as plain SQL files
- `DATABASE_URL` env var drives migration tooling
- ADR should be referenced in root repo docs to clarify per-service tool choice
- Java services continue using Liquibase; this service uses node-pg-migrate
