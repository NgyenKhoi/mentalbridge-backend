# Module delivery specifications

This directory turns the seven-UC product catalogue and 162-function WBS into work that can be assigned and accepted per deployable. It does not replace the authoritative sources in `docs/domain-and-use-cases.md`, `docs/requirements-traceability.md`, OpenAPI/event/WebSocket schemas, or executable migrations.

## How to use these documents

1. Start with [coverage.md](coverage.md) and select one module-owned use case.
2. Refine the module specification before implementation: actors, preconditions, invariants, failure paths, privacy/safety behavior, contracts, persistence, and dependencies must be explicit.
3. Create or update source-of-truth contracts and migrations before application code.
4. Implement one vertical task slice and collect the evidence required by its acceptance criteria.
5. Update coverage only after the behavior and required checks pass. A checked task means verified behavior, not code merely written.

## Module specifications

| Deployable | Runtime | Specification | Scaffold status |
| --- | --- | --- | --- |
| Identity Service | Spring Boot | [identity-service](identity-service.md) | initialized |
| Care Service | Spring Boot | [care-service](care-service.md) | initialized |
| Consultation Service | Spring Boot | [consultation-service](consultation-service.md) | initialized |
| Journal/AI Service | Node.js/TypeScript | [journal-ai-service](journal-ai-service.md) | not initialized |
| Realtime Service | Node.js/TypeScript | [realtime-service](realtime-service.md) | not initialized |
| Content/Notification Service | Node.js/TypeScript | [content-notification-service](content-notification-service.md) | not initialized |
| PhoBERT Worker | Python | [phobert-worker](phobert-worker.md) | not initialized |

The updated workbook also introduces a financial bounded context that has no accepted owner in ADR 0001. Track it in [subscription and payment architecture gap](subscription-payment-gap.md); it is not an eighth deployable until an ADR is accepted.

Specifications stay here even after a module is scaffolded. Its README remains the operational entry point and links back to this specification.

## Readiness and completion

A use case is ready for implementation only when its owner, current-data authority, actors, authorization, success result, error semantics, transaction boundary, consistency, sensitive fields, retention, contracts, and acceptance examples are known. Safety, consent, privacy, retention, or ownership ambiguity blocks implementation.

A use case is complete only when behavior, OpenAPI/event/WebSocket contracts, migrations and data descriptions, configuration, observability, and applicable tests agree. Required evidence includes success, validation, authorization, conflict/concurrency, idempotency/retry, dependency failure, and compatibility checks. Compilation alone is not acceptance.

## Cross-module rules

- REST/JSON is the only synchronous business integration; current authorization and consent fail closed.
- Kafka carries durable asynchronous commands and facts, never synchronous queries. PostgreSQL changes publish through a transactional outbox; consumers are idempotent.
- Only Realtime Service exposes WebSocket. Redis is ephemeral and never owns durable business facts.
- No module reads another module's storage or shares framework/persistence models.
- Raw journal/chat content, assessment answers, tokens, and provider payloads do not enter events, logs, or broad admin projections.
- Assessment scoring and immediate severe-risk guidance remain local to Care and available without AI, Kafka, Redis, WebSocket, or notification providers.
