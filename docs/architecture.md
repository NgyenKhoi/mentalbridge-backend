# Architecture

## 1. Architectural drivers

1. Mental-health and journal data require least privilege, consent enforcement, traceability, deletion, and minimized exposure.
2. Assessment scoring and reviewed safety guidance must remain available when AI or messaging dependencies fail.
3. AI calls and benchmark jobs are slow, costly, and failure-prone, so they are asynchronous and observable.
4. Appointment booking requires strong transactional consistency; chat and analytics favor scalable document/event storage.
5. A five-person capstone team must be able to run, test, and demonstrate the entire system locally.

## 2. Bounded contexts

| Context | Owns | Does not own |
| --- | --- | --- |
| Identity | credentials, account state, roles, sessions | profile health data |
| Care | user profile, consent, assessment, safety/support policy, intervention, follow-up | raw chat messages |
| Consultation/Billing | specialist profile/approval, plan versions, subscriptions, payments, consultation credits, slots, appointments, earnings, payout destinations/requests, reviews | account passwords, journals, chat messages |
| Journal/AI | journal revisions, analysis jobs/results, AI evaluation | authoritative assessment scoring |
| Realtime | conversations, messages, receipts, presence | consent source of truth |
| Content/Notification | reviewed self-help resources, notification preferences/delivery | screening, safety, or support-tier decisions |
| Governance/Reporting | audit events, moderation cases, de-identified projections | transactional sources of truth |

The core deployable business services are fixed as Spring Boot `identity-service`, `care-service`, and `consultation-service`, plus NestJS/TypeScript `journal-ai-service`, `realtime-service`, and `content-notification-service` using the ADR 0006 stack. ADR 0011 defers Python `phobert-worker` as an optional future benchmark baseline; it is not a current runtime or release dependency. Governance/reporting is implemented as bounded admin APIs and Kafka projections inside the relevant owner until a future ADR justifies another deployable. The edge gateway/reverse proxy and Eureka registry are infrastructure and contain no business orchestration.

ADR 0005 assigns the cohesive billing bounded context to `consultation-service` without adding another deployable. Its PostgreSQL database is authoritative for plan versions, paid subscriptions, MoMo payments/IPNs, upgrade offsets, consultation credits and ledger entries, specialist earnings, encrypted payout destinations, and MoMo payout reconciliation. Other services query narrow current entitlement or appointment-eligibility decisions and never maintain a shadow balance.

## 3. Container view

```text
Mobile App / Admin Web
       | HTTPS REST/JSON + WSS
       v
 Edge reverse proxy
       |
       +--> Identity / Care / Consultation+Billing (Spring Boot) --> PostgreSQL
       +--> Journal-AI (Node.js) --> MongoDB + PostgreSQL job metadata
       +--> Realtime (Node.js) --> MongoDB + Redis --> WebSocket clients
       +--> Content-Notification (Node.js) --> PostgreSQL + Brevo/push providers
                              |
                         Kafka topics
                              |
              Optional future PhoBERT worker (Python)

 Spring services <---- registration and lookup only ----> Eureka registry
```

Do not share ORM entities, repositories, or direct cross-service table access. A single PostgreSQL server is acceptable locally and for the first deployment, but each service owns a separate database and database user as defined by ADR 0004. DTOs are JSON contracts defined through OpenAPI rather than shared Java/TypeScript implementation classes.

## 4. Communication patterns

### Synchronous REST

Use REST/JSON for authentication, CRUD, service-to-service queries, assessment submission/scoring, current consent authorization, slot booking, history recovery, and reviewed resource retrieval. Spring services register with Eureka; Java consumers use OpenFeign to resolve provider service IDs and execute the REST call. Eureka and Feign do not replace OpenAPI contracts, provider authorization, or owner data access. Every mutating endpoint accepts or generates a correlation ID; commands vulnerable to retries accept an `Idempotency-Key`. REST clients use deadlines, bounded safe retries, circuit breakers, and domain-safe fallbacks.

### Client WebSocket

Only Realtime Service accepts WebSocket connections. It owns chat delivery, presence, receipts, and delivery of safe in-app notification payloads. Other services communicate with Realtime through REST or Kafka, never service-to-service WebSocket. Redis stores bounded ephemeral coordination state and coordinates low-latency cross-instance socket fan-out; it does not cache database queries or durable messages. MongoDB remains authoritative for durable conversations/messages.

