# Engineering rules index

This document is a stable index, not a second copy of implementation rules. Use the closest source below and fix conflicts in the same change.

| Concern | Canonical source |
| --- | --- |
| Product terminology, safety, consent, subscriptions and appointments | `docs/domain-and-use-cases.md` |
| Service ownership, transports, storage and reliability | `docs/architecture.md` |
| Accepted architectural exceptions and replacements | `docs/adr/` |
| Repository workflow and review | `docs/agent-guides/README.md` and its routed guides |
| Spring/NestJS code organization | `docs/agent-guides/service-structure.md` |
| Node.js runtime and libraries | `docs/nodejs-service-stack.md` |
| REST/event/WebSocket lifecycle | `contracts/README.md` and versioned contracts |
| PostgreSQL fields and constraints | owning migration plus `docs/database/postgresql-field-data-dictionary.md` |
| Concrete shallow examples | `docs/reference-implementations.md` |

The non-negotiable repository rules remain in `AGENTS.md`. CI enforces mechanically detectable rules; review remains responsible for business behavior, privacy, authorization, transaction boundaries, and maintainability.
