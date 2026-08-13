# MentalBridge Agent Rules

These instructions apply to the entire repository. Before changing application code, database migrations, API contracts, or deployment files, read every document in `docs/agent-guides/` in the order listed by its `README.md`.

## Non-negotiable decisions

- REST/JSON is the only synchronous protocol for business APIs and service-to-service queries. WebSocket is allowed only between clients and `realtime-service` for chat, presence, delivery state, and live notification delivery. Do not introduce GraphQL, gRPC, or broker-based request/reply without an accepted ADR.
- Kafka carries asynchronous commands and integration events. It is not used to query current data or as the request path for an immediate REST response.
- Redis supports ephemeral realtime presence, connection/room routing, cross-instance WebSocket fan-out, rate limits, short-lived delivery/idempotency state, and expiring hashed OTP challenges. Do not use Redis as a general database-query/result cache or to store durable messages and business facts. PostgreSQL, MongoDB, and Kafka remain the durable sources.
- Every service owns its data. A service must not query another service's tables, schema, repository, ORM entity, or internal classes.
- Cross-service REST and message payloads are contract-first and language-neutral. OpenAPI and JSON Schema/AsyncAPI are the sources of truth.
- Use the transactional outbox for messages caused by a PostgreSQL state change. Consumers must be idempotent.
- Keep safety-critical assessment, consent, booking, and risk decisions local and transactionally consistent in their owning service.
- Do not add explanatory comments to production code. Express intent with names, types, small functions, tests, and documentation. Legal headers, generated-code markers, and narrowly justified tool directives are the only exceptions.
- Do not report a task as complete while required tests, contract checks, migrations, or builds fail. Report an external blocker explicitly instead.

## Required delivery behavior

1. Identify the owning module and its source of truth before coding.
2. Read the module README, requirements traceability, affected OpenAPI/event contracts, migrations, and relevant domain rules.
3. Change contracts and database descriptions together with implementation.
4. Test success, authorization, validation, concurrency/consistency, timeout, retry, and dependency-failure paths as applicable.
5. Review the final diff against `docs/agent-guides/review-and-testing.md`.
6. Before any branch, checkout/switch, commit, push, issue, or PR action, follow `docs/agent-guides/git-collaboration.md` and the current `.github/` template.

An accepted ADR may override a repository recommendation, but it must explicitly identify the affected rule and migration/compatibility consequences.
