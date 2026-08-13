---
name: mentalbridge-data-contracts
description: Enforce MentalBridge contract-first and database requirements. Use for REST/OpenAPI, Kafka JSON Schema, WebSocket schemas, PostgreSQL/Liquibase, MongoDB/migrate-mongo, persistence mappings, indexes, constraints, migrations, DTOs, events, outbox, and cross-language compatibility changes.
---

# MentalBridge Data Contracts

First apply `mentalbridge-repository-workflow`; also apply `mentalbridge-architecture` when ownership or transport may change.

## API

1. Update OpenAPI request, response, errors, auth, examples, null semantics, and compatibility first.
2. Generate typed boundaries without copying framework models between services.
3. Implement provider and consumers with deadlines, safe retry, circuit breaker, idempotency, and domain-safe fallback.
4. Test provider and consumer contracts across Java/TypeScript and failure cases.

## Event and WebSocket

1. Update versioned JSON schemas and examples first.
2. Keep payloads minimal and language-neutral; exclude tokens and raw journal/chat/assessment content.
3. Update producer/outbox mapping and every consumer validation path.
4. Test duplicate, delayed, reordered, poison, incompatible, retry/dead-letter, reconnect, and resynchronization behavior as applicable.

## PostgreSQL

1. Add an append-only owner Liquibase change; never edit an applied migration.
2. Update the field dictionary for every changed table/field, including authority, sensitivity, nullability, time/version, and idempotency semantics.
3. Enforce invariants with constraints and deliberate locking; use expand/migrate/contract for overlapping versions.
4. Test migrations, constraints, mappings, concurrency, indexes, and queries against real PostgreSQL.

## MongoDB

1. Add an append-only `migrate-mongo` migration for validation, indexes, or controlled data changes.
2. Update MongoDB and owner documentation.
3. Preserve aggregate ownership, encryption/retention, recoverable publication, and real-Mongo tests.

Never hold a database transaction across a remote call or use a stale projection for authorization/safety without explicit domain acceptance.