Cloudinary is the file/object-storage provider. Sensitive evaluation assets and chat attachments use private/authenticated delivery with signed, time-limited access; each business service remains the owner of its file metadata and authorization decisions. Specialist-document upload is not in scope. Brevo is the outbound transactional-email provider, while MentalBridge services retain authoritative notification and OTP state.

### Kafka commands and events

Initial event catalogue:

| Event | Producer | Consumers |
| --- | --- | --- |
| `AnalyzeJournalRevision` | Journal/AI | Journal/AI provider executor |
| `JournalAnalysisCompleted` | Journal/AI | Care, Notification |
| `AssessmentSubmitted` | Care | Reporting, Notification |
| `SupportTierResolved` | Care | Notification, Reporting |
| `ConsentGranted/Revoked` | Care | Consultation cache invalidation, Audit |
| `SubscriptionStatusChanged` | Consultation/Billing | Care, Realtime, Notification, Reporting |
| `AppointmentStatusChanged` | Consultation | Realtime, Notification, Follow-up |
| `SpecialistEarningCreated` | Consultation/Billing | Specialist/Admin financial projections |
| `ChatMessageCreated` | Realtime | Notification, Audit projection |
| `NotificationCreated` | Content/Notification | Realtime WebSocket delivery |
| `AccountDeletionRequested` | Identity/Care | all data owners |
| `AuditEventRecorded` | all services | Governance archive/projection |

Commands/events include `messageId`, `messageType`, `occurredAt`, `producer`, `schemaVersion`, `correlationId`, aggregate identity/version, and the minimum data required. They must not contain raw journal/chat text or access tokens. WebSocket JSON messages have separate versioned client contracts and never reuse unrestricted Kafka payloads directly.

Kafka is the durable asynchronous backbone. PostgreSQL producers use a transactional outbox; MongoDB producers use an equivalent owned outbox/recoverable publisher design. Producers use stable aggregate keys and idempotent publishing. Consumers deduplicate by message ID, apply side effects before committing offsets, and use bounded retry/dead-letter topics. At-least-once business delivery is assumed. Kafka is not used for synchronous queries or request/reply.

## 5. Critical sequences

### Assessment, safety, and support

1. Client fetches versioned questionnaire.
2. Care Service validates complete responses and idempotency key.
3. In one transaction it stores submission, answers, computed score, safety flags, and outbox event.
4. A separate authenticated support-evaluation command explicitly names one compatible PHQ-9 and one compatible GAD-7 result; Care locks the profile, evaluates `mb-support-routing-capstone-v1`, and persists the immutable decision plus outbox event in one transaction.
5. Response keeps both bands and the PHQ-9 safety status independent, adds stable reasons and reviewed 14-day meanings, and returns the locally owned minimum safety guidance when required. It never calculates a composite score or invokes a downstream dependency.
6. Async consumers build projections, reminders, and non-critical notifications.

### Journal analysis

1. User saves a journal revision in MongoDB.
2. Node.js Journal/AI verifies AI-processing consent through Care REST and creates a PostgreSQL job/outbox record.
3. Journal/AI calls configured provider adapters, initially OpenAI and Gemini, through versioned input/output contracts. An optional future PhoBERT worker may execute a separately approved narrow classification contract after ADR 0011's activation gates pass.
4. Worker rejects malformed/unsafe output, records provider metadata/latency, and stores structured result.
5. Care consumes only approved structured indicators, never free-form model reasoning.
6. Retries use exponential backoff and a dead-letter state; the user can still read the journal.

### Consent-enforced specialist read

1. Specialist requests a user resource through the gateway.
2. Consultation/Care checks specialist approval and active grant for exact scope, subject, time range, and entry where applicable.
3. Data owner returns only allowed fields.
4. Audit record is written with purpose, grant ID, and result count.

### Subscription upgrade and consultation

