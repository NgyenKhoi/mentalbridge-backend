# Reference implementation structures

These are review references, not templates to copy blindly. Start shallow and add a boundary only when it carries real behavior.

## Spring Boot: straightforward feature

Use `identity-service/account` as the small-feature reference:

```text
account/
  AccountController.java
  AccountService.java
  AccountResponse.java
```

The controller maps HTTP, the service coordinates the use case, and existing repositories remain owned by their cohesive feature. Do not copy Identity's authentication, refresh-token, encryption, or outbox structure into ordinary CRUD; those extra types protect real security and reliability boundaries.

Introduce `api/application/domain/infrastructure` only when a feature has multiple transport types, non-trivial domain policy, an external provider, complex mapping/locking, or more than one implementation of a boundary.

## NestJS: service foundation and feature

Use `journal-ai-service` for the technical foundation: `app.module.ts` composes modules, `main.ts` bootstraps, and health/observability/security each own focused providers. A normal business feature begins as:

```text
resources/
  resources.module.ts
  resources.controller.ts
  resources.service.ts
  resources.repository.ts
  resources.schema.ts
  resources.spec.ts
```

Split into `api/application/domain/infrastructure` only after those responsibilities become independently meaningful. A controller never queries a database or contains business rules. A repository does not return a REST DTO. Nest dependency injection wires boundaries; it is not a reason to create a one-to-one interface for every class.

## Review question

For each added layer or abstraction, name the behavior it isolates today: an external dependency, transaction/locking rule, domain policy, complex mapping, or interchangeable implementation. If none applies, keep the feature shallow.
