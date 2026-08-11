# Agent Engineering Guide

This directory is the mandatory working guide for humans and coding agents. Read all files before implementing or reviewing a module; they are intentionally split so each concern remains maintainable.

## Required reading order

1. [Workflow](workflow.md)
2. [Owner working preferences](owner-working-preferences.md)
3. [Module boundaries](module-boundaries.md)
4. [Service code structure](service-structure.md)
5. [Environment configuration](environment-configuration.md)
6. [REST integration and resilience](rest-integration.md)
7. [Asynchronous messaging](async-messaging.md)
8. [PostgreSQL conventions](postgresql-conventions.md)
9. [Review and testing](review-and-testing.md)
10. [Git and GitHub collaboration](git-collaboration.md)

Also read the affected source-of-truth documents:

- business behavior: [Domain and use cases](../domain-and-use-cases.md);
- source/WBS coverage and unresolved design gaps: [Requirements traceability](../requirements-traceability.md);
- system boundaries: [Architecture](../architecture.md);
- REST contract: `contracts/openapi/` once introduced;
- message contract: `contracts/events/` once introduced;
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
