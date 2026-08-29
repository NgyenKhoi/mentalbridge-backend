# Identity Service

Owns accounts, credentials, roles, sessions, account lifecycle, deletion coordination, and a minimized security/audit projection. It owns PostgreSQL identity data and may use Redis only for expiring hashed OTP challenges and bounded security state.

PostgreSQL persistence uses Hibernate and Spring Data JPA types inside the owning feature. Concrete persistence coordinators are used only where locking or multi-repository work is non-trivial. Liquibase remains the schema source of truth and Hibernate runs with schema validation only.

## Integration

- Inbound REST: [`identity-service-v1.yaml`](../contracts/openapi/identity-service-v1.yaml) defines public authentication and bounded account-administration APIs.
- Outbound REST: none in the initial architecture, so this service does not include OpenFeign.
- Async: account lifecycle, deletion, and safe audit events will use Kafka with a transactional outbox.
- Discovery: registers as `identity-service` in Eureka. Eureka supplies location metadata only.

## Configuration

| Variable | Required | Purpose | Safe local example |
| --- | --- | --- | --- |
| `EUREKA_DEFAULT_ZONE` | Production | Eureka registry endpoint shared by Spring services | `http://localhost:8761/eureka/` |
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
| `IDENTITY_VERIFICATION_DELIVERY_MODE` | No | Selects `disabled`, `brevo`, or the development-only `local-file` adapter | `disabled` |
| `IDENTITY_BREVO_BASE_URL` | When delivery is enabled | Brevo API base URL | `https://api.brevo.com` |
| `IDENTITY_BREVO_API_KEY` | When delivery is enabled | Brevo API credential | `replace-only-when-delivery-is-enabled` |
| `IDENTITY_BREVO_SENDER_EMAIL` | When delivery is enabled | Verified transactional sender | `no-reply@example.test` |
| `IDENTITY_BREVO_SENDER_NAME` | When delivery is enabled | Transactional sender display name | `MentalBridge` |
| `IDENTITY_VERIFICATION_URL` | When delivery is enabled | Frontend verification URL receiving the challenge query parameter | `http://localhost:3000/verify-email` |
| `IDENTITY_LOCAL_VERIFICATION_DIRECTORY` | In `local-file` mode | Ignored local directory receiving one verification URL file per synthetic account | `.local/identity-verification` |

Production must override the local Eureka URL. Kafka and Redis variables will be documented when those runtime adapters are introduced.
Registration persists the account with exactly one immutable `USER` or `SPECIALIST` role, hashed challenge, idempotent outcome, and outbox event in one transaction. The configured delivery adapter runs only after that transaction commits and never logs the recipient or challenge. Delivery remains disabled by default and in ordinary tests.

For local frontend integration with synthetic accounts, enable the `local` profile and the local file adapter:

```powershell
$env:SPRING_PROFILES_ACTIVE='local'
$env:IDENTITY_VERIFICATION_DELIVERY_MODE='local-file'
$env:IDENTITY_LOCAL_VERIFICATION_DIRECTORY='.local/identity-verification'
.\mvnw.cmd spring-boot:run
```

Each accepted registration writes only its frontend verification URL to `.local/identity-verification/<accountId>.verification-url`. The ignored directory may contain an active challenge, so use synthetic addresses, do not publish its files, and delete them after testing. The adapter requires an exclusive `local` or `dev` profile; startup rejects it with no profile, with `prod`, or with `prod` combined with a development profile. Use `brevo` mode with externally supplied credentials for an approved provider environment.

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

The MB-87 implementation covers registration and verification, generic credential failures and lockout, RS256 access JWTs, refresh rotation with exact idempotent replay, refresh-reuse family revocation, logout/logout-all, and bearer authorization for the current-account endpoint. Password recovery and account administration remain later Identity slices even though their forward contract is already published.
