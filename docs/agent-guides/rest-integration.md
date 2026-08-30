# REST Integration and Resilience

REST/JSON is the only synchronous business and service-to-service integration protocol. The same rules apply to Spring-to-Spring, Spring-to-Node, Node-to-Spring, gateway-to-service, and ordinary client-to-gateway calls. Client WebSocket traffic terminates only at `realtime-service`; services do not query one another through WebSocket.

## Contract rules

- OpenAPI is the source of truth. Generate typed models/clients where practical; never share Java classes, TypeScript types, ORM models, or copied DTO packages between deployables.
- Use `/api/v1` for public APIs and `/internal/v1` for authenticated service APIs. “Internal” does not mean trusted or authorization-free.
- JSON fields use `camelCase`; PostgreSQL uses `snake_case`. UUID/ULID and all 64-bit identifiers are JSON strings.
- Instants are UTC ISO-8601 strings with an offset. A scheduling resource also carries the originating IANA timezone when needed.
- Decimal values requiring exactness are strings. Do not rely on JavaScript floating-point representation.
- Define absent versus `null` semantics. Consumers must tolerate unknown response fields and handle newly introduced enum values safely.
- Errors use RFC 9457 Problem Details plus a stable application `code`, `correlationId`, and field violations. Never expose stack traces or downstream raw responses.
- Commands vulnerable to client or network retries accept `Idempotency-Key` and persist the completed outcome.

## Querying another module

1. Confirm the data-owning service; do not query its database.
2. Request the minimum decision or projection required. Prefer `POST /internal/v1/consent-authorizations:check` returning an authorization decision over downloading all grants.
3. Propagate `traceparent`, `X-Correlation-Id`, caller identity, and end-user context where policy permits.
4. Enforce authorization again in the data owner. The caller's prior check is not sufficient.
5. Set a deadline and define what the caller does when the owner is slow or unavailable. Fail closed for authorization and consent.
6. Resolve Spring provider instances through Eureka by logical service ID. Discovery metadata selects an address only; it never proves identity, authorization, or contract compatibility.

Avoid chatty loops. Add a batch owner endpoint or an asynchronously built read projection when one request would otherwise issue many remote calls. Do not perform remote calls while holding a database transaction or lock.

## Timeout, retry, and circuit breaker policy

Each outbound client has its own configuration and metrics. Start with a connection timeout near 500 ms and a total request timeout near 2 seconds for internal queries, then tune from measured latency and endpoint behavior. AI/provider calls use separate, explicitly longer budgets and concurrency limits.

Retry only transient connection failures, `408`, `429` when `Retry-After` is honored, and selected `5xx` responses. Use exponential backoff with jitter and a small bounded attempt count. Do not retry validation, authorization, conflict, or other deterministic `4xx` responses.

Retry `GET`/`HEAD` only when safe. Retry a mutation only when it carries a persisted idempotency key and the provider guarantees replay behavior. A timeout after sending a mutation is an unknown outcome, not proof of failure.

Use a circuit breaker per remote dependency and operation group, not one global breaker. Count transport failures, timeouts, and selected `5xx`; do not count expected domain `4xx`. Configure minimum call volume before opening, a bounded open interval, and limited half-open probes. Exact values belong in typed configuration and must be covered by tests rather than scattered constants.

Fallbacks must be domain-safe:

- authorization/consent uncertainty: deny or return dependency unavailable;
- assessment scoring and reviewed safety guidance: execute locally and remain available;
- optional display metadata: omit or use a clearly stale local projection if permitted;
- booking: return an explicit unavailable response; never pretend success;
- notifications/reporting: enqueue through the outbox and complete asynchronously.

Spring-to-Spring consumers use OpenFeign behind a consumer-owned application port and wrap operations with Resilience4j. Feign interfaces and DTO implementations remain inside the consumer; derive them from the provider OpenAPI contract without sharing provider controller or persistence classes. Configure explicit connect/read deadlines, safe retries, circuit breakers, correlation/authentication propagation, and domain-safe fallbacks per operation. Node implementations use the standard project HTTP client plus one approved breaker library. Client library or discovery choice must not change the contract or failure semantics.

Treat no Eureka instance, registry unavailability, DNS/connect failure, and provider timeout as explicit dependency failures. Do not fall back to another service's database, an unverified static URL, or a stale projection for current authorization and safety decisions.

## Cross-language compatibility checks

- Validate every request and response against OpenAPI at runtime boundaries where feasible.
- Spring callers must test against a Node provider stub generated or validated from the same OpenAPI, and Node callers do the inverse.
- Test UUID strings, large numeric strings, offsets/timezones, optional/null fields, unknown fields, enum evolution, Problem Details, timeout, connection reset, and malformed JSON.
- Never assert Java- or Node-specific serialization details that are absent from the contract.
