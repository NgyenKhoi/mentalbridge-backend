# MentalBridge canonical domain model

START HERE when:

- understanding system entities;
- drawing an ERD;
- drawing class or domain diagrams;
- understanding ownership and relationships across services.

## Two sources of truth with different purposes

Runtime database truth is always the owner-service migration history:

- Liquibase for `identity-service`, `care-service`, and `consultation-service`;
- the current SQL migration mechanism for `content-notification-service`;
- `migrate-mongo` migrations and validators for `journal-ai-service` and
  `realtime-service`.

Documentation and domain-model truth is this directory:

- [Canonical entity index](canonical-entities.md)
- [PostgreSQL logical schema](relational/postgresql-logical-schema.sql)
- [MongoDB logical model](document/mongodb-logical-model.md)

The logical model is read-only. Never execute the PostgreSQL logical schema or
use it to provision, initialize, migrate, validate, or repair a runtime
database. If it differs from an owner migration, the migration remains runtime
truth and this documentation is stale and must be corrected.

## Relationship notation

- **Physical relationship** means a constraint or embedded relationship inside
  one owner database, as proven by that owner's migration.
- **Logical/external relationship** means an immutable identifier that points
  to data owned by another service. It is not a cross-database foreign key and
  cannot replace an owner API or authorization check.

The namespace qualifiers in the PostgreSQL logical schema group tables by
owner for diagrams. Runtime tables live in each owner's separate database and
default `public` schema.

## Status

- `ACTIVE`: implemented persistence backed by a current owner migration or by
  the migration delivered in the same pull request.
- `PROPOSED`: explicitly approved design or bounded planned model without an
  active owner migration.
- `HISTORICAL`: removed, superseded, or retained only to explain old data/model
  references.

An entity is never promoted from `PROPOSED` to `ACTIVE` merely because it is in
a backlog or architecture sketch.

## Change rule

Before adding or changing persisted data, inspect the canonical model and the
owner's current migrations. A pull request that adds, removes, renames, or
materially changes persisted domain data is incomplete until it updates:

1. the owner migration and implementation;
2. the relevant canonical logical model in this directory; and
3. the detailed data documentation, including the
   [PostgreSQL field data dictionary](../database/postgresql-field-data-dictionary.md)
   or [MongoDB collection definitions](../database/mongodb.md).

Pure implementation details that do not change domain understanding—such as a
non-unique performance-index adjustment, migration metadata, or a constraint
name-only rename—do not require a logical-model change.

## Related architecture documentation

- [Architecture](../architecture.md)
- [Domain and use cases](../domain-and-use-cases.md)
- [Module boundaries](../agent-guides/module-boundaries.md)
- [PostgreSQL conventions](../agent-guides/postgresql-conventions.md)
- [Service-owned PostgreSQL ADR](../adr/0004-service-owned-postgresql-databases.md)
