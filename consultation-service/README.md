# Consultation Service

Owns specialist approval and discovery, matching, availability, appointments, and reviews. Appointment invariants stay transactionally local in its PostgreSQL data.

## Integration

- Inbound REST: Consultation APIs will be defined in OpenAPI before implementation.
- Outbound REST: calls Care for current consent and authorization decisions through a consumer-owned OpenFeign adapter and Resilience4j; uncertainty fails closed.
- Async: appointment, review, and moderation events will use Kafka with a transactional outbox.
- Discovery: registers as `consultation-service` and resolves Spring providers through Eureka. Registry data does not grant authorization.

## Configuration

| Variable | Required | Purpose | Safe local example |
| --- | --- | --- | --- |
| `EUREKA_DEFAULT_ZONE` | Production | Eureka registry endpoint shared by Spring services | `http://localhost:8761/eureka/` |

Production must override the local Eureka URL. Database, Kafka, Cloudinary, signing, and downstream timeout variables will be documented when their typed configuration is introduced.

## Run and test

```powershell
.\mvnw.cmd spring-boot:run
.\mvnw.cmd test
```

The generated context test uses PostgreSQL and Kafka Testcontainers and disables live Eureka registration.
