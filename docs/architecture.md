# Architecture

> Current product authority/navigation: [Current Product Blueprint](CURRENT_PRODUCT_BLUEPRINT.md).
> The architecture follows ADR 0017 as prospectively amended by ADR 0022 and
> distinguishes approved targets from implemented runtime.

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
| Care | user profile, consent, assessment, safety/support policy, SupportEvaluation, persisted Support Guide, SupportPlan proposal/lifecycle, reassessment and `PlanChangeRequest` decisions, follow-up | raw chat messages or resource definitions |
| Consultation/Billing | specialist profile/approval, plan versions, subscriptions, payments, consultation credits, slots, appointments, earnings, payout destinations/requests, reviews | account passwords, journals, chat messages |
| Journal/AI | journal revisions, analysis jobs/results, AI evaluation | authoritative assessment scoring |
| Realtime | conversations, messages, receipts, presence | consent source of truth |
| Content/Notification | reviewed self-help resource definitions and versioned eligibility metadata, notification preferences/delivery | screening, safety, SupportEvaluation, or final SupportPlan decisions |
| Governance/Reporting | audit events, moderation cases, de-identified projections | transactional sources of truth |

The core deployable business services are fixed as Spring Boot `identity-service`, `care-service`, and `consultation-service`, plus NestJS/TypeScript `journal-ai-service`, `realtime-service`, and `content-notification-service` using the ADR 0006 stack. ADR 0011 defers Python `phobert-worker` as an optional future benchmark baseline; it is not a current runtime or release dependency. Governance/reporting is implemented as bounded admin APIs and Kafka projections inside the relevant owner until a future ADR justifies another deployable. The edge gateway/reverse proxy and Eureka registry are infrastructure and contain no business orchestration.

ADR 0005 assigns the cohesive billing bounded context to `consultation-service` without adding another deployable. Its PostgreSQL database is authoritative for plan versions, paid subscriptions, MoMo payments/IPNs, upgrade offsets, consultation credits and ledger entries, specialist earnings, encrypted payout destinations, and MoMo payout reconciliation. Other services query narrow current entitlement or appointment-eligibility decisions and never maintain a shadow balance.

ADR 0012 bounds V1 screening and post-screening support to PHQ-9/`DEPRESSIVE_SYMPTOMS` and GAD-7/`ANXIETY_SYMPTOMS`. ADR 0013 freezes immutable SupportPlan template policy, compositional selection, slot bounds, eligibility roles, safety presentation, and lifecycle. Safety remains cross-cutting. Care owns domain-aware evaluation and final plan eligibility; Content/Notification owns exact reviewed resource definitions and eligibility provenance. Neither service reads the other's database, and AI owns neither decision.

ADR 0017, as prospectively amended by ADR 0022, defines scope v2 across these owners: canonical packages are `FREE`,
`PLUS`, and `PREMIUM`; every package receives a one-time Support Guide while
only paid packages receive the durable Care-owned SupportPlan; new
consultations use in-app chat/video only; and real payment/payout is VND through
MoMo after its price, allocation, and credential gates pass.

The Support Guide is a persisted immutable historical snapshot for one
screening context, not ephemeral data and not a lifecycle aggregate. New
`consultation-credit-v2` periods use `FREE=0`, `PLUS=4`, `PREMIUM=10`, with no
rollover and concurrent active-reservation caps `0/2/4`; historical credit-v1
periods remain immutable `0/1/3`.

## 3. Container view

```text
Mobile App / Admin Web
       | HTTPS REST/JSON + WSS
       v
 Edge reverse proxy
       |
       +--> Identity / Care / Consultation+Billing (Spring Boot) --> PostgreSQL
       +--> Journal-AI (Node.js) --> MongoDB
       +--> Realtime (Node.js) --> MongoDB + Redis --> WebSocket clients
       +--> Content-Notification (Node.js) --> PostgreSQL + Brevo/push providers
                              |
                         Kafka topics
                              |
              Optional future PhoBERT worker (Python)

 Spring services <---- registration and lookup only ----> Eureka registry
```

Do not share ORM entities, repositories, or direct cross-service table access. A single PostgreSQL server is acceptable locally and for the first deployment, but each service owns a separate database and database user as defined by ADR 0004. DTOs are JSON contracts defined through OpenAPI rather than shared Java/TypeScript implementation classes.

