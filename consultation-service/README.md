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
restoration never revives those records. MB-363 adds approved-only discovery,
public profile detail, deterministic explanations, and current exact-slot
handoff. Billing purchase and cross-service brief/chat integrations remain
later stories. MB-362 adds approved-specialist publication, owner listing, and
tombstone withdrawal of exact online slots; it does not create appointments.
MB-377 adds Consultation-owned plan-period credit rows, an append-only
transition ledger, and an authenticated owner balance. Provisioning is
idempotent and keeps `DEMO` distinct from `PAID`; it does not infer payment.
MB-378 adds the appointment request slice. A paid user selects one exact
online slot and Consultation atomically creates a `REQUESTED` snapshot while
holding one eligible credit. Slot/credit concurrency and command replay are
enforced locally. MB-379 lets only the assigned specialist accept or reject an
eligible request with optimistic concurrency and idempotent history. Acceptance
keeps the credit held; rejection and server-scheduled deadline expiry release
the exact credit once in the same local transaction.
MB-558 adds `consultation-credit-v2` for new periods (`FREE=0`, `PLUS=4`,
`PREMIUM=10`), preserves existing v1 periods as `0/1/3`, and exposes/enforces
separate active-reservation limits `0/2/4`. Replacement requests retain the old
appointment snapshot while moving its credit and reservation atomically.
Appointment existence alone does not grant sensitive data access;
Care owns the user-approved appointment-scoped `ConsultationBrief` and sharing
decision. MB-592 exposes a separate internal, audited continuity projection for
the authenticated currently approved specialist. It contains only assigned
`CONFIRMED`/in-flight appointments and appointments ending within the last 90
days, is capped at 200 rows, and never acts as a user directory or carries Care
content.
MB-380 adds optimistic, idempotent owner cancellation and completes
reschedule-as-new audit. Requested cancellations and confirmations at least 24
hours before start release the held credit; later confirmed cancellations
forfeit it. Every cancellation records actor, stable reason, instant, exact
credit outcome, and append-only history. Replacement creation and old
appointment settlement remain one transaction.
MB-364 lets the appointment owner create and later edit one 1-5 rating only
after evidence-backed completion. The current rating and specialist count/sum
aggregate commit together; public discovery discloses average/count and uses
the aggregate only as the final `PREMIUM` tie-breaker.
MB-588 adds an ADMIN-only, read-only appointment operations projection with
bounded time/status/modality/account filters and cursor pagination. Consultation
remains the lifecycle authority; the projection carries operational timestamps,
reason codes, and credit settlement state but no brief, summary, journal,
assessment, chat, or private-note content. It grants no appointment mutation or
clinical authority.

## Integration

- Inbound REST: implemented specialist/admin APIs are defined in `../contracts/openapi/consultation-service-v1.yaml`.
- Outbound REST: discovery optionally calls Care for one exact owned
  SupportEvaluation through a narrow OpenFeign adapter. It reduces the response
  to domain/pathway priorities in memory; unavailable or malformed context
  produces explained neutral ranking and never exposes raw assessment data.
  Other consent/authorization integrations remain separately gated.
- Async: appointment lifecycle changes are written to the Consultation-owned
  transactional outbox and relayed to
  `mentalbridge.consultation.appointment-status.v1`. The minimized event carries
  appointment/version, owner, status, start, and modality only; it contains no
  health, Journal, assessment, brief, or chat content.
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
| `APPOINTMENT_REMINDER_SERVICE_TOKEN` | When reminder flow enabled | Shared service credential for the narrow eligibility read | injected secret |
| `KAFKA_BOOTSTRAP_SERVERS` | When relay enabled | Broker list for appointment status events | `localhost:9092` |
| `CONSULTATION_APPOINTMENT_OUTBOX_RELAY_ENABLED` | No | Enables bounded appointment outbox relay | `false` |

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

## Implemented MB-363 owner endpoints

- `GET /api/v1/specialists`
- `GET /api/v1/specialists/{specialistAccountId}`

Discovery uses the existing profile, language/support-area, availability,
appointment-hold, and current-entitlement tables in one owner database; MB-363
requires no new persistence or migration. Every request rechecks current
`APPROVED` state and selectable slots. `FREE` receives `BROWSE_ONLY`, while
`PLUS`/`PREMIUM` receive an exact slot identity with
`BOOKING_POLICY_CHECK_REQUIRED`; MB-378 remains the booking authority and
revalidates all mutable state. Discovery policy v2 displays only authoritative
MB-364 rating aggregates and never fabricates a score.

## Implemented MB-377/MB-558 endpoint

- `GET /api/v1/service-credits`

## Implemented MB-378/MB-558 endpoints

- `GET /api/v1/bookable-slots`
- `GET|POST /api/v1/appointments`

## Implemented MB-380 endpoint

- `POST /api/v1/appointments/{appointmentId}/cancel`

## Implemented MB-364 endpoint

- `GET|PUT /api/v1/appointments/{appointmentId}/rating`

## Implemented MB-548 internal endpoint

- `GET /internal/v1/appointments/{appointmentId}/notification-eligibility?appointmentVersion={version}`

## Implemented MB-379 endpoints

- `GET /api/v1/specialist/appointments`
- `POST /api/v1/specialist/appointments/{appointmentId}/accept`
- `POST /api/v1/specialist/appointments/{appointmentId}/reject`
- `GET /internal/v1/appointments/{conversationId}/chat-eligibility` for participant-bound Realtime subscribe/send/history authorization

## Implemented MB-591 endpoint

- `GET /api/v1/specialist/dashboard`

The dashboard is a bounded, read-only projection of Consultation-owned
specialist profile, appointment, and availability facts. Counts retain exact
totals while item previews are capped at five. Every section and item includes
its source and server as-of time. Missing, pending, rejected, or suspended
profiles fail closed with blocked workload sections. The projection contains
no client identity, check-in, clinical risk, recovery/adherence, journal,
assessment-answer, chat-content, or private-note fields, and it performs no
cross-service database query.

## Implemented MB-588 endpoint

- `GET /api/v1/admin/appointments`

The query requires an explicit range of at most 180 days, caps pages at 100,
and may filter by appointment status, modality, user account, or specialist
account. Responses identify Consultation as the authoritative source and mark
the data state explicitly. The operation is available only to `ADMIN` and is
not a substitute for any owner or specialist command endpoint.

## Implemented MB-592 endpoint

- `GET /internal/v1/specialist/client-relationships`

The endpoint returns appointment/user identifiers, status, modality, exact
schedule, appointment version, projection time/window, and policy version only.
Suspended, rejected, pending, or missing specialist profiles fail closed and
every list attempt records a content-free audit fact.

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

The generated context test uses PostgreSQL and Kafka Testcontainers and disables
live Eureka registration. The appointment relay remains disabled unless its
versioned topic has been provisioned.
