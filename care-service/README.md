# Care Service

Owns profiles, consent, assessments, deterministic scoring, risk policy, intervention, and follow-up. Safety-critical scoring and guidance remain local and do not depend on Eureka, OpenFeign, Kafka, Redis, or AI availability.

## Integration

- Inbound REST: Care APIs will be defined in OpenAPI before implementation.
- Outbound REST: may call the minimum Identity decision or projection required for exceptional current account facts through a consumer-owned OpenFeign adapter and Resilience4j.
- Async: assessment, risk, consent, intervention, and follow-up events will use Kafka with a transactional outbox.
- Discovery: registers as `care-service` and resolves Spring providers through Eureka. Registry data does not grant authorization.

## Configuration

| Variable | Required | Purpose | Safe local example |
| --- | --- | --- | --- |
| `EUREKA_DEFAULT_ZONE` | Production | Eureka registry endpoint shared by Spring services | `http://localhost:8761/eureka/` |

Production must override the local Eureka URL. Database, Kafka, signing, and downstream timeout variables will be documented when their typed configuration is introduced.

## Run and test

```powershell
.\mvnw.cmd spring-boot:run
.\mvnw.cmd test
```

The generated context test uses PostgreSQL and Kafka Testcontainers and disables live Eureka registration.
