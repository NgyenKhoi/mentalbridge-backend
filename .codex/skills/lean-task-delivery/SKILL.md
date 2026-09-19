---
name: lean-task-delivery
description: Implement scoped coding tasks with the simplest business-sufficient design and impact-based verification. Use when changing application code, APIs, persistence, or UI; especially when deciding whether defensive infrastructure or a full test suite is actually justified.
---

# Lean Task Delivery

Deliver the requested business behavior with the smallest maintainable change and proportionate evidence. Treat the merged development baseline as already verified; do not re-prove unrelated behavior locally.

## Start from business impact

Before editing, extract the task's observable behavior, business invariants, affected contracts, and explicit Definition of Done. Separate them from implementation preferences and hypothetical failure modes.

Choose the simplest design that satisfies those requirements and fits existing repository patterns. Do not introduce a new abstraction, table, command log, queue, cache, configuration layer, retry mechanism, idempotency protocol, or generalized framework unless at least one of these is true:

- the task or an existing contract explicitly requires it;
- it protects a concrete business invariant or irreversible side effect;
- an observed failure cannot be handled correctly by the existing design;
- the repository already requires that pattern at this boundary.

Prefer a local implementation for a local requirement. Do not design for speculative reuse, future scale, or unsupported cases. Preserve security, privacy, data integrity, and externally visible compatibility even when minimizing scope.

## Use idempotency only where it changes business correctness

Use explicit idempotency for non-idempotent or externally visible business transitions where retries could duplicate durable effects, such as payment, activation, publication, notification, or outbox production.

Do not add an idempotency key to reads. For replacement-style updates, first prefer natural PUT idempotence, optimistic concurrency, and a no-op when the desired state already exists. Request deduplication alone is not a business requirement unless duplicate processing would materially change durable state or user-visible results.

## Implement narrowly

- Change only the owning module and directly affected consumers.
- Reuse current types, validation, errors, and transaction boundaries.
- Add validation for supported business inputs, not imagined variants.
- Add concurrency handling only around a real shared-state invariant.
- Avoid refactors, cleanup, or formatting outside the affected path.
- If a simpler design conflicts with an explicit acceptance criterion, follow the criterion and call out the cost rather than silently expanding scope.

## Verify by impact

Build a small change-to-test map before running commands. Every test command must cover a changed behavior, a directly affected boundary, or a realistic regression path.

During implementation:

- run focused unit tests for changed decision logic;
- run focused integration tests for changed persistence, transactions, or service boundaries;
- run only the affected contract, migration, route, or component tests;
- use targeted typecheck/lint/build commands when the repository supports them.

Expand verification only when evidence justifies it. A full repository suite is appropriate when explicitly required, at a release or pre-merge gate, after changing shared infrastructure or foundational contracts, after a broad schema migration, or when focused failures suggest wider regression risk. Otherwise leave exhaustive regression coverage to CI.

Run at most one full suite per repository per delivery checkpoint. Do not rerun it after documentation-only changes or a narrowly added test; run the affected test instead. Rerun broader verification only when subsequent production-code changes could invalidate its result.

Keep command output concise. Prefer quiet reporters or bounded output, then inspect the failing test or summary instead of loading complete debug logs.

## Report proportionately

At handoff, state:

- the business behavior delivered;
- focused tests that passed;
- any broader gate run and the concrete reason it was warranted;
- relevant verification intentionally left to CI;
- known limitations that affect the requested behavior.

Do not claim confidence from unrelated test volume. Confidence should come from coverage of the changed behavior and its real boundaries.
