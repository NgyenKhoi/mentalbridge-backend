# Consultation Service

Owns specialist approval and deterministic non-clinical discovery, practice
locations, 60-minute chat/in-person availability and appointments, session
evidence and user-visible summaries, subscription/payment/upgrade,
consultation credits, specialist earnings/provider payout reconciliation, and
reviews. Billing and booking invariants stay transactionally local in its
PostgreSQL data. It does not collect specialist verification documents.

Story 6101 implements the first vertical slice: a specialist can save the six
approved public fields, submit the profile, and an administrator can inspect
and approve it. The approval history is audited and no credential, license, or
verification-document claim is accepted. Discovery, rejection/suspension,
appointments, billing, and cross-service brief/chat integrations remain later
stories. Appointment existence alone does not grant sensitive data access;
Care owns the user-approved appointment-scoped `ConsultationBrief` and sharing
decision.

## Integration

- Inbound REST: implemented specialist/admin APIs are defined in `../contracts/openapi/consultation-service-v1.yaml`.
- Outbound REST: calls Care for current consent and authorization decisions through a consumer-owned OpenFeign adapter and Resilience4j; uncertainty fails closed.
- Async: Story 6101 has no asynchronous workflow, so it has no Kafka runtime,
  topic, producer, consumer, outbox, or Kafka test container. A later feature
  may add these only when its accepted flow requires durable asynchronous work
  or an independent consumer, under ADR 0016.
- Discovery: registers as `consultation-service` and resolves Spring providers through Eureka. Registry data does not grant authorization.

## Configuration

| Variable | Required | Purpose | Safe local example |
| --- | --- | --- | --- |
| `EUREKA_DEFAULT_ZONE` | Production | Eureka registry endpoint shared by Spring services | `http://localhost:8761/eureka/` |
| `CONSULTATION_DB_URL` | Yes | JDBC URL for Consultation-owned PostgreSQL | `jdbc:postgresql://localhost:5432/mentalbridge_consultation` |
| `CONSULTATION_DB_USERNAME` | Yes | Consultation database role | `mentalbridge_consultation` |
| `CONSULTATION_DB_PASSWORD` | Yes | Consultation database credential | local secret |
| `CONSULTATION_LIQUIBASE_ENABLED` | Deployment | Enables schema migration for the migration job/owner | `true` |
| `IDENTITY_JWT_ISSUER` | Yes | Accepted Identity token issuer | `https://identity.local.mentalbridge` |
| `IDENTITY_JWT_AUDIENCE` | Yes | Required API audience | `mentalbridge-api` |
| `IDENTITY_JWT_PUBLIC_KEY` | Yes | X.509 PEM public key used to verify tokens | local public key |

Production must override local URLs and secrets. MoMo IPN signing,
payout, encryption, and downstream timeout variables will be documented when
their typed configuration is introduced. No refund adapter is planned.

## Implemented Story 6101 endpoints

- `GET|PUT /api/v1/specialist-profile`
- `POST /api/v1/specialist-profile/submit`
- `GET /api/v1/admin/specialist-profiles`
- `GET /api/v1/admin/specialist-profiles/{specialistAccountId}`
- `POST /api/v1/admin/specialist-profiles/{specialistAccountId}/approve`

Updates and decisions use the returned `ETag` in `If-Match`. Editing a
submitted pending profile withdraws it from the review queue until the
specialist explicitly submits it again. Approved profiles are immutable in
this slice; rejection, resubmission, suspension, and restoration belong to
Story 6102.

## Run and test

```powershell
.\mvnw.cmd spring-boot:run
.\mvnw.cmd test
```

The generated context test uses a PostgreSQL Testcontainer and disables live
Eureka registration. Kafka is intentionally absent from this synchronous slice.
