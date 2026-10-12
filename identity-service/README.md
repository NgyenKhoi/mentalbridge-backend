# Identity Service

Owns accounts, credentials, roles, sessions, account lifecycle, deletion coordination, and a minimized security/audit projection. It owns PostgreSQL identity data and may use Redis only for expiring hashed OTP challenges and bounded security state.

PostgreSQL persistence uses Hibernate and Spring Data JPA types inside the owning feature. Concrete persistence coordinators are used only where locking or multi-repository work is non-trivial. Liquibase remains the schema source of truth and Hibernate runs with schema validation only.

## Integration

- Inbound REST: [`identity-service-v1.yaml`](../contracts/openapi/identity-service-v1.yaml) defines public authentication and bounded account-administration APIs.
- Outbound REST: none in the initial architecture, so this service does not include OpenFeign.
- Async: account lifecycle events use Kafka with a transactional outbox; deletion and safe audit events retain the same approved boundary for their delivery slices.
- Discovery: registers as `identity-service` in Eureka. Eureka supplies location metadata only.

## Configuration

| Variable | Required | Purpose | Safe local example |
| --- | --- | --- | --- |
| `EUREKA_DEFAULT_ZONE` | Production | Eureka registry endpoint shared by Spring services | `http://localhost:8761/eureka/` |
| `EUREKA_CLIENT_ENABLED` | No | Enables Eureka registration; disable it when running Identity by itself locally | `false` |
| `KAFKA_BOOTSTRAP_SERVERS` | When relay enabled | Comma-separated Kafka brokers used only by the transactional outbox relay | `localhost:9092` |
| `IDENTITY_OUTBOX_RELAY_ENABLED` | No | Publishes due Identity outbox lifecycle events; keep disabled unless versioned topics exist | `false` |
| `IDENTITY_OUTBOX_RELAY_BATCH_SIZE` | No | Maximum rows claimed per relay run | `100` |
| `IDENTITY_OUTBOX_RELAY_INTERVAL` | No | Delay between bounded relay runs | `PT5S` |
| `IDENTITY_OUTBOX_RELAY_SEND_TIMEOUT` | No | Maximum Kafka acknowledgement wait per event | `PT5S` |
| `IDENTITY_OUTBOX_RELAY_RETRY_BASE` | No | Initial retry delay after a failed publish | `PT5S` |
| `IDENTITY_OUTBOX_RELAY_RETRY_MAXIMUM` | No | Maximum retry delay for a still-unpublished row | `PT5M` |
| `IDENTITY_DB_URL` | Yes | PostgreSQL JDBC URL; use `sslmode=require` for an RDS connection | `jdbc:postgresql://localhost:5432/mentalbridge_identity` |
| `IDENTITY_DB_USERNAME` | Yes | Identity-owned PostgreSQL login | `mentalbridge_identity` |
| `IDENTITY_DB_PASSWORD` | Yes | Identity PostgreSQL password injected outside source control | `replace-with-a-local-secret` |
| `IDENTITY_JWT_ISSUER` | Yes | Exact issuer accepted by Identity and resource services | `https://identity.local.mentalbridge` |
| `IDENTITY_JWT_AUDIENCE` | Yes | Intended MentalBridge API audience | `mentalbridge-api` |
| `IDENTITY_JWT_KEY_ID` | Yes | Identifier for the active asymmetric signing key | `local-development-key` |
| `IDENTITY_JWT_PRIVATE_KEY` | Yes | PKCS#8 RSA private signing key; Identity only | `replace-with-pkcs8-pem-private-key` |
| `IDENTITY_JWT_PUBLIC_KEY` | Yes | X.509 RSA public verification key | `replace-with-x509-pem-public-key` |
| `IDENTITY_ENCRYPTION_KEY_VERSION` | Yes | Non-secret identifier for the active idempotency-response key | `local-v1` |
| `IDENTITY_ENCRYPTION_KEY` | Yes | Base64-encoded 32-byte AES key for bounded idempotent responses | `replace-with-base64-encoded-32-byte-key` |
| `IDENTITY_BREVO_BASE_URL` | Yes | Brevo API base URL | `https://api.brevo.com` |
| `IDENTITY_BREVO_API_KEY` | Yes | Brevo API credential | `replace-with-a-development-brevo-key` |
| `IDENTITY_BREVO_SENDER_EMAIL` | Yes | Verified transactional sender | `no-reply@example.test` |
| `IDENTITY_BREVO_SENDER_NAME` | Yes | Transactional sender display name | `MentalBridge` |
| `IDENTITY_VERIFICATION_URL` | Yes | Frontend verification URL receiving the challenge query parameter | `http://localhost:3000/verify-email` |
| `IDENTITY_PASSWORD_RECOVERY_URL` | Yes | Frontend reset-password URL receiving the one-time recovery challenge | `http://localhost:3000/reset-password` |
| `IDENTITY_E2E_SEED` | No | Enables synthetic-account seeding only with the `e2e` Spring profile | `false` |
| `IDENTITY_E2E_USER_A_EMAIL` | When seeded | User A address; must end in `@synthetic.invalid` | `e2e-user-a@synthetic.invalid` |
| `IDENTITY_E2E_USER_B_EMAIL` | When seeded | User B address; must end in `@synthetic.invalid` | `e2e-user-b@synthetic.invalid` |
| `IDENTITY_E2E_PASSWORD` | When seeded | Shared local-only password for the two synthetic accounts | injected secret |
| `APPOINTMENT_REMINDER_SERVICE_TOKEN` | When reminder flow enabled | Shared credential for the narrow verified-delivery-address read | injected secret |

