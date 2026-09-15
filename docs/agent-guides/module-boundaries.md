# Module Boundaries

## Ownership map

| Module | Recommended runtime | Owns | May query synchronously | Publishes/consumes asynchronously |
| --- | --- | --- | --- | --- |
| `identity-service` | Spring Boot | account, credentials, roles, sessions, deletion coordination, minimized audit/security projection | no profile health data | account lifecycle, deletion and safe audit events |
| `care-service` | Spring Boot | profile, consent, two-domain assessment, both safety triggers, Support Guide, SupportEvaluation, the one official SupportPlan and `PlanChangeRequest` decisions, follow-up | Identity for exceptional current account facts; Content for exact eligible resource/directory versions | assessment, support, consent and follow-up events |
| `consultation-service` | Spring Boot | specialist approval/discovery/matching, `FREE`/`PLUS`/`PREMIUM` plan versions, VND/MoMo payment/upgrade, consultation credits, chat/video slots, appointments/evidence, summaries/next steps, earnings, provider payouts, reviews | Care authorization/consent and SupportPlan-change decisions when current truth is required | subscription, appointment, earning, payout, review and moderation events |
| `journal-ai-service` | Node.js/TypeScript | journal metadata/content access, analysis jobs/results | Care for current AI-processing consent | analysis commands/results |
| `realtime-service` | Node.js/TypeScript | conversations, messages, WebSocket sessions, presence, receipts | Consultation/Care for current authorization when connecting or sending | chat facts and notification delivery events |
| `content-notification-service` | Node.js/TypeScript | reviewed self-help resources and eligibility, verified area-directory content, preferences, digest/resource/appointment scheduling, notification creation/provider delivery | provider APIs only when executing delivery | consumes domain events and emits notification/delivery outcomes |
| `phobert-worker` (optional/deferred) | Python | inference execution only after its activation gate passes | no business data query | future versioned inference command/result only |

The edge gateway/reverse proxy and Eureka service registry are infrastructure, not business modules, and contain no orchestration or domain logic. Eureka publishes service location metadata only. Language does not change ownership. Node.js and Spring communicate through REST/JSON DTOs and Kafka contracts and never share framework models.

ADR 0005 assigns subscription/payment, upgrade, consultation-credit, earning, and provider-payout behavior to one billing feature inside `consultation-service`. Booking and credit transitions share its local PostgreSQL transaction. No consumer may derive or store a mutable entitlement, credit, earning, or payout balance independently.

ADR 0012 assigns final domain-aware SupportEvaluation and SupportPlan decisions to Care while Content/Notification owns resource definitions and eligibility provenance. ADR 0013 freezes immutable Care templates, deterministic composition, bounded core/optional slots, exact `PRIMARY`/`ADJUNCT` roles, and one-draft/one-current-plan lifecycle invariants. A published resource is not automatically plan-eligible, a coarse support tier cannot select a plan alone, and neither AI nor a client may author the initial proposal.

ADR 0017 further separates the all-tier one-time Support Guide from the
`PLUS`/`PREMIUM` durable SupportPlan. Care owns the only official current plan;
a specialist submits `PlanChangeRequest` input and never creates a second plan.
Content/Notification's scheduler decides reminders, while AI may phrase only
approved content. Consultation owns 60-minute chat/video appointment evidence;
`SESSION_ENDED` is not completion or an earning trigger.

## Synchronous versus asynchronous

Use REST only when the caller cannot produce a correct response without current data, for example an exact consent authorization or booking command. Keep call depth shallow: edge to owner, with at most one necessary owner-to-owner call on a normal request path.

Use messages for work that may complete later: AI analysis, notification delivery, audit projection, reporting, cache/projection updates, and deletion fan-out. An event is a fact that already happened; a command asks one logical owner to perform work.

This is a selection rule, not a requirement that every feature publish a
message. Default to a local transaction and REST. Add Kafka only when the
accepted feature has durable work that completes later, an independent
consumer, required fan-out, or a replayable projection. Do not add broker
dependencies, an outbox, topics, consumers, or Kafka test containers to a
synchronous CRUD slice in anticipation of possible future use.

WebSocket is an edge delivery channel owned only by `realtime-service`. Other services never open service-to-service WebSocket connections. `content-notification-service` publishes a durable notification event to Kafka; `realtime-service` consumes it and pushes the safe client payload to an existing WebSocket session. Redis coordinates ephemeral sessions and fan-out across realtime replicas.

Never use an asynchronously replicated projection for a safety- or authorization-critical decision unless the domain explicitly accepts staleness. Never turn a dashboard or response into distributed joins across services; create a read projection or bounded composition endpoint.

## Module completion checklist

Every deployable contains a README describing owner, data, inbound REST endpoints, outbound REST dependencies, Kafka topics/messages and WebSocket namespace/events only where applicable, failure behavior, configuration, local run command, and test commands. It also contains health/readiness checks, OpenAPI, migrations where applicable, structured logs, focused metrics, a Dockerfile, and automated tests. OpenTelemetry is added only for a demonstrated distributed-trace use case under ADR 0016.
