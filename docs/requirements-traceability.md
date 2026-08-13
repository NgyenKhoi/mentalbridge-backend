# Requirements Traceability

This document maps the approved project sources to the backend architecture. The source documents remain authoritative for product scope:

- [Capstone registration](<../FA26_TraLTB_MentalBridge (1).docx>)
- [Project tracking workbook](../Report3_Project%20Tracking.xlsx), especially the 153 WBS functions and actor flows

The registration file contains student/supervisor contact details. Confirm repository visibility and team approval before publishing the binary; this traceability document intentionally does not reproduce those personal details.

Architecture may add safety, privacy, reliability, and implementation constraints, but must not silently remove a required feature. Any scope change requires supervisor/product approval and an ADR or updated tracking source.

## Product and technology alignment

| Source commitment | Architecture coverage |
| --- | --- |
| Consumer product for Vietnamese users aged 18–30 | User/guest APIs, `vi-VN` locale baseline, mobile-facing REST plus realtime WebSocket |
| React web admin and end-user mobile application | Edge proxy exposes REST/JSON; only Realtime Service exposes WSS |
| Spring Boot and Node.js backend | Three Spring Boot and three NestJS services as fixed by ADR 0001 |
| PostgreSQL and MongoDB | PostgreSQL owns relational transactions; MongoDB owns journals, analysis documents, conversations/messages |
| Gemini/OpenAI through prompt engineering | NestJS Journal/AI provider adapters with strict versioned output schemas; no fine-tuning |
| PhoBERT inference-only comparison | Isolated Python worker consuming Kafka jobs; no model training/fine-tuning |
| Microservices and API integration | Owner databases, OpenAPI REST/JSON DTOs, Kafka async contracts, no cross-service table access |
| AWS EC2, Docker, Nginx, Docker Compose, GitHub Actions | Deployment baseline retained; Kafka and Redis included in local/hosted composition |
| Grafana, Prometheus, Swagger/OpenAPI | Metrics/observability and contract rules are required by engineering guides |
| Privacy, consent, audit, deletion and retention | Care consent owner, owner-enforced authorization, minimized audit projection, idempotent deletion workflow |

Kafka and Redis are architecture additions supporting realtime and asynchronous workloads. Kafka is the durable event/task backbone. Redis is limited to ephemeral presence/routing/fan-out, rate-limit, delivery/idempotency, and expiring hashed OTP state; it is not a database-query cache and never replaces PostgreSQL, MongoDB, or Kafka.

## WBS ownership map