The [canonical domain model](domain-model/README.md) is the centralized
documentation view used for ERDs, class/domain diagrams, ownership, and
cross-service relationship understanding. It is read-only and never
provisions a database. Owner-specific PostgreSQL and MongoDB migration histories
remain the executable runtime sources of truth; cross-service identifiers in
the canonical model are logical references rather than physical foreign keys.

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

Kafka is the durable asynchronous backbone for features that actually require
durable later completion, independent consumers, fan-out, or replay. It is not
a mandatory dependency of every service or feature, and is never used for
synchronous queries or request/reply. A synchronous local CRUD or approval
slice stays on REST plus its owner database and does not pre-create topics,
outboxes, consumers, or broker tests. When Kafka is justified, PostgreSQL
producers use a transactional outbox; MongoDB producers use an equivalent
owned outbox/recoverable publisher design. Producers use stable aggregate keys
and idempotent publishing. Consumers deduplicate by message ID, apply side
effects before committing offsets, and use bounded retry/dead-letter topics.
At-least-once business delivery is assumed. See ADR 0016.

## 5. Critical sequences

### Assessment, safety, and support

1. Client fetches versioned questionnaire.
2. Care Service validates complete responses and idempotency key.
3. In one transaction it stores submission, answers, computed score, safety flags, and outbox event.
4. A separate authenticated support-evaluation command explicitly names one compatible PHQ-9 and one compatible GAD-7 result; Care locks the profile, evaluates `mb-support-routing-capstone-v1`, and persists the immutable decision plus outbox event in one transaction.
5. Response keeps both instrument/domain-specific bands and safety evidence
   independent. Positive PHQ-9 item 9 or the explicit “Tôi cần hỗ trợ ngay”
   action activates the safety flow; a `High`/`Severe` band alone does not. Any
   area directory uses user-entered/selected geography and verified,
   provenance-bearing entries; without coordinates and distance it never
   claims “nearest”. The flow never automatically calls, shares location,
   sends safety email, or notifies a third party.
