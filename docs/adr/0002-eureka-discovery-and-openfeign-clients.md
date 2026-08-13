# ADR 0002: Eureka Discovery and OpenFeign Clients

- Status: Accepted
- Date: 2026-08-13

## Context

MentalBridge services use REST/JSON for synchronous business communication, but independently deployed instances still need a consistent way to publish their location and find healthy providers. The Java team also needs one familiar, testable client style for Spring-to-Spring calls without sharing controller classes or persistence models.

## Decision

Run a Eureka service registry as an infrastructure component. Every Spring Boot business service uses Eureka Client to register its service ID and resolve Spring providers. Eureka contains only discovery metadata; it is not an API gateway, configuration store, authorization source, health-data store, or replacement for application readiness checks.

Use Spring Cloud OpenFeign for Java consumers that currently make owner-to-owner REST calls:

- `care-service` may call the minimum Identity decision or projection required for exceptional current account facts;
- `consultation-service` may call Care for current consent and authorization decisions.

Do not add OpenFeign to a service until it has an outbound REST dependency. `identity-service` therefore registers with Eureka but does not include OpenFeign in the initial scaffold.

Feign clients are consumer-owned infrastructure adapters behind narrow application ports. Their request, response, authentication, and RFC 9457 error shapes come from the provider OpenAPI contract; Java interfaces and DTO implementations are not shared between deployables. Calls propagate trace, correlation, caller, and permitted end-user context, use explicit deadlines, and are wrapped by Resilience4j with operation-specific retry, circuit-breaker, and domain-safe fallback behavior.

Service discovery changes only address resolution. REST remains the synchronous protocol, Kafka remains the durable asynchronous backbone, WebSocket remains client-to-Realtime only, and each provider remains responsible for authorization and its own data.

## Consequences

- Local/demo and hosted environments operate Eureka alongside the edge proxy and data/messaging infrastructure.
- A registry outage must not bypass authorization or trigger a database fallback. New resolutions may fail with dependency unavailable; safety-critical local Care behavior remains available.
- Tests disable live Eureka registration unless they explicitly test discovery and use controlled provider instances for Feign behavior.
- OpenAPI provider and consumer contract tests remain mandatory; successful discovery does not prove payload compatibility.
- Call depth remains shallow and remote calls never run inside a database transaction or lock.
- Non-Java services continue using REST/JSON and stable deployment addressing until a language-compatible Eureka registration approach is validated; they do not adopt Java-specific client contracts.

## Rejected alternatives

- Hard-coded instance URLs in business code: rejected because they couple deployable locations to implementation.
- OpenFeign interfaces shared as a common business library: rejected because it would make Java implementation types the cross-service contract.
- Eureka for authorization, configuration, or orchestration: rejected because those responsibilities belong to service owners, external configuration, and bounded workflows.
- Kafka request/reply for discovery-free synchronous calls: rejected because Kafka is not the immediate query path.