Production must override the local Eureka URL. The outbox relay is disabled by default and is enabled only when the version-controlled lifecycle topic has been provisioned. Redis variables will be documented when that runtime adapter is introduced.
Registration persists the account with exactly one immutable `USER` or `SPECIALIST` role, hashed challenge, idempotent outcome, and outbox event in one transaction. The configured delivery adapter runs only after that transaction commits and never logs the recipient or challenge. Automated tests keep delivery isolated and never use live Brevo credentials.

To prepare a new local checkout from the repository root, copy the committed template and generate development-only signing and encryption material:

```powershell
Copy-Item .env.example identity-service/.env
.\scripts\generate-local-jwt-keys.ps1
```

Replace the three corresponding placeholders in `identity-service/.env` with the entries written to `.local/identity-secrets/identity-secrets.env`, then set the Identity database URL, username, password, development Brevo key, and verified sender. Both files containing real secrets are ignored by Git and must not be committed.

For a controlled local cross-stack run, use the `e2e` Spring profile and explicitly
enable the seed. The runner creates only `USER` accounts under the reserved
`@synthetic.invalid` domain and hashes the supplied password with the production
BCrypt component; it is not active in the default profile:

```powershell
$env:IDENTITY_SPRING_PROFILES_ACTIVE='e2e'
$env:IDENTITY_E2E_SEED='true'
$env:IDENTITY_E2E_USER_A_EMAIL='e2e-user-a@synthetic.invalid'
$env:IDENTITY_E2E_USER_B_EMAIL='e2e-user-b@synthetic.invalid'
$env:IDENTITY_E2E_PASSWORD='<random-local-password>'
.\mvnw.cmd spring-boot:run
```

For the normal frontend-to-backend development flow, use Brevo credentials from the ignored `identity-service/.env` and run without a special Spring profile:

```powershell
cd identity-service
.\mvnw.cmd spring-boot:run
```

The Brevo adapter is the only runtime email delivery path. It sends verification and password-recovery URLs only for eligible accounts after the owning transaction commits. Provider failure is logged with safe identifiers only and does not change the endpoint's generic response. Automated tests replace the delivery port with a mock and never call Brevo. Do not commit or share the `.env`, and use a development provider key rather than a production credential.

