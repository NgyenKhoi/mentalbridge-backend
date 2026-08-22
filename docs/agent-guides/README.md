# Agent Engineering Guide

This directory is the mandatory working guide for humans and coding agents. Use it as a router: read the core set for every task, then only the guides for the boundaries being changed. A reviewer may require another guide when the diff crosses that boundary.

## Core reading order

1. [Workflow](workflow.md)
2. [Owner working preferences](owner-working-preferences.md)
3. [Module boundaries](module-boundaries.md)
4. [Service code structure](service-structure.md)
5. [Review and testing](review-and-testing.md)
6. [Git and GitHub collaboration](git-collaboration.md)

## Task-specific guides

| Change touches | Also read |
| --- | --- |
| environment variables, secrets, profiles, deployment configuration | [Environment configuration](environment-configuration.md) |
| OpenAPI, synchronous clients, timeouts, retries, circuit breakers | [REST integration and resilience](rest-integration.md) |
| Kafka commands/events, outbox, inbox, retries, dead letters | [Asynchronous messaging](async-messaging.md) |
| PostgreSQL tables, mappings, transactions, indexes, migrations | [PostgreSQL conventions](postgresql-conventions.md) |

Do not read every guide mechanically when the change is isolated. Do not skip a task-specific guide merely because the implementation is small.

Also read the affected source-of-truth documents:

- business behavior: [Domain and use cases](../domain-and-use-cases.md);
- source/WBS coverage and unresolved design gaps: [Requirements traceability](../requirements-traceability.md);
- system boundaries: [Architecture](../architecture.md);
- contract lifecycle and canonical locations: [`contracts/README.md`](../../contracts/README.md);
- REST contract: `contracts/openapi/`;
- message contract: `contracts/events/`;
- persisted shape and constraints: the owning service's migrations;
- a deliberate architectural exception: `docs/adr/`.

## Decision precedence

When documents disagree, use this order and fix the stale document in the same change:

1. approved product requirement and safety policy;
2. accepted ADR;
3. versioned API/event contract and executable migration;
4. domain and architecture documentation;
5. module README and implementation.

Do not guess silently when the conflict affects consent, risk, access control, retention, or data ownership.