| WBS functions | Functional area | Authoritative owner | Main integration/storage |
| --- | --- | --- | --- |
| 1–6, 8, 109 | registration, login/logout, reset, RBAC, admin login | Identity Service (Spring) | PostgreSQL; REST/JWT; account Kafka events |
| 7 | anonymous PHQ-9/GAD-7 | Care Service (Spring) | PostgreSQL with expiry policy; REST only, no silent account linking |
| 9–15 | profile, consent, specialist grants, deletion request | Care for profile/consent; Identity coordinates account deletion | REST owner checks; Kafka deletion fan-out |
| 16–20 | journal CRUD/history | Journal/AI Service (NestJS) | MongoDB; REST/JSON; encrypted content |
| 21–27 | questionnaire start/submit/result/history/deletion | Care Service | PostgreSQL transaction, versioned definitions and deterministic scoring |
| 28–30 | LLM analysis and re-run | Journal/AI Service | REST to Care for current AI consent; job/outbox; provider API; Kafka completion |
| 31–32, 141–147 | dataset import/CRUD, PhoBERT inference, benchmark comparison | Journal/AI; PhoBERT Worker for inference only | Private object storage, MongoDB/PostgreSQL metadata, Kafka jobs/results |
| 33–39 | risk, intervention, severe alert and crisis guidance | Care for classification/intervention; Content/Notification for reviewed hotline content | Synchronous deterministic Care path; Kafka only for non-critical follow-up/notification |
| 40–43, 121–130 | self-help resources and hotline CRUD/use | Content/Notification Service (NestJS) | PostgreSQL; REST/JSON; reviewed admin workflow |
| 44–49 | specialist discovery, filtering, matching/recommendation | Consultation Service (Spring) | PostgreSQL; REST; transparent versioned matching criteria |
| 50–59 | specialist profile, documents, verification, availability | Consultation Service | PostgreSQL plus private object storage; REST; audited admin approval |
| 60–71, 139–140 | booking, transitions, history and admin monitoring | Consultation Service | PostgreSQL constraints/locking/idempotency; Kafka status events |
| 72–81 | conversation, chat, receipts, unread count, tombstone, report | Realtime Service (NestJS) | REST history + client WebSocket; MongoDB truth; Redis presence/fan-out; Kafka facts |
| 82–88 | specialist reviews, aggregate rating and reports | Consultation Service | PostgreSQL; REST; moderation status and Kafka audit/report events |
| 89–94 | follow-up, reassessment and progress comparison | Care Service | PostgreSQL; REST; Kafka reminders/projection updates |
| 95–98 | notification list/detail/read/delete | Content/Notification owns durable notification; Realtime delivers live payload | PostgreSQL REST history; Kafka `NotificationCreated`; WebSocket delivery via Redis |
| 99–102 | personal emotion, assessment and risk analytics | Care owns assessment/risk projection; Journal/AI supplies approved structured indicators | Kafka projections plus bounded owner REST endpoints |
| 103–108 | specialist dashboard and consented user data | Consultation composes its own workload; Care and Journal/AI remain data owners | Current consent authorization through Care REST; scoped owner REST reads; no shared DB |
| 110–120 | admin dashboard, account and specialist administration | Identity and Consultation owner-specific admin APIs | REST/JSON with audit; dashboard widgets use bounded projections |
| 131–138 | review/chat moderation | Consultation owns review actions; Realtime owns message actions; reporter/admin case projection consumes Kafka | Authorized REST to the target owner; minimized evidence and audit |
| 148–149 | system activity and platform trend reporting | Owner-specific Kafka read projections; Care owns de-identified wellbeing trend projection | Bounded REST dashboard queries; no distributed runtime join |
| 150–153 | audit search and retention policy | Identity coordinates minimized audit/security projection; every service enforces its own retention/deletion | Kafka audit facts, PostgreSQL projections and owner REST administration |

## Actor-flow coverage

- **Anonymous:** questionnaire → result → screening/risk guidance → optional register. Anonymous data is never silently linked to the new account.
- **User:** assessment/journal → analysis → risk/intervention → specialist discovery/booking → scoped consent → chat → review → follow-up/analytics.
- **Specialist:** register → submit verification → admin approval → availability/appointments → consented data → chat → complete session → receive reviews.
- **Admin:** login → bounded dashboard → accounts/specialists → content/hotlines → moderation → appointments → datasets/evaluation → reporting/audit/retention.

## Safety clarifications added by architecture

These constraints refine rather than contradict the source documents:

- PHQ-9/GAD-7 scoring is authoritative and deterministic; AI is a supporting indicator.
- A positive AI signal cannot downgrade a severe/safety-item path.
- Severe guidance is returned synchronously and remains available when AI, Kafka, Redis, WebSocket, email, or push delivery fails.
- The platform provides screening/referral support, not diagnosis, treatment, continuous monitoring, or guaranteed emergency response.
- Admin dashboards do not imply unrestricted raw journal/chat access.
- Production journals are excluded from research benchmarks by default; explicit governed consent is required for any exception.

## Baseline design gaps to resolve during module contracts

The requirements are represented in domain/architecture documentation, but the logical database baseline still needs explicit aggregates or decisions before the affected iteration is implemented:

1. Referral status/history for the Moderate intervention path.
2. Specialist matching configuration, result provenance, and recommendation explanation.
3. Notification templates and provider delivery-attempt history for email/push retries.
4. Dedicated read projections for personal analytics, admin activity, platform trends, and consolidated audit search.
5. Explicit anonymous-assessment expiry/cleanup configuration and deletion evidence.
6. Moderation evidence snapshot/access policy and appeal/action history.
7. Dataset metadata edit semantics: immutable version replacement versus narrowly editable administrative metadata.

Agents must not invent these behaviors independently. Resolve the relevant rule through product/domain review, then update the contract, data dictionary, migration, tests, and this traceability document together.
