# Service Code Structure

Use package-by-feature. Layers exist inside a feature, not as repository-wide buckets containing hundreds of unrelated classes.

## Spring Boot

```text
src/main/java/com/mentalbridge/<service>/
├── assessment/
│   ├── api/
│   ├── application/
│   ├── domain/
│   └── infrastructure/
├── consent/
│   ├── api/
│   ├── application/
│   ├── domain/
│   └── infrastructure/
├── configuration/
└── shared/
```

- Controllers validate transport shape and invoke one application use case; they do not contain business rules or persistence queries.
- Domain types do not import Spring, JPA entities, controllers, generated API DTOs, or provider SDKs.
- Persistence entities and repository adapters remain in `infrastructure` and are mapped explicitly to domain types.
- Outbound REST clients live in the consuming feature's infrastructure package behind a narrow application port.
- `shared` is limited to stable technical primitives such as error envelopes, tracing, clocks, and identifiers. It must not become a shared business model.

## Node.js/NestJS

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
└── shared/
```

- Nest modules follow business features; do not create global `controllers/`, `services/`, `repositories/`, or `dto/` dumping grounds.
- Controllers and message handlers delegate to application use cases.
- Domain code does not import NestJS, ORM documents/entities, HTTP clients, or broker libraries.
- Provider-specific HTTP, Kafka, Redis, and WebSocket code stays behind application ports in `infrastructure`.
- TypeScript runs in strict mode. Do not use `any` for contracts or persistence boundaries; validate runtime input because compile-time types do not validate JSON.

## Size and readability controls

- One file has one primary reason to change. Split orchestration, mapping, validation, and persistence instead of growing a god service.
- Prefer named domain types and small cohesive methods over boolean flags and long parameter lists.
- Duplication inside two features is preferable to a premature cross-domain abstraction. Extract only when semantics and change cadence are demonstrably the same.
- Do not add explanatory comments to production code. Improve the name or structure and capture business rationale in tests, the module README, an ADR, or the database description.
- Do not create empty architecture layers. A small feature may have fewer files while preserving dependency direction.
