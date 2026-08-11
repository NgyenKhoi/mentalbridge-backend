# Architecture

## 1. Architectural drivers

1. Mental-health and journal data require least privilege, consent enforcement, traceability, deletion, and minimized exposure.
2. Assessment scoring and severe-risk guidance must remain available when AI or messaging dependencies fail.
3. AI calls and benchmark jobs are slow, costly, and failure-prone, so they are asynchronous and observable.
4. Appointment booking requires strong transactional consistency; chat and analytics favor scalable document/event storage.
5. A five-person capstone team must be able to run, test, and demonstrate the entire system locally.

## 2. Bounded contexts

| Context | Owns | Does not own |
| --- | --- | --- |
| Identity | credentials, account state, roles, sessions | profile health data |
| Care | user profile, consent, assessment, risk, intervention, follow-up | raw chat messages |
| Consultation | specialist profile/status, slots, appointments, reviews | account passwords, journals |
| Journal/AI | journal revisions, analysis jobs/results, AI evaluation | authoritative assessment scoring |
| Realtime | conversations, messages, receipts, presence | consent source of truth |
| Content/Notification | resources, hotlines, notification preferences/delivery | risk decisions |
| Governance/Reporting | audit events, moderation cases, de-identified projections | transactional sources of truth |

The deployable business services are fixed as Spring Boot `identity-service`, `care-service`, and `consultation-service`; NestJS `journal-ai-service`, `realtime-service`, and `content-notification-service`; and Python `phobert-worker`. Governance/reporting is implemented as bounded admin APIs and Kafka projections inside the relevant owner until a future ADR justifies another deployable. The edge gateway/reverse proxy is infrastructure and contains no business orchestration.

## 3. Container view

```text
Mobile App / Admin Web
       | HTTPS REST/JSON + WSS
       v
 Edge reverse proxy
       |
       +--> Identity / Care / Consultation (Spring Boot) --> PostgreSQL
       +--> Journal-AI (NestJS) --> MongoDB + PostgreSQL job metadata
       +--> Realtime (NestJS) --> MongoDB + Redis --> WebSocket clients
       +--> Content-Notification (NestJS) --> PostgreSQL + push/email providers
                              |
                         Kafka topics
                              |
                    PhoBERT worker (Python)
```

Do not share ORM entities, repositories, or direct cross-service table access. A single PostgreSQL cluster is acceptable locally and for the first deployment, but each service owns a schema and database user. DTOs are JSON contracts defined through OpenAPI rather than shared Java/TypeScript implementation classes.

## 4. Communication patterns

### Synchronous REST

Use REST/JSON for authentication, CRUD, service-to-service queries, assessment submission/scoring, current consent authorization, slot booking, history recovery, and immediate crisis-resource retrieval. Every mutating endpoint accepts or generates a correlation ID; commands vulnerable to retries accept an `Idempotency-Key`. REST clients use deadlines, bounded safe retries, circuit breakers, and domain-safe fallbacks.

### Client WebSocket

Only Realtime Service accepts WebSocket connections. It owns chat delivery, presence, receipts, and delivery of safe in-app notification payloads. Other services communicate with Realtime through REST or Kafka, never service-to-service WebSocket. Redis stores TTL presence and coordinates low-latency cross-instance socket fan-out; MongoDB remains authoritative for durable conversations/messages.

### Kafka commands and events

Initial event catalogue:

| Event | Producer | Consumers |
| --- | --- | --- |
| `AnalyzeJournalRevision` | Journal/AI | PhoBERT worker or Journal/AI provider executor |
| `JournalAnalysisCompleted` | Journal/AI or PhoBERT worker | Care, Notification |
| `AssessmentSubmitted` | Care | Reporting, Notification |
| `RiskClassified` | Care | Intervention, Notification, Reporting |
| `ConsentGranted/Revoked` | Care | Consultation cache invalidation, Audit |
| `AppointmentStatusChanged` | Consultation | Realtime, Notification, Follow-up |
| `ChatMessageCreated` | Realtime | Notification, Audit projection |
| `NotificationCreated` | Content/Notification | Realtime WebSocket delivery |
| `AccountDeletionRequested` | Identity/Care | all data owners |
| `AuditEventRecorded` | all services | Governance archive/projection |

Commands/events include `messageId`, `messageType`, `occurredAt`, `producer`, `schemaVersion`, `correlationId`, aggregate identity/version, and the minimum data required. They must not contain raw journal/chat text or access tokens. WebSocket JSON messages have separate versioned client contracts and never reuse unrestricted Kafka payloads directly.

