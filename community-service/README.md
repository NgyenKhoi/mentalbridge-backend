# Community Service

`community-service` is the independent Spring Boot foundation for the Community bounded context. MB-604 deliberately contains no Community business entity or API; later MB-574 through MB-582 slices add behavior against the frozen Community Contract v1.

## Runtime

- Java 21 and Spring Boot 4
- service-owned PostgreSQL database with Liquibase migrations
- local verification of Identity-issued RS256 JWTs; no synchronous Identity lookup
- Cloudinary Java SDK configuration seam for later media workflows
- Actuator health/readiness and Prometheus metrics

The service uses Maven, matching the other Spring modules. Liquibase is disabled in normal application replicas and runs through the Docker `migration` target or `./mvnw liquibase:update`.

## Configuration

Copy `.env.example` to `.env` for local development. Real environment variables override the local file, and CI/production set `SPRINGDOTENV_ENABLED=false`.

| Variable | Required | Purpose |
| --- | --- | --- |
| `COMMUNITY_DB_URL` | yes | JDBC URL for the Community-owned PostgreSQL database |
| `COMMUNITY_DB_USERNAME` | yes | Community database login |
| `COMMUNITY_DB_PASSWORD` | yes | Community database credential |
| `COMMUNITY_LIQUIBASE_ENABLED` | no | Explicitly enables in-process migration; defaults to `false` |
| `IDENTITY_JWT_ISSUER` | yes | Exact trusted Identity token issuer |
| `IDENTITY_JWT_AUDIENCE` | yes | Required Community API audience |
| `IDENTITY_JWT_PUBLIC_KEY` | yes | Identity RS256 public key; escaped PEM or base64 DER |
| `CLOUDINARY_CLOUD_NAME` | yes | Cloudinary tenant name |
| `CLOUDINARY_API_KEY` | yes | Cloudinary API identifier |
| `CLOUDINARY_API_SECRET` | yes | Cloudinary signing credential |
| `SERVER_PORT` | no | HTTP port; defaults to `8084` |
| `EUREKA_CLIENT_ENABLED` | no | Enables service discovery; defaults to `true` |
| `EUREKA_DEFAULT_ZONE` | no | Eureka registry URL |

Do not commit `.env`, credentials, private keys, media signatures, or delivery URLs.

## Verify

```powershell
./mvnw test
```

The test suite starts disposable PostgreSQL, applies the empty baseline changelog, boots the application with synthetic JWT/Cloudinary settings, and verifies the public health endpoint and deny-by-default application routes.
