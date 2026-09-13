# Consultation Service

Owns specialist approval and deterministic non-clinical discovery, practice
locations, 60-minute chat/in-person availability and appointments, session
evidence and user-visible summaries, subscription/payment/upgrade,
consultation credits, specialist earnings/provider payout reconciliation, and
reviews. Billing and booking invariants stay transactionally local in its
PostgreSQL data. It does not collect specialist verification documents.

ADR 0014 freezes the product flow but the compatible OpenAPI, migrations,
handlers, frontend, and cross-service brief/chat integrations are not yet
implemented. Appointment existence alone does not grant sensitive data access;
Care owns the user-approved appointment-scoped `ConsultationBrief` and sharing
decision.

## Integration

- Inbound REST: Consultation APIs will be defined in OpenAPI before implementation.
- Outbound REST: calls Care for current consent and authorization decisions through a consumer-owned OpenFeign adapter and Resilience4j; uncertainty fails closed.
- Async: subscription, appointment, earning, review, and moderation events will use Kafka with a transactional outbox.
- Discovery: registers as `consultation-service` and resolves Spring providers through Eureka. Registry data does not grant authorization.

## Configuration

| Variable | Required | Purpose | Safe local example |
| --- | --- | --- | --- |
| `EUREKA_DEFAULT_ZONE` | Production | Eureka registry endpoint shared by Spring services | `http://localhost:8761/eureka/` |

Production must override the local Eureka URL. Database, Kafka, MoMo IPN signing, payout, encryption, and downstream timeout variables will be documented when their typed configuration is introduced. No refund adapter is planned. Local/CI uses MoMo-shaped fakes; real MoMo payment/payout remains disabled until credentials and compatible VND pricing or an approved FX policy exist.

## Run and test

```powershell
.\mvnw.cmd spring-boot:run
.\mvnw.cmd test
```

The generated context test uses PostgreSQL and Kafka Testcontainers and disables live Eureka registration.
