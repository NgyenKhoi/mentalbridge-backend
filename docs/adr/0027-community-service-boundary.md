# ADR 0027: Independent Community service boundary

- Status: Accepted
- Date: 2026-09-29
- Decision ID: `MB-COMMUNITY-SERVICE-001`
- Delivery tracking: MB-573, MB-604, and Community stories MB-574 through MB-582

## Context

The current architecture fixes six core deployables and lists social/community feeds as out of scope unless they are formally added. The approved Community epic now requires an independently deployable peer-support context whose public identity, posts, interactions, moderation, reports, blocks, and media metadata do not depend on Care or Journal data.

MB-604 needs a runnable foundation before those business slices begin. This decision records the new owner and runtime only; it does not make any MB-574 through MB-582 business path executable and does not redefine Community Contract v1.

## Decision

Add `community-service` as an independent Spring Boot 4 / Java 21 deployable with its own PostgreSQL database and database user.

- Community will own Community display profiles and, when their delivery stories are implemented, posts, comments, reactions, moderation, reports, blocks, and Community media metadata.
- Identity-issued RS256 JWTs are verified locally from issuer, audience, and public-key configuration. Community does not synchronously call Identity to authenticate a request.
- Community never reads Care, Journal/AI, SupportPlan, assessment, screening, emotion, or AI-analysis data for ranking or personalization.
- Liquibase is the only Community schema authority. MB-604 records an empty baseline; no business table is created by the foundation task.
- Cloudinary is the future Community media provider seam. Credentials are environment-only, binaries are not stored in PostgreSQL, and no upload behavior is introduced by MB-604.
- Actuator health/readiness is the only unauthenticated HTTP surface in the foundation. Prometheus metrics are exposed for authenticated operators; future product APIs require their versioned contract and delivery story.
- Community uses Maven, the repository Spring configuration baseline, Eureka registration, Docker multi-stage build, and the shared backend quality gate.

## Alternatives considered

- Put Community inside Realtime Service: rejected because durable Community publication/moderation ownership is distinct from chat delivery and presence.
- Put Community inside Content/Notification: rejected because reviewed editorial resources and peer-authored social content have different trust, moderation, and lifecycle boundaries.
- Add Community as a NestJS service: feasible, but Spring Boot/PostgreSQL matches the rule-heavy relational moderation and ownership model and reuses the repository's local JWT/Liquibase conventions.

## Consequences

- The backend now has seven core deployables: four Spring Boot services and three NestJS services.
- Community requires a separately provisioned PostgreSQL database, scoped login, migration job, and runtime configuration.
- Existing Community product stories remain non-executable until their own contracts, migrations, authorization, privacy behavior, and tests are delivered.
- The previous blanket exclusion of social/community feeds is amended only for the governed Community Contract v1 scope. General social networking, clinical ranking, and cross-context profiling remain out of scope.
