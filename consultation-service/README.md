# Consultation Service

Owns specialist approval and deterministic non-clinical discovery, 60-minute
in-app chat/video availability and appointments, session
evidence and user-visible summaries, subscription/payment/upgrade,
consultation credits, specialist earnings/provider payout reconciliation, and
reviews. Billing and booking invariants stay transactionally local in its
PostgreSQL data. It does not collect specialist verification documents.

Story 6101 implements the first profile vertical slice: a specialist can save the six
approved public fields, submit the profile, and an administrator can inspect
and approve it. The approval history is audited and no credential, license, or
verification-document claim is accepted. MB-360 completes rejection,
same-profile resubmission, suspension, and restoration with closed reasons and
audited transitions. Suspension atomically withdraws future availability,
cancels future not-started appointments, and releases their exact held credits;
restoration never revives those records. Discovery, billing, and cross-service
brief/chat integrations remain later stories. MB-362 adds approved-specialist publication, owner listing, and
tombstone withdrawal of exact online slots; it does not create appointments.
MB-377 adds Consultation-owned plan-period credit rows, an append-only
transition ledger, and an authenticated owner balance. Provisioning is
idempotent and keeps `DEMO` distinct from `PAID`; it does not infer payment.
MB-378 adds the first appointment request slice. A paid user selects one exact
online slot and Consultation atomically creates a `REQUESTED` snapshot while
holding one eligible credit. Slot/credit concurrency and command replay are
enforced locally; specialist decisions and scheduled expiry remain MB-379.
MB-558 adds `consultation-credit-v2` for new periods (`FREE=0`, `PLUS=4`,
`PREMIUM=10`), preserves existing v1 periods as `0/1/3`, and exposes/enforces
separate active-reservation limits `0/2/4`. Replacement requests retain the old
appointment snapshot while moving its credit and reservation atomically.
Appointment existence alone does not grant sensitive data access;
Care owns the user-approved appointment-scoped `ConsultationBrief` and sharing
decision.

## Integration

- Inbound REST: implemented specialist/admin APIs are defined in `../contracts/openapi/consultation-service-v1.yaml`.
- Outbound REST: calls Care for current consent and authorization decisions through a consumer-owned OpenFeign adapter and Resilience4j; uncertainty fails closed.
- Async: the implemented profile lifecycle has no independent asynchronous consumer, so it has no Kafka runtime,
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
| `CONSULTATION_VIDEO_AVAILABILITY_ENABLED` | No | Enables `IN_APP_VIDEO` slot publication only after the provider contract gate passes | `false` |

Production must override local URLs and secrets. MoMo IPN signing,
payout, encryption, and downstream timeout variables will be documented when
their typed configuration is introduced. No refund adapter is planned.

For container-based local/demo startup, copy `.env.example` to an untracked
`.env` and replace the database placeholder values. The root Compose profile
runs `consultation-migrate` first with Liquibase enabled, then starts the
`consultation` application container with runtime migrations disabled:

```powershell
docker compose --profile demo up consultation-migrate consultation
```

The committed Docker image contains the Maven-built service only. Database
provisioning remains an operator prerequisite; Compose never creates, resets,
or owns the shared dev/staging Consultation database.

## Implemented specialist lifecycle endpoints

- `GET|PUT /api/v1/specialist-profile`
- `POST /api/v1/specialist-profile/submit`
- `POST /api/v1/specialist-profile/resubmit`
- `GET /api/v1/admin/specialist-profiles?status=PENDING|APPROVED|REJECTED|SUSPENDED`
- `GET /api/v1/admin/specialist-profiles/{specialistAccountId}`
- `POST /api/v1/admin/specialist-profiles/{specialistAccountId}/approve`
- `POST /api/v1/admin/specialist-profiles/{specialistAccountId}/reject`
- `POST /api/v1/admin/specialist-profiles/{specialistAccountId}/suspend`
- `POST /api/v1/admin/specialist-profiles/{specialistAccountId}/restore`

## Implemented MB-362 endpoints

- `GET|POST /api/v1/availability-slots`
- `DELETE /api/v1/availability-slots/{slotId}`

## Implemented MB-377/MB-558 endpoint

- `GET /api/v1/service-credits`

## Implemented MB-378/MB-558 endpoints

- `GET /api/v1/bookable-slots`
- `GET|POST /api/v1/appointments`

Availability accepts only exact future 60-minute `IN_APP_CHAT` and gated
`IN_APP_VIDEO` slots. It stores UTC instants and an IANA display timezone,
rejects active overlap transactionally, and retains withdrawn slots as
tombstones. Practice locations, phone calls, external meeting links,
recurrence, time off, booking, and video-room authorization are not part of
this flow.

Updates and decisions use the returned `ETag` in `If-Match`. Editing a
submitted pending profile withdraws it from the review queue until the
specialist explicitly submits it again. Rejected profiles retain their reason
while being edited and require explicit resubmission. Approved and suspended
profiles are immutable. Rejection and suspension accept only their documented
stable reason sets. A suspension response reports the exact number of future
slots withdrawn, appointments cancelled, and credits released.

## Run and test

```powershell
.\mvnw.cmd spring-boot:run
.\mvnw.cmd test
```

The generated context test uses a PostgreSQL Testcontainer and disables live
Eureka registration. Kafka is intentionally absent from this synchronous slice.
