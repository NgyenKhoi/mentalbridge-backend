# Service Code Structure

Use package-by-feature. Keep a feature shallow until additional internal boundaries make it easier to understand.

## Spring Boot

```text
src/main/java/com/mentalbridge/<service>/
├── profile/
│   ├── ProfileController.java
│   ├── ProfileService.java
│   ├── ProfileEntity.java
│   ├── ProfileRepository.java
│   └── ProfileResponse.java
├── assessment/
│   ├── api/                 # only when the transport boundary has several types
│   ├── application/         # focused use cases and transaction orchestration
│   ├── domain/              # aggregates, values, and policies independent of JPA
│   └── infrastructure/      # provider or persistence adapters with real boundary logic
├── configuration/
└── shared/
```

- Controllers validate transport shape and invoke one focused service or use case; they do not contain business rules or persistence queries.
- A straightforward feature may use Controller → Service → Spring Data Repository directly. Do not add a one-to-one port and adapter around a repository, encoder, mapper, or framework interface without a concrete second implementation or meaningful boundary behavior.
- Split services by use case when they coordinate unrelated transactions, authorization, mapping, providers, and events; do not replace clear small services with a feature-wide god service.
- Create a separate domain model only when aggregates, values, or policies benefit from persistence independence. A JPA entity may hold cohesive local behavior for a simple aggregate, but it never becomes a REST/event DTO.
- Spring Boot business modules use Hibernate and Spring Data JPA for PostgreSQL persistence. `ddl-auto` validates rather than creates or updates schemas because Liquibase remains authoritative.
- Keep external REST/provider clients behind a narrow application port. A concrete persistence coordinator is acceptable when it centralizes deliberate locking, non-trivial mapping, or a multi-repository operation; it does not require a matching interface by default.
- `shared` is limited to stable technical primitives such as error envelopes, tracing, clocks, and identifiers. It must not become a shared business model.

## Node.js/TypeScript

```text
src/
├── conversations/
│   ├── api/
│   ├── application/
│   ├── domain/
│   └── infrastructure/
├── messages/
│   ├── api/
│   ├── application/
│   ├── domain/
│   └── infrastructure/
├── configuration/
├── observability/
├── shared/
└── main.ts
```

- Use the ADR 0003 stack and `docs/nodejs-service-stack.md`; do not add NestJS or recreate its module/decorator/DI model internally.
- Express routers, Kafka handlers, and Socket.IO handlers stay in feature `api` adapters and delegate to application use cases.
- Domain code does not import Express, database drivers, HTTP clients, Kafka, Redis, Socket.IO, or provider SDKs.
- Provider-specific HTTP, MongoDB, PostgreSQL, Kafka, Redis, and WebSocket code stays behind application ports in `infrastructure`.
- `main.ts` is the composition root: load validated configuration, create adapters/use cases, attach routes/handlers, start lifecycle hooks, and shut down gracefully.
- TypeScript runs in strict mode. Do not use `any` for contracts or persistence boundaries; validate runtime input because compile-time types do not validate JSON.

## Size and readability controls

- One file has one primary reason to change. Split orchestration, mapping, validation, and persistence instead of growing a god service.
- Prefer named domain types and small cohesive methods over boolean flags and long parameter lists.
- Duplication inside two features is preferable to a premature cross-domain abstraction. Extract only when semantics and change cadence are demonstrably the same.
- Do not add explanatory comments to production code. Improve the name or structure and capture business rationale in tests, the module README, an ADR, or the database description.
- Do not create empty architecture layers. A small feature may have fewer files while preserving dependency direction.
