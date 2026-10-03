# Community Service

`community-service` is the independent Spring Boot owner for the Community bounded context. MB-574 adds authenticated newest-first feed, topic catalogue, and post detail reads. MB-575 adds personal-story lifecycle operations, MB-581 adds the owner-scoped display profile, MB-576 adds bounded owner-only image and short-video upload lifecycle operations, MB-578 adds reports, personal hide/block controls, and auditable administration, and MB-579 adds supportive reactions and owner-private bookmarks.

## Runtime

- Java 21 and Spring Boot 4
- service-owned PostgreSQL database with Liquibase migrations
- local verification of Identity-issued RS256 JWTs; no synchronous Identity lookup
- Cloudinary Java SDK configuration seam for later media workflows
- Actuator health/readiness and Prometheus metrics
- opaque cursor pagination with optional governed topic filtering
- fail-closed post visibility for moderation and bilateral Community blocks
- Community-owned display identity resolved from the locally verified JWT subject, without synchronous Identity or Care calls
- short-lived signed Cloudinary uploads with server-side format, size, duration, and ownership verification
- authenticated originals, signed metadata-stripped delivery transformations, and scheduled orphan cleanup
- naturally idempotent supportive reactions and private bookmarks with transactionally consistent reaction counts

## Display identity policy

The private Identity JWT subject is retained only as the ownership key and is never returned by a Community API. Public responses expose the opaque `communityProfileId`, chosen display name, and an optional closed avatar preset. Arbitrary avatar URLs are not accepted.

Feed, post, and future comment reads render the author's current active Community display identity. Changing a display identity does not update post content or any immutable moderation evidence snapshot. A deleted Community profile renders the neutral tombstone name and no avatar. External account lifecycle handling remains an independently replayable future adapter; current requests rely on local JWT signature, issuer, audience, expiry, and role verification and never synchronously call Identity or Care.

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
| `CLOUDINARY_TIMEOUT_SECONDS` | no | Bounded Admin API request/connect timeout; defaults to 5 seconds |
| `COMMUNITY_MEDIA_MAX_IMAGE_BYTES` | no | Maximum image upload size; defaults to 10 MiB |
| `COMMUNITY_MEDIA_MAX_VIDEO_BYTES` | no | Maximum video upload size; defaults to 50 MiB |
| `COMMUNITY_MEDIA_MAX_VIDEO_DURATION_SECONDS` | no | Maximum verified video duration; defaults to 60 seconds |
| `COMMUNITY_MEDIA_UPLOAD_INTENT_TTL` | no | Signed upload window as a Spring duration; defaults to `10m` |
| `COMMUNITY_MEDIA_ORPHAN_RETENTION` | no | Retention before unattached finalized media expires; defaults to `24h` |
| `COMMUNITY_MEDIA_CLEANUP_INTERVAL` | no | Scheduled cleanup cadence; defaults to `1h` |
| `SERVER_PORT` | no | HTTP port; defaults to `8084` |
| `EUREKA_CLIENT_ENABLED` | no | Enables service discovery; defaults to `true` |
| `EUREKA_DEFAULT_ZONE` | no | Eureka registry URL |

Do not commit `.env`, credentials, private keys, media signatures, or delivery URLs.

## Verify

```powershell
./mvnw test
```

The test suite starts disposable PostgreSQL, applies the Community migrations, boots the application with synthetic JWT/Cloudinary settings, and verifies feed/detail visibility, owner-isolated display profiles, Unicode bounds, optimistic concurrency, authentication, the public health endpoint, and fail-closed application routes without Identity or Care APIs running.

The canonical REST contract is [`contracts/openapi/community-service-v1.yaml`](../contracts/openapi/community-service-v1.yaml). Contract v1.6 marks feed, post/comment/media/profile lifecycle, reports, personal hide/block controls, moderation cases/actions, supportive reactions, and private bookmarks as implemented; notification-producing interactions remain planned.

MB-579 uses a compatibility-first rollout: deploy the frontend that accepts both v1.5 responses without `viewerState` and v1.6 responses with it before deploying Community v1.6. The frontend treats the presence of valid `viewerState` as the capability signal and does not render or call reaction/bookmark controls against v1.5. Do not deploy this backend ahead of that compatibility consumer.
