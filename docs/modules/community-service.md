# Community Service specification

## Ownership

`community-service` is the independent owner for the governed Community peer-support context established by ADR 0027 (`MB-COMMUNITY-SERVICE-001`). Future delivery stories may add Community display profiles, posts, comments, reactions, moderation, reports, blocks, and Community-owned media metadata.

It does not own Identity credentials, Care profiles, screening, SupportPlan, Journal entries, emotion history, AI analysis, specialist chat, reviewed editorial resources, or notification delivery.

## MB-604 executable foundation

- Spring Boot 4 / Java 21 application built with Maven.
- Dedicated PostgreSQL connection and an empty Liquibase baseline.
- Local RS256 verification of Identity-issued JWT issuer, audience, signature, expiry, and roles.
- Environment-only Cloudinary configuration seam; no upload or delivery operation exists yet.
- Public Actuator health/readiness, authenticated Prometheus metrics, and deny-by-default future application paths.
- Multi-stage Docker runtime/migration targets and CI matrix coverage.

## MB-574 feed and personal-story reads

- `GET /api/v1/community/feed` returns only active posts in deterministic `(publishedAt, postId)` descending order, with an optional governed topic filter and opaque cursor.
- `GET /api/v1/community/posts/{postId}` returns an active visible post or the same bounded not-found response for absent, hidden, removed, and blocked content.
- `GET /api/v1/community/topics` returns the six non-diagnostic v1 topic definitions.
- Public author data is limited to the chosen per-post projection: Community profile ID/display name/avatar for `PROFILE`, or a neutral unlinkable label for `ANONYMOUS`. Deleted authors receive a neutral tombstone label.
- Only `READY` media delivery metadata is public. `PARTIAL` and `UNAVAILABLE` states remain explicit without leaking provider or moderation details.
- Feed/detail visibility uses only Community-owned post, topic, media, profile, and block data. It never reads Care, Journal/AI, SupportPlan, assessment, emotion, package, diagnosis, or severity data.

## Delivery boundary

MB-574 does not upload media, add comments/reactions/bookmarks, report, moderate, or publish asynchronous integrations. Those planned operations remain owned by MB-576 through MB-582. Community remains independent of synchronous Identity business, Care, and Journal/AI APIs.

## MB-575 personal-story lifecycle

- `POST /api/v1/community/posts` creates one active owner post with one to three governed topics and up to ten already-owned `READY` media IDs. `Idempotency-Key` is scoped by Community owner and conflicting reuse is rejected.
- A neutral Community-local display profile is provisioned on first publish when MB-581 profile setup has not run. No synchronous Identity, Care, Journal/AI, assessment, or SupportPlan business API is called.
- Owner detail, create, and update expose the quoted post version as `ETag`; `PATCH` and `DELETE` require the same quoted version in `If-Match` and reject stale commands.
- Cross-owner, absent, hidden, removed, and owner-deleted mutations share the bounded not-found contract and do not disclose ownership or moderation state.
- Owner deletion changes the post to `OWNER_DELETED`, detaches media, and removes it from feed/detail immediately while retaining auditable content and lifecycle state in Community storage.
- Community story text remains inside `community-service`; it is not emitted or copied into Care, reassessment, Journal/AI, SupportPlan, or specialist context.

## MB-576 bounded Community media lifecycle

- `POST /api/v1/community/media/upload-intents` creates an idempotent, owner-scoped ten-minute signed Cloudinary upload for allowlisted JPEG, PNG, WebP, MP4, WebM, or QuickTime files. Images are limited to 10 MiB; videos are limited to 50 MiB and 60 seconds.
- Browser uploads use Cloudinary `authenticated` storage and a server-signed immutable storage key. The API secret, original object URL, and provider response are never returned by Community APIs or persisted in a post.
- `POST /api/v1/community/media/{mediaId}/finalize` verifies the exact storage identity, detected format, byte count, dimensions, and video duration outside a database transaction. Valid objects become `READY`; mismatches become `REJECTED`.
- READY images and videos are delivered only through signed transformed URLs. Cloudinary transformations strip location-sensitive image metadata and minimize delivered video metadata while originals remain authenticated.
- A post may attach at most ten distinct media items that are `READY`, owned by its author, and unattached elsewhere. An attached media item must be removed through the post update contract before its own DELETE endpoint can tombstone it.
- The scheduled retention job expires timed-out uploads and unattached READY/REJECTED objects after 24 hours. Provider deletion is retried for retained `DELETED` or `EXPIRED` rows without holding a database transaction across the provider call.
- Liquibase change `0006-community-request-fingerprint-varchar` upgrades the post and media SHA-256 fingerprint columns from fixed-width `char(64)` to JPA-compatible `varchar(64)` without rewriting their values or changing idempotency semantics.

## MB-609 per-post public identity

- Each create or update may explicitly choose `PROFILE` or `ANONYMOUS`; omitted create values remain `PROFILE`, and omitted update values preserve the current mode for rolling-client compatibility.
- Anonymous feed and detail responses return a neutral label with a null `communityProfileId` and no avatar preset, preventing public linkage to the author's other posts or Community display identity.
- `author_profile_id` remains private and authoritative for owner authorization, moderation, abuse controls, and audit. Anonymous mode never removes ownership evidence or changes bilateral block enforcement.

## MB-577 comments and one-level replies

- Active visible posts accept owner-scoped idempotent comments and replies. A reply may reference only an active top-level comment on the same post, so V1 nesting never exceeds one level.
- Comment reads use deterministic `(created_at, id)` ascending cursor pagination. Hidden/removed comments, hidden/removed posts, and either direction of a Community block fail closed.
- Comment edits and owner deletion require the exact quoted version through `If-Match`. Deletion keeps a neutral public tombstone so existing replies retain context while the post's active comment count is decremented exactly once.
- Every create, edit, and owner deletion writes a Community-local immutable revision snapshot. Comment text and revision history remain inside `community-service` and are never reused as Care, Journal/AI, screening, SupportPlan, or specialist evidence.
