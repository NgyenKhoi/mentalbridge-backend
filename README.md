# MentalBridge Backend

Backend platform for **MentalBridge (MBMS)**, an intelligent mental-health screening and early-intervention system for Vietnamese students and young adults (18-30).

MentalBridge helps users complete PHQ-9/GAD-7 self-screenings, keep an emotion journal, receive AI-assisted emotion insights, follow an approved support workflow, and connect with an approved specialist. It is a screening and support product, **not a diagnosis, emergency service, or replacement for professional treatment**.

## Review 1 Docker Compose stack

The root [`compose.yml`](compose.yml) is intentionally scoped to executable Review 1 flows rather than every planned microservice. It provides two profiles:

| Profile | Services |
| --- | --- |
| `demo` | Frontend, Identity, Care, Content/Notification, and explicit migrations against the shared dev/staging PostgreSQL databases |
| `full-test` | Everything in `demo`, plus Realtime, its migration against the shared dev/staging MongoDB deployment, and local ephemeral Redis |

Consultation, Journal/AI, PhoBERT, Eureka, and Kafka remain outside this stack until they participate in an executable Review 1 journey. Realtime is available for foundation testing but is not part of the critical mentor-demo path.

Keep the backend and frontend repositories as sibling directories. From this backend repository, prepare the ignored Compose environment file and local Identity keys:

```powershell
.\scripts\prepare-review1-compose-env.ps1
```

The helper generates the ignored `.env`, Identity key material, and application-only secrets without printing secret values or replacing an existing file. It deliberately does not generate or copy database credentials. Populate every cloud connection placeholder from the deployment secret source before starting Compose. To configure it manually instead, copy `.env.compose.example` to `.env`, run `scripts/generate-local-jwt-keys.ps1`, and replace every `replace-*` value. `CONTENT_DATABASE_URL`, `REALTIME_MONGODB_URI`, and `REALTIME_REDIS_URL` must contain URL-encoded passwords when a password includes reserved URL characters.

Dev and staging intentionally share the current service-owned AWS RDS databases and MongoDB Atlas deployment. Compose does not create, reset, expose, or remove those durable stores. Production will use separate database endpoints and credentials when it is provisioned. Because both pre-production environments share migration history, every migration must remain forward-compatible with both running application versions.

Start the mentor-facing stack:

```powershell
docker compose --profile demo up --build -d
docker compose ps --all
```

One-shot migration jobs are expected to show `Exited (0)` after completing. The demo profile runs Content schema migrations first, then the separately tracked `migrations/review1/1_seed_review1_controlled_resource.sql` migration. It inserts the idempotent, visibly labeled Review 1 resource into the shared pre-production Content database. Future production deployment must run `npm run migrate:up` only and must not run the Review 1 seed migration. Identity writes synthetic verification links to its private volume in the default controlled-demo configuration. Retrieve the latest link without exposing the challenge in logs:

```powershell
docker compose exec identity sh -c 'ls -1t /var/lib/mentalbridge/identity-verification/*.verification-url | head -n 1 | xargs cat'
```

Then open `http://localhost:3000` and demonstrate:

```text
Register/Login -> Profile -> Consent -> PHQ-9 vi-VN -> Result -> History -> Reassessment -> Progress
```

The deterministic evidence cases use the same total score with different safety-item answers:

```text
[1,1,1,1,1,1,1,1,0] -> total 8 -> MILD + NEGATIVE_SAFETY_SCREEN
[1,1,1,1,1,1,1,0,1] -> total 8 -> MILD + POSITIVE_SAFETY_SCREEN
```

The Content seed is idempotent and visibly labeled as controlled demo data; it is not production clinical approval. To exercise the infrastructure foundation too, start the superset profile:

```powershell
docker compose --profile full-test up --build -d
docker compose ps --all
```

Stop application containers while retaining the Identity verification and Redis volumes with `docker compose --profile demo down` or `docker compose --profile full-test down`. These commands do not alter the external PostgreSQL or MongoDB databases.

For an EC2 demo host, set `PUBLIC_APP_ORIGIN` and `IDENTITY_VERIFICATION_URL` to the externally reachable HTTPS origin, inject the shared pre-production database secrets, and allow that host through the RDS/Atlas network policies. Keep `SERVICE_BIND_ADDRESS=127.0.0.1`, expose only the frontend through the host firewall/reverse proxy, and replace `local-file` plus the `dev` Spring profile with an approved delivery configuration before any real-user deployment.

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
- [MB-273 initial-check release readiness](docs/sprints/mb-273-initial-check-release-readiness.md)
- [NestJS service framework ADR](docs/adr/0006-nestjs-nodejs-service-framework.md)
- [Eureka discovery and OpenFeign ADR](docs/adr/0002-eureka-discovery-and-openfeign-clients.md)
- [Kiến trúc module microservices và ngôn ngữ đã chốt](docs/microservice-module-suggestions.md)
- [Engineering rules](docs/engineering-rules.md)
- [Mandatory agent workflow and review guide](docs/agent-guides/README.md)
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

Pull requests targeting `dev` and pushes to `dev` run the backend service matrix, repository policy, Compose topology validation, and the stable `quality-gate` aggregate check.
- OpenTelemetry-compatible traces, Prometheus metrics, Grafana dashboards, structured JSON logs

The current Review 1 Compose stack runs application services and local ephemeral Redis while using the shared dev/staging PostgreSQL and MongoDB cloud data plane. Disposable integration tests continue to provision isolated databases through Testcontainers. Kubernetes, a service mesh, distributed secrets platforms, and multiple observability products are outside the initial scope unless the team can demonstrate a concrete requirement.

## Status

This repository is currently in the design/bootstrap phase. The source requirements are the capstone registration document and project-tracking workbook at the repository root. The documentation here turns those inputs into an implementable backend baseline; API contracts and migrations remain the source of truth once implementation begins.

## License

See [LICENSE](LICENSE).
