# Identity Service

Owns accounts, credentials, roles, sessions, account lifecycle, deletion coordination, and a minimized security/audit projection. It owns PostgreSQL identity data and may use Redis only for expiring hashed OTP challenges and bounded security state.

## Integration

- Inbound REST: public and internal identity APIs will be defined in OpenAPI before implementation.
- Outbound REST: none in the initial architecture, so this service does not include OpenFeign.
- Async: account lifecycle, deletion, and safe audit events will use Kafka with a transactional outbox.
- Discovery: registers as `identity-service` in Eureka. Eureka supplies location metadata only.

## Configuration

| Variable | Required | Purpose | Safe local example |
| --- | --- | --- | --- |
| `EUREKA_DEFAULT_ZONE` | Production | Eureka registry endpoint shared by Spring services | `http://localhost:8761/eureka/` |

Production must override the local Eureka URL. Authentication, database, Kafka, Redis, signing, and encryption variables will be documented when their typed configuration is introduced.

## Run and test

```powershell
.\mvnw.cmd spring-boot:run
.\mvnw.cmd test
```

The generated context test uses PostgreSQL, Kafka, and Redis Testcontainers and disables live Eureka registration.
