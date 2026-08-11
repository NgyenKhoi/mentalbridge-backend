# Implementation Workflow

## 1. Preflight

Before editing:

1. Identify one owning service for every changed business rule and field.
2. Read its README, feature package, tests, migrations, outbound REST clients, and consumed/published messages.
3. Search for existing contracts and names before creating new DTOs, events, errors, or shared utilities.
4. Record which invariants require a local transaction and which results may be eventually consistent.
5. List affected callers and consumers. A provider change is incomplete until compatibility is checked.

Stop and resolve the design first if a change requires direct cross-service database access, a distributed transaction, raw sensitive text in a message, or a synchronous query through Kafka.

## 2. Contract-first change order

For an API change:

1. Update OpenAPI request, response, error, authentication, and examples.
2. Confirm backward compatibility or document a versioned migration.
3. Generate or update typed client/server models without hand-copying field definitions across services.
4. Implement provider behavior and consumer integration.
5. Run provider and consumer contract tests.

For a message change:

1. Update the event/command JSON Schema and examples.
2. Keep additive fields optional; create a new schema version for breaking semantics.
3. Update producer outbox mapping and every consumer validation path.
4. Test duplicate, reordered, delayed, poison, and incompatible messages.

For a database change:

1. Add an append-only migration in the owning service.
2. Update `docs/database/postgresql-field-data-dictionary.md` with the purpose and necessity of every changed field.
3. Update the data dictionary/module documentation and mapping tests.
4. Use expand/migrate/contract when old and new application versions may overlap.

## 3. Implementation order

Implement a vertical feature slice: boundary validation, application use case, domain rules, persistence/outbound adapters, observability, then tests. Do not pre-create generic abstractions for hypothetical reuse.

Business transactions must finish before remote side effects. Persist state plus an outbox record atomically, commit, then let the publisher deliver the message. Never keep a database transaction open while calling another service or external provider.

## 4. Verification and handoff

Run the smallest relevant checks during development, then all module quality gates before completion. The handoff must state:

- behavior and contracts changed;
- migrations and rollback/compatibility considerations;
- tests run and their exact result;
- any intentionally untested external integration;
- known follow-up work or blocker.

Passing compilation alone is not completion. Do not disable, delete, loosen, or mark tests skipped merely to obtain a green run.

## 5. Failure-loop control

When a command, test, integration, or implementation attempt fails:

1. Capture the exact command, exit code, first actionable error, affected boundary, and whether any state changed.
2. Classify the likely cause: requirement ambiguity, stale/contradictory contract, code defect, configuration/environment, dependency/tooling, test data/timing, shell/quoting, or truncated/incomplete evidence.
3. Inspect the closest source of truth before editing. Examples: failing test and implementation, generated OpenAPI model and schema, effective Spring configuration, Kafka consumer-group state, or full untruncated log segment.
4. State one falsifiable hypothesis and make the smallest change/check that can prove or disprove it.
5. Retry the same failed command only after a relevant change. Do not execute an identical failing command more than twice without new evidence.
6. If two evidence-based hypotheses fail, change strategy: reduce to a minimal reproduction, inspect a lower layer, compare with a known working feature, validate tool/dependency compatibility, or document the external blocker.
7. Once fixed, add a regression test or durable guide/configuration change so the same failure is not rediscovered by another agent.

Common loop traps to avoid:

- changing multiple layers before identifying which boundary is wrong;
- repeatedly increasing timeout/retry counts to hide a deterministic failure;
- treating a truncated tool output as the complete file/log;
- retrying dependency/network commands when the actual problem is credentials, compatibility, or sandbox access;
- fixing one document while leaving stale conflicting decisions elsewhere;
- weakening assertions, validation, transactions, or authorization merely to make tests pass.