Kafka is the durable asynchronous backbone. PostgreSQL producers use a transactional outbox; MongoDB producers use an equivalent owned outbox/recoverable publisher design. Producers use stable aggregate keys and idempotent publishing. Consumers deduplicate by message ID, apply side effects before committing offsets, and use bounded retry/dead-letter topics. At-least-once business delivery is assumed. Kafka is not used for synchronous queries or request/reply.

## 5. Critical sequences

### Assessment and risk

1. Client fetches versioned questionnaire.
2. Care Service validates complete responses and idempotency key.
3. In one transaction it stores submission, answers, computed score, safety flags, and outbox event.
4. It evaluates the deterministic policy synchronously when safety-relevant input is present.
5. Response includes score/band, risk result if available, disclaimer, and crisis guidance where required.
6. Async consumers build projections, reminders, and non-critical notifications.

### Journal analysis

1. User saves a journal revision in MongoDB.
2. NestJS Journal/AI verifies AI-processing consent through Care REST and creates a PostgreSQL job/outbox record.
3. Journal/AI calls configured LLM providers; the Python PhoBERT worker consumes only PhoBERT analysis commands from Kafka. Both use a versioned prompt/model contract and JSON schema.
4. Worker rejects malformed/unsafe output, records provider metadata/latency, and stores structured result.
5. Care consumes only approved structured indicators, never free-form model reasoning.
6. Retries use exponential backoff and a dead-letter state; the user can still read the journal.

### Consent-enforced specialist read

1. Specialist requests a user resource through the gateway.
2. Consultation/Care checks specialist approval and active grant for exact scope, subject, time range, and entry where applicable.
3. Data owner returns only allowed fields.
4. Audit record is written with purpose, grant ID, and result count.

## 6. Security and privacy

- OAuth-style access tokens are short-lived; refresh tokens are hashed, rotated, and revocable.
- Passwords use Argon2id or BCrypt with reviewed parameters. Never encrypt passwords.
- TLS is required externally and between production components where the network is not trusted.
- Encrypt sensitive data at rest using managed storage keys; field-level envelope encryption is recommended for raw journals and verification documents.
- Secrets come from environment/secret storage, never source control or images.
- Object storage uses private buckets and short-lived signed URLs.
- Logs exclude tokens, passwords, answer text, journal text, chat bodies, document URLs, and AI prompts containing user content.
- Rate-limit authentication, anonymous screening, AI analysis, booking, chat, and export endpoints.
- Authorization is deny-by-default and tested at controller/service/data-query boundaries.
- Audit records are append-only to application roles and include before/after state only when it does not expose prohibited content.

Threat-model at least broken object-level authorization, revoked-consent races, prompt injection from journal text, model data retention, mass assignment, slot double-booking, WebSocket subscription authorization, CSV/dataset injection, and deletion gaps.

## 7. Reliability and observability

- Timeouts, bounded retries, circuit breakers, and concurrency limits wrap all service-to-service REST and external AI/notification calls.
- Health endpoints distinguish liveness from readiness. A failed optional AI provider must not make assessment APIs unready.
- Structured logs include `timestamp`, `level`, `service`, `traceId`, `correlationId`, `event`, and safe entity IDs.
- Metrics cover request latency/error rate, assessment submissions, safety-path success, Kafka consumer/outbox lag, AI failure/latency/cost, booking conflicts, Redis health, WebSocket connections/delivery delay, notification failures, and deletion completion.
- Traces cross gateway, services, and workers using W3C trace context.
- Backups and restore drills cover both PostgreSQL and MongoDB. A backup without a tested restore is not considered complete.

## 8. Deployment profile

### Local/demo

Docker Compose with the six business services, Python worker, edge proxy, PostgreSQL, MongoDB, Kafka, and Redis. One command should start infrastructure; seed data must be synthetic.

### AWS capstone deployment

Use one EC2 host initially with Docker Compose, Nginx, managed DNS/TLS, private database ports, encrypted volumes, and automated backups. Split databases or workers only after measurements show a need. Keep AI provider keys server-side.

## 9. Decision records

Create an ADR in `docs/adr/` when changing service boundaries, storage ownership, risk-policy strategy, identity/token design, event broker, sensitive-data encryption, or AI provider/data-retention settings. ADRs contain context, decision, alternatives, consequences, and date/status.