6. The active v1 tier remains coarse historical routing and does not select a resource or SupportPlan. Additive v2 exposes exact domain-local contributions and independent item-9 safety for future plan composition (#48); it does not create or mutate a plan.
7. Care produces and persists a one-time approved Support Guide after screening
   for every package. One-time means one immutable result per screening context;
   it is not ephemeral, has no automatic time-based expiry, and is not a
   lifecycle aggregate.
8. For an entitled `PLUS`/`PREMIUM` user, Care obtains exact versioned
   eligibility from Content/Notification, creates a bounded system-proposed
   draft, and remains the sole owner of the official SupportPlan. Specialist
   resource input enters through `PlanChangeRequest`; Care revalidates and the
   user confirms. Safety guidance is presented first, and a new evaluation
   never silently changes the plan.
9. Content/Notification schedules at most one default wellbeing digest per
   user/day. Per-resource reminders require explicit opt-in; the separate
   appointment reminder is sent once approximately one hour before start. AI
   may phrase approved facts but cannot decide scheduling, and no safety email
   is generated automatically.

### Journal analysis

1. User saves a journal revision in MongoDB.
2. On an explicit request for one exact revision, Node.js Journal/AI forwards the verified end-user bearer context to Care REST, verifies current `AI_PROCESSING` consent, and creates one idempotent durable MongoDB job. The bearer credential is held only in memory and is never persisted or logged.
3. A local worker atomically claims the job with a bounded lease and re-checks Care consent with the same ephemeral bearer immediately before each provider attempt. It also forwards that bearer to Consultation's current-entitlement endpoint; no client tier claim or cross-service database read participates in routing. Absence of an effective stored entitlement is authoritative `FREE` with `DEFAULT_FREE` provenance.
4. The versioned `EXACT_REVISION` router selects one approved provider/model route from the current `FREE`/`PLUS`/`PREMIUM` decision. `FREE` and `PLUS` share the baseline route; `PREMIUM` may use a stronger approved route. The first attempt snapshots the route, every retry must keep it, and entitlement change or dependency uncertainty stops the attempt. There is no automatic cross-provider fallback. Each provider attempt times out after 30 seconds and has at most one retry for HTTP 429, provider 5xx, or transport failure.
5. The worker rejects malformed/unsafe output and stores only the normalized structured result plus entitlement, routing, provider/model/prompt/schema provenance, usage/cost estimate, timing, and job state. Raw provider responses and hidden reasoning are not persisted. Gemini/OpenAI stay config- and approval-gated; local/test/CI uses the deterministic fake.
6. Care consumes only approved structured indicators, never free-form model reasoning. Canonical v2 composition keeps standardized screening trend, non-standardized available-journal context trend with coverage, SupportPlan engagement, and explicit user-authored reassessment self-report as separate dimensions. Activity helpfulness/reflection is separately labelled supporting evidence and never replaces dimension four. Historical MB-386 v1 snapshots retain their original interpretation. Care never creates a combined improvement score.
7. Failure produces a terminal job state after the bounded retry; the user can still read the journal. Reproducible synthetic evidence for each configured Gemini/OpenAI candidate gates its approval identifier and official controlled-demo enablement; a run may evaluate one provider or compare both, and adapters/harnesses alone do not constitute approval.

Package routing applies outside the model's authority: `FREE` defaults to five
successfully delivered responses per day, `PLUS` has a higher versioned quota
and may share the same model, and `PREMIUM` may use a stronger model without a
displayed daily-response cap. All remain subject to server token, rate, abuse,
cost, and fair-use enforcement.

AI Companion conversations are Journal/AI-owned encrypted MongoDB aggregates,
not Realtime specialist-chat records. Each answer rechecks Care consent and
Consultation entitlement, reserves quota atomically, assembles only selected
owner-verified minimized context, and persists the answer/quota/idempotency
outcome in one Mongo transaction. The default 90-day conversation retention is
TTL-backed and owner deletion removes message and replay content immediately.
Reminder context remains fail-closed until Content/Notification publishes its
authorization contract. See ADR 0021.

### Consent-enforced specialist read

1. Specialist requests a user resource through the gateway.
2. Consultation/Care checks specialist approval and active grant for exact scope, subject, time range, and entry where applicable.
3. Data owner returns only allowed fields.
4. Audit record is written with purpose, grant ID, and result count.

### Subscription upgrade and consultation

1. A new `PLUS`/`PREMIUM` purchase or `PLUS`-to-`PREMIUM` upgrade creates a
   VND MoMo payment attempt. The versioned IPN is signature-verified,
   deduplicated, and matched before activating an immutable plan version and
   granting the exact versioned period allocation once. New
   `consultation-credit-v2` periods target four `PLUS` or ten `PREMIUM` credits
   with no rollover; historical `consultation-credit-v1` periods keep their
   original one/three allocation. No downgrade or user refund API exists.
2. An upgrade holds eligible unused credits and calculates its VND minor-unit
   offset from versioned facts before a verified webhook starts the new full
   `PREMIUM` period.
3. An approved specialist publishes a 60-minute `IN_APP_CHAT` or
   `IN_APP_VIDEO` slot. A booking transaction verifies package booking access,
   one available credit, reservation capacity below the target package cap, and
   a still-selectable slot before atomically holding the credit and slot and
   snapshotting start, end, timezone, and mode. Reschedule-as-new replaces one
   logical reservation and must not fail solely because the old appointment
   occupies the user's cap. New in-person, phone, and external-link appointments
   are rejected.
4. At scheduled end, Consultation records `SESSION_ENDED` and closes chat/video
   access. Time alone cannot complete the appointment. The versioned evidence
   policy evaluates server-observed chat or server/provider-observed video
   evidence.
5. Only accepted `COMPLETED` evidence consumes the credit and atomically creates
   an earning equal to 70% of the credit's fixed `creditAllocation`.
   `SESSION_ENDED`, cancellation, no-show, and dispute create no earning.
6. The user approves a `ConsultationBrief` before the session. The specialist
   creates `SessionSummary` and `AgreedNextSteps` only afterward; reuse requires
   explicit user approval. A resource suggestion becomes a Care-owned
   `PlanChangeRequest`, never another SupportPlan.
7. Available earnings may enter one idempotent MoMo Disbursement payout.
   Real-money payment and payout remain disabled until exact VND prices,
   `creditAllocation`, and credentials are approved and configured.

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
