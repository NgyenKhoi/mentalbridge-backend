# MentalBridge Backend

Backend platform for **MentalBridge (MBMS)**, an intelligent mental-health screening and early-intervention system for Vietnamese students and young adults (18-30).

MentalBridge helps users complete PHQ-9/GAD-7 self-screenings, keep an emotion journal, receive AI-assisted emotion insights, follow an approved support workflow, and connect with an approved specialist. It is a screening and support product, **not a diagnosis, emergency service, or replacement for professional treatment**.

## Product scope

- End-user mobile APIs: authentication, profile, consent, journal, assessments, insights, interventions, subscriptions, consultation credits, appointments, chat, notifications, and personal trends.
- Specialist APIs: approved profile, channel-specific availability, scheduled consultation appointments, consented user data, follow-up, earnings, and provider payout history.
- Administration APIs: account/profile approval, subscription/payment/upgrade and payout reconciliation, reviewed content management, moderation, aggregated reporting, audit, retention, and AI evaluation datasets.
- AI/NLP integration: Gemini or OpenAI through prompt engineering; PhoBERT inference is used only as an experimental baseline.
- Anonymous PHQ-9/GAD-7 screening with minimal collection and no silent linkage to a later account.

## Proposed architecture

The recommended starting point is a **small microservice landscape**, not one service per feature. Keep domain ownership explicit and split further only when deployment or team ownership justifies it.

| Component | Technology | Responsibility | Primary store |
| --- | --- | --- | --- |
| Identity Service | Spring Boot 4.x | Accounts, roles, sessions, password reset | PostgreSQL |
| Care Service | Spring Boot 4.x | Profiles, consent grants, assessments, safety/support policy, interventions, follow-up | PostgreSQL |
| Consultation Service | Spring Boot 4.x | Specialist approval/discovery, subscription/payment/upgrade, credits, scheduled consultations, earnings/provider payouts, reviews | PostgreSQL |
| Journal & AI Service | Node.js 22+, TypeScript, NestJS | Journals, LLM orchestration, analysis jobs/results, benchmark coordination | MongoDB + PostgreSQL metadata |
| Realtime Service | Node.js 22+, TypeScript, NestJS, Socket.IO | REST message APIs, WebSocket chat/notification delivery, presence, receipts | MongoDB + Redis |
| Content & Notification Service | Node.js 22+, TypeScript, NestJS | Self-help resources, preferences, notification/provider delivery | PostgreSQL |
| PhoBERT Worker | Python | Experimental inference jobs only | No authoritative business store |

ADR 0005 assigns the workbook's financial bounded context to a cohesive `billing` feature inside Consultation Service, preserving the seven-deployable baseline. It owns paid subscriptions, Care-to-Plus upgrades, consultation credits, specialist earnings, and payout reconciliation. Downgrade and user-initiated refund are unsupported; MoMo is the sole production payment/payout provider, while local/CI uses MoMo-shaped fakes.

Use REST/JSON DTOs for synchronous business APIs and service-to-service queries. Spring services register with Eureka and Java consumers use OpenFeign only as a REST client adapter; discovery does not change ownership, authorization, or OpenAPI contracts. WebSocket terminates only at Realtime Service for live client chat, presence, receipts, and in-app notifications. Kafka carries durable asynchronous commands/events for analysis, notification, audit, reporting, and deletion workflows. Redis carries only ephemeral presence, connection routing, fan-out, rate-limit, delivery/idempotency, and expiring hashed OTP state; it is not a database-query cache or business source of truth.

## Core flow

```text
Questionnaire result ----> screeningLevel
PHQ-9 item 9 -----------> safetyStatus
Approved local policy --> supportTier --> approved catalogue actions
Active plan version ----> entitlementPlan

AI supplies supporting indicators; it must not override validated questionnaire
scoring, change safety status, invent a diagnosis, or create an intervention.
```

Safety handling must be deterministic, immediate, auditable, non-paywalled, and usable even if optional AI or messaging dependencies are unavailable. MentalBridge has no hotline catalogue and must not hard-code unverified emergency numbers or facility claims in prompts or application code. See [ADR 0009](docs/adr/0009-care-screening-safety-and-support-boundaries.md) and the [policy register](docs/policies/README.md).

## Repository documentation

