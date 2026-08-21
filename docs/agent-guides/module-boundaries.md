# Module Boundaries

## Ownership map

| Module | Recommended runtime | Owns | May query synchronously | Publishes/consumes asynchronously |
| --- | --- | --- | --- | --- |
| `identity-service` | Spring Boot | account, credentials, roles, sessions, deletion coordination, minimized audit/security projection | no profile health data | account lifecycle, deletion and safe audit events |
| `care-service` | Spring Boot | profile, consent, assessment, risk, intervention, follow-up | Identity for exceptional current account facts | assessment, risk, consent and follow-up events |
| `consultation-service` | Spring Boot | specialist approval/discovery/matching, subscription/payment/upgrade, consultation credits, slots, appointments, earnings, provider payouts, reviews | Care authorization/consent checks when current truth is required | subscription, appointment, earning, payout, review and moderation events |
| `journal-ai-service` | Node.js/TypeScript | journal metadata/content access, analysis jobs/results | Care for current AI-processing consent | analysis commands/results |
| `realtime-service` | Node.js/TypeScript | conversations, messages, WebSocket sessions, presence, receipts | Consultation/Care for current authorization when connecting or sending | chat facts and notification delivery events |
| `content-notification-service` | Node.js/TypeScript | resources, hotlines, preferences, notification creation/provider delivery | provider APIs only when executing delivery | consumes domain events and emits notification/delivery outcomes |
| `phobert-worker` | Python | inference execution only | no business data query | consumes analysis commands and emits results |

The edge gateway/reverse proxy and Eureka service registry are infrastructure, not business modules, and contain no orchestration or domain logic. Eureka publishes service location metadata only. Language does not change ownership. Node.js and Spring communicate through REST/JSON DTOs and Kafka contracts and never share framework models.

ADR 0005 assigns subscription/payment, upgrade, consultation-credit, earning, and provider-payout behavior to one billing feature inside `consultation-service`. Booking and credit transitions share its local PostgreSQL transaction. No consumer may derive or store a mutable entitlement, credit, earning, or payout balance independently.

## Synchronous versus asynchronous

Use REST only when the caller cannot produce a correct response without current data, for example an exact consent authorization or booking command. Keep call depth shallow: edge to owner, with at most one necessary owner-to-owner call on a normal request path.

Use messages for work that may complete later: AI analysis, notification delivery, audit projection, reporting, cache/projection updates, and deletion fan-out. An event is a fact that already happened; a command asks one logical owner to perform work.

WebSocket is an edge delivery channel owned only by `realtime-service`. Other services never open service-to-service WebSocket connections. `content-notification-service` publishes a durable notification event to Kafka; `realtime-service` consumes it and pushes the safe client payload to an existing WebSocket session. Redis coordinates ephemeral sessions and fan-out across realtime replicas.

Never use an asynchronously replicated projection for a safety- or authorization-critical decision unless the domain explicitly accepts staleness. Never turn a dashboard or response into distributed joins across services; create a read projection or bounded composition endpoint.

## Module completion checklist

Every deployable contains a README describing owner, data, inbound REST endpoints, outbound REST dependencies, Kafka topics/messages, WebSocket namespace/events where applicable, failure behavior, configuration, local run command, and test commands. It also contains health/readiness checks, OpenAPI, migrations where applicable, structured logs, metrics, a Dockerfile, and automated tests.
