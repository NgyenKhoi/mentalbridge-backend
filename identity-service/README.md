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
| `EUREKA_CLIENT_ENABLED` | No | Enables Eureka registration; disable it when running Identity by itself locally | `false` |
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
| `IDENTITY_VERIFICATION_DELIVERY_MODE` | No | Selects `disabled`, `brevo`, or the development-only `local-file` fallback; use `brevo` for the normal development E2E flow | `brevo` |
| `IDENTITY_BREVO_BASE_URL` | When delivery is enabled | Brevo API base URL | `https://api.brevo.com` |
| `IDENTITY_BREVO_API_KEY` | When delivery is enabled | Brevo API credential | `replace-only-when-delivery-is-enabled` |
| `IDENTITY_BREVO_SENDER_EMAIL` | When delivery is enabled | Verified transactional sender | `no-reply@example.test` |
| `IDENTITY_BREVO_SENDER_NAME` | When delivery is enabled | Transactional sender display name | `MentalBridge` |
| `IDENTITY_VERIFICATION_URL` | When delivery is enabled | Frontend verification URL receiving the challenge query parameter | `http://localhost:3000/verify-email` |
| `IDENTITY_LOCAL_VERIFICATION_DIRECTORY` | In `local-file` mode | Ignored local directory receiving one verification URL file per synthetic account | `.local/identity-verification` |

Production must override the local Eureka URL. Kafka and Redis variables will be documented when those runtime adapters are introduced.
Registration persists the account with exactly one immutable `USER` or `SPECIALIST` role, hashed challenge, idempotent outcome, and outbox event in one transaction. The configured delivery adapter runs only after that transaction commits and never logs the recipient or challenge. Automated tests keep delivery isolated and never use live Brevo credentials.

To prepare a new local checkout from the repository root, copy the committed template and generate development-only signing and encryption material:

```powershell
Copy-Item .env.example identity-service/.env
.\scripts\generate-local-jwt-keys.ps1
```

Replace the three corresponding placeholders in `identity-service/.env` with the entries written to `.local/identity-secrets/identity-secrets.env`, then set the Identity database URL, username, password, development Brevo key, and verified sender. Both files containing real secrets are ignored by Git and must not be committed.

For the normal frontend-to-backend development flow, use Brevo credentials from the ignored `identity-service/.env` and run without a special Spring profile:

```powershell
cd identity-service
.\mvnw.cmd spring-boot:run
```

The Brevo adapter sends the verification URL to the submitted email address. Do not commit or share the `.env`, and use a development provider key rather than a production credential.

If Brevo is temporarily unavailable, the retained `local-file` fallback can be enabled only with the exclusive `dev` profile:

```powershell
$env:IDENTITY_VERIFICATION_DELIVERY_MODE='local-file'
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
```

Each accepted registration then writes only its frontend verification URL to `.local/identity-verification/<accountId>.verification-url`. The ignored directory may contain an active challenge, so use synthetic addresses, do not publish its files, and delete them after testing. Startup rejects this fallback with no profile, with `prod`, or with `prod` combined with `dev`.

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

Only OpenAPI paths marked `x-mentalbridge-status: implemented` have runtime handlers. They currently cover registration, email verification, login, refresh, logout, logout-all, and current-account retrieval. Contract and provider tests compare this exact set with the Spring request mappings so an unavailable operation cannot silently become a frontend-facing 404.

Email-verification resend, password-recovery request/reset, authenticated password change, and account administration are explicitly marked `planned`. Their forward schemas remain published for design coordination, but clients must not call them until a later Identity slice changes their status and supplies the matching implementation and tests.
