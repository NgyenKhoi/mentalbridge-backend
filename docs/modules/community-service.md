# Community Service specification

## Ownership

`community-service` is the independent owner for the governed Community peer-support context established by ADR 0027 (`MB-COMMUNITY-SERVICE-001`). Future delivery stories may add Community display profiles, posts, comments, reactions, moderation, reports, blocks, and Community-owned media metadata.

It does not own Identity credentials, Care profiles, screening, SupportPlan, Journal entries, emotion history, AI analysis, specialist chat, reviewed editorial resources, or notification delivery.

## MB-604 executable foundation

- Spring Boot 4 / Java 21 application built with Maven.
- Dedicated PostgreSQL connection and an empty Liquibase baseline.
- Local RS256 verification of Identity-issued JWT issuer, audience, signature, expiry, and roles.
- Environment-only Cloudinary configuration seam; no upload or delivery operation exists yet.
- Public Actuator health/readiness, authenticated Prometheus metrics, and deny-by-default future application paths.
- Multi-stage Docker runtime/migration targets and CI matrix coverage.

## Delivery boundary

MB-604 creates no Community business table, product endpoint, ranking rule, media upload path, or asynchronous integration. MB-574 through MB-582 must pair each new behavior with the frozen Community Contract v1, owner migration, privacy/authorization rules, and boundary tests. Community must remain independent of synchronous Care and Journal/AI APIs.