1. A paid-plan IPN is parsed by the versioned MoMo-only contract, verified over every required signature field, deduplicated from its verified transaction tuple, and matched on configured partner plus local order/request/amount. Only then does it activate the immutable plan version and grant one credit row per included consultation.
2. A Care-to-Plus upgrade holds eligible unused credits, calculates a minor-unit offset from their allocation plus the second-accurate remaining non-consultation value, and starts a full Plus period only after a verified payment webhook. Downgrade is not supported.
3. A specialist publishes a channel-specific `[start_at, end_at)` slot. Booking locks that slot and one available credit in the same Consultation database transaction, then snapshots its start, end, timezone, and channel into the appointment.
4. Specialist confirmation authorizes one appointment-scoped Realtime conversation. `IN_APP_CHAT` join/send works only during the snapshotted window. `IN_APP_VIDEO` is planned but disabled until its call/signalling/provider/security contract is accepted; no physical location, phone number, or external meeting link is stored.
5. Rejection or eligible appointment cancellation releases the credit. Completion consumes it and atomically creates the specialist earning snapshot. Subscription cancellation instead disables paid features immediately, cancels future appointments and revokes their credits; only a confirmed session already in progress may finish at its scheduled end.
6. Available earnings may enter one idempotent MoMo Disbursement payout. Verified result/IPN/status evidence is required for success; local/CI uses a MoMo-shaped fake, and real payment/payout remains disabled until credentials and VND plan/settlement currency are approved.

## 6. Security and privacy

- OAuth-style access tokens are short-lived; refresh tokens are hashed, rotated, and revocable.
- Passwords use BCrypt with a reviewed cost factor. Never encrypt passwords.
- TLS is required externally and between production components where the network is not trusted.
- Encrypt sensitive data at rest using managed storage keys; field-level envelope encryption is recommended for raw journals and chat messages.
- Secrets come from environment/secret storage, never source control or images.
- Object storage uses private buckets and short-lived signed URLs.
- Logs exclude tokens, passwords, answer text, journal text, chat bodies, private object URLs, payment-provider payloads, and AI prompts containing user content.
- Rate-limit authentication, anonymous screening, AI analysis, booking, chat, and export endpoints.
- Authorization is deny-by-default and tested at controller/service/data-query boundaries.
- Audit records are append-only to application roles and include before/after state only when it does not expose prohibited content.

Threat-model at least broken object-level authorization, revoked-consent races, prompt injection from journal text, model data retention, mass assignment, slot double-booking, WebSocket subscription authorization, CSV/dataset injection, and deletion gaps.

## 7. Reliability and observability

- Timeouts, bounded retries, circuit breakers, and concurrency limits wrap all service-to-service REST and external AI/notification calls.
- Eureka discovery failure is a dependency failure, never permission to bypass the owner or query its database; safety-critical local Care paths remain independent of discovery.
- Health endpoints distinguish liveness from readiness. A failed optional AI provider must not make assessment APIs unready.
- Structured logs include `timestamp`, `level`, `service`, `traceId`, `correlationId`, `event`, and safe entity IDs.
- Metrics cover request latency/error rate, assessment submissions, safety-path success, Kafka consumer/outbox lag, AI failure/latency/cost, booking conflicts, Redis health, WebSocket connections/delivery delay, notification failures, and deletion completion.
- Traces cross gateway, services, and workers using W3C trace context.
- Backups and restore drills cover both PostgreSQL and MongoDB. A backup without a tested restore is not considered complete.

## 8. Deployment profile

### Local/demo

The current Review 1 Docker Compose topology starts the executable application services and local ephemeral Redis while connecting to the service-owned cloud PostgreSQL databases and MongoDB deployment shared by dev and staging. Compose does not own or initialize durable databases. CI and integration tests use disposable isolated stores, and all demo seed data remains synthetic and visibly labeled.

### AWS capstone deployment

Use one EC2 host initially with Docker Compose, Nginx, managed DNS/TLS, private outbound access to the shared pre-production data plane, and automated backups. Production receives separate database endpoints and credentials when provisioned. Split databases or workers only after measurements show a need. Keep AI provider keys server-side.

## 9. Decision records

Create an ADR in `docs/adr/` when changing service boundaries, storage ownership, safety/support-policy strategy, identity/token design, event broker, sensitive-data encryption, or AI provider/data-retention settings. ADRs contain context, decision, alternatives, consequences, and date/status.
