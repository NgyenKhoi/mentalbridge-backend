# Agent Engineering Guide

This directory is the mandatory working guide for humans and coding agents. Use it as a router: read the core set for every task, then only the guides for the boundaries being changed. A reviewer may require another guide when the diff crosses that boundary.

## Core reading order

1. [Workflow](workflow.md)
2. [Owner working preferences](owner-working-preferences.md)
3. [Current Product Blueprint](../CURRENT_PRODUCT_BLUEPRINT.md)
4. [Module boundaries](module-boundaries.md)
5. [Service code structure](service-structure.md)
6. [Review and testing](review-and-testing.md)
7. [Git and GitHub collaboration](git-collaboration.md)

## Task-specific guides

| Change touches | Also read |
| --- | --- |
| environment variables, secrets, profiles, deployment configuration | [Environment configuration](environment-configuration.md) |
| OpenAPI, synchronous clients, timeouts, retries, circuit breakers | [REST integration and resilience](rest-integration.md) |
| Kafka commands/events, outbox, inbox, retries, dead letters | [Asynchronous messaging](async-messaging.md) |
| PostgreSQL tables, mappings, transactions, indexes, migrations | [PostgreSQL conventions](postgresql-conventions.md) |

Do not read every guide mechanically when the change is isolated. Do not skip a task-specific guide merely because the implementation is small.

Also read the affected source-of-truth documents:

- current product authority/navigation: [Current Product Blueprint](../CURRENT_PRODUCT_BLUEPRINT.md);
- latest accepted product decision/amendment: [ADR register](../adr/README.md);
- current approved product/safety/domain policy: [`docs/policies/`](../policies/README.md);
- integrated business behavior: [Domain and use cases](../domain-and-use-cases.md);
- source/WBS coverage and unresolved design gaps: [Requirements traceability](../requirements-traceability.md);
- system boundaries: [Architecture](../architecture.md);
- contract lifecycle and canonical locations: [`contracts/README.md`](../../contracts/README.md);
- REST contract: `contracts/openapi/`;
- message contract: `contracts/events/`;
- persisted shape and constraints: the owning service's migrations;
- canonical documentation view of relational/document entities and relationships: [`docs/domain-model/`](../domain-model/README.md).

## Decision precedence

`CURRENT_PRODUCT_BLUEPRINT.md` is an authority map, not a duplicate detailed specification. It tells readers which accepted ADR/amendment and policy version is current.

When product/business documents disagree, use this order and fix the stale document in the same change:

1. latest explicit accepted product-scope ADR/amendment for the affected rule;
2. current approved domain or safety policy implementing that decision;
3. versioned API/event contract and executable owner migration;
4. domain, requirements-traceability, architecture, and domain-model documentation;
5. owner-service implementation and tests as evidence of what is executable;
6. Jira delivery text/status.

A newer explicit amendment wins prospectively over an older ADR or policy clause. Historical records retain the exact policy/version under which they were created and must not be rewritten to look as if the newer rule applied earlier.

Jira tracks delivery and dependencies. Jira does not override an accepted product decision or approved current policy.

Do not guess silently when a conflict affects consent, safety, access control, retention, entitlement, financial settlement, or data ownership.
