# Community Service

`community-service` is the independent Spring Boot owner for the Community bounded context. MB-574 adds authenticated newest-first feed, topic catalogue, and post detail reads. MB-575 adds personal-story lifecycle operations, MB-581 adds the owner-scoped display profile, MB-576 adds bounded owner-only image and short-video upload lifecycle operations, MB-578 adds reports, personal hide/block controls, and auditable administration, MB-579 adds supportive reactions and owner-private bookmarks, MB-580 publishes minimized interaction facts through a transactional outbox, MB-614 adds one optional stable Content-owned Resource reference per post, MB-615 adds one explicit non-clinical sensitive-content warning per post, and MB-616 adds an owner-private saved-post collection.

## Runtime

- Java 21 and Spring Boot 4
- service-owned PostgreSQL database with Liquibase migrations
- local verification of Identity-issued RS256 JWTs; no synchronous Identity lookup
- Cloudinary Java SDK configuration seam for later media workflows
- Actuator health/readiness and Prometheus metrics
- opaque cursor pagination with explicit OR filtering across at most three active governed topics
- fail-closed post visibility for moderation and bilateral Community blocks
- Community-owned display identity resolved from the locally verified JWT subject, without synchronous Identity or Care calls
- short-lived signed Cloudinary uploads with server-side format, size, duration, and ownership verification
- authenticated originals, signed metadata-stripped delivery transformations, and scheduled orphan cleanup
- naturally idempotent supportive reactions and private bookmarks with transactionally consistent reaction counts
- owner-only saved-post browsing ordered by immutable `(savedAt, postId)` descending cursors with the same fail-closed visibility policy as the feed
- minimized comment, reply, and first-reaction facts committed atomically and relayed independently to Kafka
- optional Resource attachment stores only the Content-owned Resource UUID; Community never copies Resource bodies, versions, publication state, or clinical/support meaning

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
| `KAFKA_BOOTSTRAP_SERVERS` | when relay enabled | Comma-separated brokers used only by the Community interaction relay |
| `COMMUNITY_INTERACTION_RELAY_ENABLED` | no | Publishes due interaction facts; defaults to `false` unless the topic is provisioned |
| `COMMUNITY_INTERACTION_RELAY_BATCH_SIZE` | no | Maximum outbox rows claimed per relay run; defaults to `100` |
| `COMMUNITY_INTERACTION_RELAY_INTERVAL` | no | Delay between relay runs; defaults to `PT5S` |
| `COMMUNITY_INTERACTION_RELAY_SEND_TIMEOUT` | no | Maximum Kafka acknowledgement wait; defaults to `PT5S` |
| `COMMUNITY_INTERACTION_RELAY_RETRY_BASE` | no | Initial independent retry delay; defaults to `PT5S` |
| `COMMUNITY_INTERACTION_RELAY_RETRY_MAXIMUM` | no | Maximum retry delay; defaults to `PT5M` |
| `SERVER_PORT` | no | HTTP port; defaults to `8084` |
| `EUREKA_CLIENT_ENABLED` | no | Enables service discovery; defaults to `true` |
| `EUREKA_DEFAULT_ZONE` | no | Eureka registry URL |

Do not commit `.env`, credentials, private keys, media signatures, or delivery URLs.

## Verify

```powershell
./mvnw test
```

The test suite starts disposable PostgreSQL, applies the Community migrations, boots the application with synthetic JWT/Cloudinary settings, and verifies feed/detail visibility, owner-isolated display profiles, Unicode bounds, optimistic concurrency, authentication, the public health endpoint, and fail-closed application routes without Identity or Care APIs running.

The canonical REST contract is [`contracts/openapi/community-service-v1.yaml`](../contracts/openapi/community-service-v1.yaml). Contract v1.10 marks feed, owner-private newest-saved browsing, governed active topic discovery, bounded multi-topic filtering, post/comment/media/profile lifecycle, optional stable Resource attachment, explicit sensitive-content warning, reports, personal hide/block controls, moderation cases/actions, supportive reactions, and private bookmarks as implemented. Saved results never accept another owner identifier and omit any post that the normal Community visibility policy makes inaccessible. Topic selection is explicit and matches any of at most three selected active labels; it never consults Care, assessment, Journal, emotion, severity, or AI data. Deactivation prevents new selection while preserving historical post membership and moderation provenance. Resource attachment is a logical UUID reference only: Content remains authoritative for current publication and metadata, the frontend resolves it through the existing Content REST/BFF boundary, and an unavailable Content response never causes Community to expose copied fallback content. A warning is only the explicit `SENSITIVE_CONTENT` governance marker selected by the author or an authorized moderator; it does not classify distress, infer diagnosis/severity, or alter post lifecycle. The versioned event schema is [`contracts/events/community/community-interaction-v1.schema.json`](../contracts/events/community/community-interaction-v1.schema.json), published on `mentalbridge.community.interaction.v1`.

Eligible external root comments route to the post owner, replies route to the parent-comment owner, and the first reaction for an actor/post pair routes to the post owner. Self-interactions, bookmarks, reaction replacement/removal, and repeated logical commands do not create notification facts. Kafka and Notification are never called inside Community commands; an unavailable broker leaves the committed outbox row retryable.

MB-579 uses a compatibility-first rollout: deploy the frontend that accepts both v1.5 responses without `viewerState` and v1.6 responses with it before deploying Community v1.6. The frontend treats the presence of valid `viewerState` as the capability signal and does not render or call reaction/bookmark controls against v1.5. Do not deploy this backend ahead of that compatibility consumer.
