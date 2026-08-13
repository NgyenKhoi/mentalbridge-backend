# MentalBridge Backend

Backend platform for **MentalBridge (MBMS)**, an intelligent mental-health screening and early-intervention system for Vietnamese students and young adults (18-30).

MentalBridge helps users complete PHQ-9/GAD-7 self-screenings, keep an emotion journal, receive AI-assisted emotion insights, follow a risk-appropriate support workflow, and connect with an approved specialist. It is a screening and support product, **not a diagnosis, emergency service, or replacement for professional treatment**.

## Product scope

- End-user mobile APIs: authentication, profile, consent, journal, assessments, insights, interventions, subscriptions, consultation credits, appointments, chat, notifications, and personal trends.
- Specialist APIs: verification profile, availability, appointments, consented user data, consultation chat, follow-up, earnings, and payout history.
- Administration APIs: account approval, subscription/payment and payout operations, content and hotline management, moderation, aggregated reporting, audit, retention, and AI evaluation datasets.
- AI/NLP integration: Gemini or OpenAI through prompt engineering; PhoBERT inference is used only as an experimental baseline.
- Anonymous PHQ-9/GAD-7 screening with minimal collection and no silent linkage to a later account.

## Proposed architecture

The recommended starting point is a **small microservice landscape**, not one service per feature. Keep domain ownership explicit and split further only when deployment or team ownership justifies it.

| Component | Technology | Responsibility | Primary store |
| --- | --- | --- | --- |
| Identity Service | Spring Boot 4.x | Accounts, roles, sessions, password reset | PostgreSQL |
| Care Service | Spring Boot 4.x | Profiles, consent grants, assessments, risk, interventions, follow-up | PostgreSQL |
| Consultation Service | Spring Boot 4.x | Specialists, verification, availability, appointments, reviews | PostgreSQL |
| Journal & AI Service | NestJS | Journals, LLM orchestration, analysis jobs/results, benchmark coordination | MongoDB + PostgreSQL metadata |
| Realtime Service | NestJS | REST message APIs, WebSocket chat/notification delivery, presence, receipts | MongoDB + Redis |
| Content & Notification Service | NestJS | Self-help resources, hotlines, preferences, notification/provider delivery | PostgreSQL |
| PhoBERT Worker | Python | Experimental inference jobs only | No authoritative business store |

The updated project-tracking workbook adds a financial bounded context for premium subscriptions, payments, consultation credits, specialist earnings, and payouts. The seven-deployable baseline does not yet assign that authority; implementation is blocked pending an ADR rather than being placed implicitly in Identity or Consultation.

Use REST/JSON DTOs for synchronous business APIs and service-to-service queries. Spring services register with Eureka and Java consumers use OpenFeign only as a REST client adapter; discovery does not change ownership, authorization, or OpenAPI contracts. WebSocket terminates only at Realtime Service for live client chat, presence, receipts, and in-app notifications. Kafka carries durable asynchronous commands/events for analysis, notification, audit, reporting, and deletion workflows. Redis carries only ephemeral presence, connection routing, fan-out, rate-limit, delivery/idempotency, and expiring hashed OTP state; it is not a database-query cache or business source of truth.

## Core flow

```text
Assessment + journal signals
          |
          v
Deterministic risk policy  --->  Minimal/Mild: self-help + reassessment
          |                 --->  Moderate: specialist referral + follow-up
          +-------------------->  Severe: crisis information + prominent guidance

AI supplies supporting indicators; it must not override validated questionnaire
scoring, invent a diagnosis, or be the sole trigger for a safety decision.
```

Severe-risk handling must be deterministic, immediate, auditable, and usable even if the AI provider or message broker is unavailable. Hotline content must be maintained and reviewed by an administrator; never hard-code unverified emergency numbers in model prompts.

## Repository documentation

- [Domain and use cases](docs/domain-and-use-cases.md)
- [Requirements traceability to the capstone registration and 162-function WBS](docs/requirements-traceability.md)
- [Per-module business, use-case, implementation and task specifications](docs/modules/README.md)
- [Architecture](docs/architecture.md)
- [Eureka discovery and OpenFeign ADR](docs/adr/0002-eureka-discovery-and-openfeign-clients.md)
- [Kiến trúc module microservices và ngôn ngữ đã chốt](docs/microservice-module-suggestions.md)
- [Engineering rules](docs/engineering-rules.md)
- [Mandatory agent workflow and review guide](docs/agent-guides/README.md)
- [PostgreSQL data model](docs/database/postgresql.md)
- [MongoDB collections](docs/database/mongodb.md)
- [Initial PostgreSQL schema](database/postgresql/001_initial_schema.sql)
- [PostgreSQL field data dictionary](docs/database/postgresql-field-data-dictionary.md)

## Delivery roadmap

| Iteration | Outcome |
| --- | --- |
| 1 - Screening foundation | Identity, profile/consent, anonymous and authenticated PHQ-9/GAD-7, journal CRUD, admin login |
| 2 - Insight and intervention | Asynchronous journal analysis, deterministic risk classification, resources, crisis guidance |
| 3 - Human support and premium access | Specialist approval/profile, subscription/payment, consultation credits, availability, booking, consented access, chat, reviews, follow-up, notifications |
| 4 - Governance and research | Administration, payouts, moderation, deletion/retention, audit, reporting, dataset import, LLM vs PhoBERT benchmark |

## Technology baseline

- Java 21+, Spring Boot 4.x, Spring Security Resource Server, Spring Data, Liquibase, OpenAPI
- Eureka for Spring service discovery; OpenFeign plus Resilience4j for Java owner-to-owner REST clients
- Node.js LTS + NestJS for Journal/AI, Realtime, and Content/Notification services
- Python for the isolated PhoBERT inference worker
- PostgreSQL for transactional and relational data
- MongoDB for journal text, chat messages, and variable AI/evaluation payloads
- Kafka for durable asynchronous commands/events; Redis for bounded ephemeral realtime/OTP coordination, not database-query caching
- `migrate-mongo` for MongoDB migrations; Cloudinary private/authenticated storage; Brevo transactional email API
- Docker Compose for local development; GitHub Actions for CI
- OpenTelemetry-compatible traces, Prometheus metrics, Grafana dashboards, structured JSON logs

The local stack includes Eureka, PostgreSQL, MongoDB, Kafka, Redis, and the application services through Docker Compose. Kubernetes, a service mesh, distributed secrets platforms, and multiple observability products are outside the initial scope unless the team can demonstrate a concrete requirement.

## Status

This repository is currently in the design/bootstrap phase. The source requirements are the capstone registration document and project-tracking workbook at the repository root. The documentation here turns those inputs into an implementable backend baseline; API contracts and migrations remain the source of truth once implementation begins.

## License

See [LICENSE](LICENSE).