The initial deployment provisions one dedicated `ADMIN` account through an operator-controlled bootstrap with externally supplied credentials. Public registration and account-administration APIs never create or promote an administrator. Liquibase enforces at most one `ADMIN` account but deliberately does not contain administrator credentials; deployment readiness must verify that secure provisioning has completed.

Create the service-owned `mentalbridge_identity` database before running this module. Liquibase connects directly to that database and creates extensions, tables, indexes, constraints, reference data, and its tracking tables in the default `public` schema. Application startup deliberately does not run migrations.

```powershell
$env:IDENTITY_DB_URL='jdbc:postgresql://<host>:5432/mentalbridge_identity?sslmode=require'
$env:IDENTITY_DB_USERNAME='<identity-username>'
$env:IDENTITY_DB_PASSWORD='<identity-password>'
./mvnw.cmd liquibase:validate
./mvnw.cmd liquibase:status
./mvnw.cmd liquibase:updateSQL
./mvnw.cmd liquibase:update
```

`updateSQL` is the review step and does not mutate the database. `update` applies pending owner changesets and records them in `mentalbridge_identity.public.databasechangelog`. Do not point `IDENTITY_DB_URL` at the administrative `postgres` database, and do not place real credentials in Maven arguments, repository files, or shell history.

## Run and test

```powershell
.\mvnw.cmd spring-boot:run
.\mvnw.cmd test
```

The generated context test uses PostgreSQL, Kafka, and Redis Testcontainers and disables live Eureka registration.

## Runtime contract status

### Recurring aggregate reports (MB-586)

Identity owns ADMIN-only `/api/v1/admin/platform-report-schedules` management
and the existing versioned ACCOUNT_ACTIVITY job engine. At most 50 nondeleted
schedules may be created. DAILY, WEEKLY (Monday), and MONTHLY (day 1) schedules
use an IANA timezone and minute-resolution wall time. Java timezone rules move
DST gap times forward and select the earlier offset during an overlap.

Each occurrence requests the preceding 1–366 completed UTC days, ending before
the occurrence's UTC calendar date. A schedule row lock, unique job occurrence
key, and one local transaction commit the queued job and next run together.
One due schedule is handled per existing reporting poll; overdue schedules run
once and advance to the next future occurrence, without replaying an unbounded
backlog. Update/resume also select the next future occurrence.

The approved recipient group is ADMIN and the only delivery target is
ADMIN_REPORT_HISTORY. Delivery means publishing the aggregate artifact into the
existing authenticated report history/download flow; no email, arbitrary URL,
health content, second report engine, broker, or notification adapter is added.
Current creator account access is checked before generation; revoked access
pauses the schedule with ADMIN_ACCESS_UNAVAILABLE. Generation failures retain
the existing immutable FAILED/STALE job evidence and idempotent retry API.
Soft deletion stops future occurrences while retaining prior jobs/artifacts.
Update, pause/resume and delete require the current `expectedVersion`; a stale
write returns HTTP 412. Existing report artifact retention remains 30 days.

Apply append-only migration `004-report-schedules.sql` before deploying. Old
applications tolerate the additive table and nullable occurrence columns. Stop
the schedule-aware runtime before rollback; do not drop audit-linked schedules.
No new configuration is required; existing platform-reporting polling/enable
settings control both scheduled enqueue and the shared processor.

The implemented internal endpoint
`GET /internal/v1/accounts/{accountId}/verified-email` returns an address only
for an active account with a verified email and requires the dedicated service
token. It is not a general account/profile read.

Only OpenAPI paths marked `x-mentalbridge-status: implemented` have runtime handlers. They currently cover registration, email verification/resend, login, refresh, logout/logout-all, password recovery/reset/change, and current-account retrieval. Contract and provider tests compare this exact set with the Spring request mappings so an unavailable operation cannot silently become a frontend-facing 404.

Account administration remains explicitly marked `planned`. Its forward schemas remain published for design coordination, but clients must not call it until a later Identity slice changes its status and supplies the matching implementation and tests.
