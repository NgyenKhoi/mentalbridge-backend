# Community Service

`community-service` is the independent Spring Boot owner for the Community bounded context. MB-574 adds authenticated newest-first feed, topic catalogue, and post detail reads. MB-575 adds personal-story lifecycle operations, MB-581 adds the owner-scoped display profile, MB-576 adds bounded owner-only image and short-video upload lifecycle operations, MB-578 adds reports, personal hide/block controls, and auditable administration, MB-579 adds supportive reactions and owner-private bookmarks, MB-580 publishes minimized interaction facts through a transactional outbox, MB-614 adds one optional stable Content-owned Resource reference per post, MB-615 adds one explicit non-clinical sensitive-content warning per post, and MB-616 adds an owner-private saved-post collection.

## Runtime

MB-684 accepted the [Community chat contract](../docs/adr/0042-community-chat-contract.md)
and [privacy policy](../docs/policies/community-chat-policy-v1.md). MB-685 delivers
the direct-chat provider subset in Community PostgreSQL, including fresh Identity
eligibility checks. Groups and authorized Realtime transport/presence remain
downstream stories. Only delivered operations are registered by the canonical
Community OpenAPI through its direct-chat fragment; the proposal is the frozen
baseline for future delivery rather than a capability advertisement.

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

The private Identity JWT subject is retained only as the ownership key and is never returned by a Community API. Public responses expose the opaque `communityProfileId`, chosen display name, an optional closed avatar preset, and an optional current Community-only uploaded image. Arbitrary avatar URLs are not accepted.

The owner-approved uploaded-avatar extension uses the existing signed media upload
and server verification path. `PUT /api/v1/community/profile/avatar` requires a
strong profile `If-Match` and only `{mediaId}`: an owned, unattached READY IMAGE
with upload provenance and known positive dimensions, or null to remove the
photo. Legacy URL-only media cannot be selected. Different selections reject
stale versions; an unchanged retry returns the current profile without changing
its version. Normal name/preset replacement preserves the photo. This is a
separate explicit command, not an import from Identity or Care.

The new nullable `avatar_media_id` has an owner-bound composite foreign key.
Active selection prevents post attachment, direct media deletion and orphan
cleanup. Cleanup rechecks current attachment after locking a candidate to avoid
racing a completed selection. Removal/replacement releases the old image to the
existing unattached-media retention; it does not claim immediate provider erasure.
Named posts/comments resolve the current signed, metadata-stripped derivative;
anonymous posts and deleted identities omit the image. Binary content, original
provider payloads, account identity and health data remain outside responses.

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

The canonical REST contract is [`contracts/openapi/community-service-v1.yaml`](../contracts/openapi/community-service-v1.yaml). Contract v1.11 adds independent selection/removal of a verified Community-only uploaded avatar to feed, owner-private newest-saved browsing, governed active topic discovery, bounded multi-topic filtering, post/comment/media/profile lifecycle, optional stable Resource attachment, explicit sensitive-content warning, reports, personal hide/block controls, moderation cases/actions, supportive reactions, and private bookmarks. Saved results never accept another owner identifier and omit any post that the normal Community visibility policy makes inaccessible. Topic selection is explicit and matches any of at most three selected active labels; it never consults Care, assessment, Journal, emotion, severity, or AI data. Deactivation prevents new selection while preserving historical post membership and moderation provenance. Resource attachment is a logical UUID reference only: Content remains authoritative for current publication and metadata, the frontend resolves it through the existing Content REST/BFF boundary, and an unavailable Content response never causes Community to expose copied fallback content. A warning is only the explicit `SENSITIVE_CONTENT` governance marker selected by the author or an authorized moderator; it does not classify distress, infer diagnosis/severity, or alter post lifecycle. The versioned event schema is [`contracts/events/community/community-interaction-v1.schema.json`](../contracts/events/community/community-interaction-v1.schema.json), published on `mentalbridge.community.interaction.v1`.

Eligible external root comments route to the post owner, replies route to the parent-comment owner, and the first reaction for an actor/post pair routes to the post owner. Self-interactions, bookmarks, reaction replacement/removal, and repeated logical commands do not create notification facts. Kafka and Notification are never called inside Community commands; an unavailable broker leaves the committed outbox row retryable.

MB-579 uses a compatibility-first rollout: deploy the frontend that accepts both v1.5 responses without `viewerState` and v1.6 responses with it before deploying Community v1.6. The frontend treats the presence of valid `viewerState` as the capability signal and does not render or call reaction/bookmark controls against v1.5. Do not deploy this backend ahead of that compatibility consumer.

## MB-685 direct-chat provider

Community owns the direct-chat provider subset in [`community-chat-v1.json`](../contracts/openapi/community-chat-v1.json). `COMMUNITY_CHAT_ENABLED` defaults false until the operator supplies the dedicated Identity credential and a versioned 32-byte AES key. No success or cached current history is returned while unavailable. No Community message is stored in Realtime or transmitted through Kafka/Redis. MB-686 owns clients; groups and socket/presence delivery remain downstream stories.

| Variable | Meaning |
| --- | --- |
| `COMMUNITY_CHAT_ENABLED` | Enables delivered provider APIs after required credentials/keys are installed. |
| `COMMUNITY_CHAT_IDENTITY_BASE_URL` | Optional explicit trusted Identity REST URL; empty uses Eureka service ID. |
| `IDENTITY_COMMUNITY_CHAT_SERVICE_TOKEN` | Dedicated 32–512 character service credential shared only with Identity eligibility endpoint. |
| `COMMUNITY_CHAT_IDENTITY_CONNECT_TIMEOUT` | Positive connect deadline, default PT0.5S. |
| `COMMUNITY_CHAT_IDENTITY_READ_TIMEOUT` | Positive read deadline, default PT1.5S; connect + read cannot exceed 2 seconds. |
| `COMMUNITY_CHAT_ENCRYPTION_KEY` | Required base64 32-byte AES-GCM/HMAC secret when enabled; supplied externally. |
| `COMMUNITY_CHAT_ENCRYPTION_KEY_VERSION` | Stable non-secret key identifier, default v1; never reuse a version with another key. |
| `COMMUNITY_CHAT_DECRYPTION_KEYS` | JSON object of retained version→base64 keys for ciphertext/cursor/retry rotation, default {} (at most ten prior versions). |

Cursor tokens are AES-GCM authenticated ciphertext, bound to actor, surface and visibility epoch. They expose neither numeric message gaps nor private identities. Public version is for management only. Reads/sends use a fresh bounded, fail-closed Identity eligibility read outside the Community transaction, then lock current profiles in UUID order before checking local restrictions/blocks and mutating. No cross-service transaction or cached positive grant is claimed.
