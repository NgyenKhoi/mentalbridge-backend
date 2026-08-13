---
name: mentalbridge-architecture
description: Guard MentalBridge backend architecture, module ownership, communication protocols, safety/privacy rules, provider boundaries, configuration, and deployment readiness. Use when analyzing or changing services, integrations, Redis/Kafka/WebSocket/REST behavior, AI/risk flows, Cloudinary/Brevo adapters, environment settings, infrastructure, or ADRs.
---

# MentalBridge Architecture

First apply `mentalbridge-repository-workflow`. Read `docs/architecture.md`, `docs/domain-and-use-cases.md`, `docs/requirements-traceability.md`, accepted ADRs, and relevant agent guides.

- Keep service ownership explicit. Never query another service's storage or share framework/internal models.
- Use REST/JSON and OpenAPI for synchronous business APIs and current-data queries.
- Use Eureka only for Spring service registration and address lookup. Use OpenFeign only as a consumer-owned Java REST adapter with OpenAPI contracts, explicit deadlines, Resilience4j, and provider-side authorization.
- Use WebSocket only between clients and `realtime-service` for realtime delivery.
- Use Kafka for durable asynchronous commands/events, never request/reply. Use transactional outbox for PostgreSQL-caused messages and idempotent consumers.
- Use Redis only for bounded ephemeral presence/routing/fan-out, rate limits, delivery/idempotency state, and expiring hashed OTP challenges. Never use it as a query cache or durable store.
- Keep scoring, safety guidance, risk, consent authorization, and booking invariants local and available without optional AI, Redis, or Kafka dependencies.
- Keep AI supporting only: no diagnosis, PHQ/GAD scoring, sole severe-risk decision, chain-of-thought exposure, or consent bypass.
- Keep Cloudinary and Brevo behind application ports. Use private/authenticated signed file access; MentalBridge owns authorization, OTP validity, and notification state.
- Prepare production-safe settings during development: typed validation, external secrets, TLS-capable endpoints, bounded pools/timeouts, health/readiness, metrics, graceful shutdown, and no production `.env` dependency.
- Create an ADR for changes to boundaries, storage ownership, risk policy strategy, identity/token design, broker, encryption, or AI provider/retention.

Search the repository for stale conflicting language after changing a decision.