- [Domain and use cases](docs/domain-and-use-cases.md)
- [Requirements traceability to the capstone registration and 162-function WBS](docs/requirements-traceability.md)
- [Per-module business, use-case, implementation and task specifications](docs/modules/README.md)
- [Architecture](docs/architecture.md)
- [Node.js service stack](docs/nodejs-service-stack.md)
- [Sprint 1 backend backlog guide](docs/sprint-1-backlog-guide.md)
- [NestJS service framework ADR](docs/adr/0006-nestjs-nodejs-service-framework.md)
- [Eureka discovery and OpenFeign ADR](docs/adr/0002-eureka-discovery-and-openfeign-clients.md)
- [Kiến trúc module microservices và ngôn ngữ đã chốt](docs/microservice-module-suggestions.md)
- [Engineering rules](docs/engineering-rules.md)
- [Mandatory agent workflow and review guide](docs/agent-guides/README.md)
- [Sprint 2 integrated journey and release evidence](docs/sprint-2-integrated-release-evidence.md)
- [Sprint 2 runbook, traceability, and release evidence](docs/sprints/sprint-2-runbook-traceability.md)
- [PostgreSQL data model](docs/database/postgresql.md)
- [MongoDB collections](docs/database/mongodb.md)
- [Non-executable whole-system PostgreSQL model](database/postgresql/001_initial_schema.sql) — owner namespaces are visual only; each module deploys to its own database/default `public` schema
- [PostgreSQL field data dictionary](docs/database/postgresql-field-data-dictionary.md)

## Delivery roadmap

| Iteration | Outcome |
| --- | --- |
| 1 - Screening foundation | Identity, profile/consent, anonymous and authenticated PHQ-9/GAD-7, journal CRUD, admin login |
| 2 - Insight and intervention | Asynchronous journal analysis, deterministic safety/support policy, approved resources and safety guidance |
| 3 - Human support and premium access | Specialist approval/profile, subscription/payment, consultation credits, availability, booking, consented access, chat, reviews, follow-up, notifications |
| 4 - Governance and research | Administration, payout history/reconciliation, moderation, deletion/retention, audit, reporting, dataset import, LLM vs PhoBERT benchmark |

## Technology baseline

- Java 21+, Spring Boot 4.x, Spring Security Resource Server, Spring Data, Liquibase, OpenAPI
- Eureka for Spring service discovery; OpenFeign plus Resilience4j for Java owner-to-owner REST clients
- Node.js 22 or newer + strict TypeScript + NestJS 11 for Journal/AI, Realtime, and Content/Notification
- Python for the isolated PhoBERT inference worker
- PostgreSQL for transactional and relational data
- MongoDB for journal text, chat messages, and variable AI/evaluation payloads
- Kafka for durable asynchronous commands/events; Redis for bounded ephemeral realtime/OTP coordination, not database-query caching
- `migrate-mongo` for MongoDB migrations; Cloudinary private/authenticated storage; Brevo transactional email API
- Docker Compose for local development; GitHub Actions basic CI is introduced after the current bootstrap integration is stable

The repository does not yet require a GitHub Actions check on PRs targeting `dev`. Until the basic CI gate is enabled, reviewers require recorded local checks; a missing CI status is not a passing result.
- OpenTelemetry-compatible traces, Prometheus metrics, Grafana dashboards, structured JSON logs

The local stack includes Eureka, PostgreSQL, MongoDB, Kafka, Redis, and the application services through Docker Compose. Kubernetes, a service mesh, distributed secrets platforms, and multiple observability products are outside the initial scope unless the team can demonstrate a concrete requirement.

### Local Docker infrastructure

The repository includes a secrets-free infrastructure stack for local
development and service integration tests:

```powershell
.\scripts\docker-local.ps1 up
.\scripts\docker-local.ps1 status
.\scripts\docker-local.ps1 logs
.\scripts\docker-local.ps1 down
```

Start Docker Desktop first and wait until `docker info` succeeds. The compose
file exposes separate PostgreSQL databases for Identity, Care, and
Content/Notification, plus MongoDB, Redis, and single-node Kafka. The passwords
are local-only development values and must never be reused outside this stack.

Spring integration tests use Testcontainers and start isolated temporary
containers automatically; the Docker daemon is the only required integration
test prerequisite. The compose stack is useful for manually running services
against stable local infrastructure and is not a production deployment.

## Status

This repository is currently in the design/bootstrap phase. The source requirements are the capstone registration document and project-tracking workbook at the repository root. The documentation here turns those inputs into an implementable backend baseline; API contracts and migrations remain the source of truth once implementation begins.

## License

See [LICENSE](LICENSE).
