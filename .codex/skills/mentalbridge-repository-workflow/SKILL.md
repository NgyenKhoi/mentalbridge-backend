---
name: mentalbridge-repository-workflow
description: Apply MentalBridge backend repository requirements to every task. Use for all analysis, review, implementation, documentation, configuration, database, contract, testing, and delivery work in this repository. Always use before modifying files or reporting work complete; combine it with the specialized MentalBridge architecture, data-contracts, or verification-delivery skill when those boundaries are affected.
---

# MentalBridge Repository Workflow

Treat repository sources as active requirements, not background reading.

1. Read every applicable `AGENTS.md` or override from repository root to the working directory.
2. Read `docs/agent-guides/README.md`, then all linked guides in its declared order before implementing or reviewing a module.
3. Identify the approved requirement, owning module, source of truth, affected callers/consumers, transaction boundary, and acceptable consistency before editing.
4. Read the owner README, relevant feature code/tests, contracts, migrations, database descriptions, domain rules, architecture, traceability, and accepted ADRs.
5. Follow decision precedence: approved product/safety policy; accepted ADR; versioned contract and executable migration; domain/architecture docs; module implementation.
6. Stop and surface ambiguity involving risk, safety, consent, authorization, privacy, retention, or data ownership. Do not guess silently.
7. Update every affected artifact together: contract, migration/data description, implementation, configuration example, tests, and documentation.
8. Before editing a task that spans multiple concerns, define ordered commit slices. Each slice has one reviewable reason to change, its affected boundary/files, and the smallest checks that must pass; revise the slices when evidence changes.
9. Implement a vertical slice: boundary validation, application use case, domain behavior, persistence/provider adapters, observability, then tests. Finish and verify each planned commit slice instead of accumulating the whole task for one final commit.
10. Preserve unrelated work and avoid speculative abstractions, empty layers, explanatory production comments, or cross-service model/storage sharing.
11. Run applicable checks and inspect the complete diff. Do not report completion while a required check fails.

Use `mentalbridge-architecture` for architecture, ownership, integration, safety, provider, or deployment decisions. Use `mentalbridge-data-contracts` for API/event/WebSocket/database work. Use `mentalbridge-verification-delivery` for review, tests, Git, GitHub, push, or completion handoff.
