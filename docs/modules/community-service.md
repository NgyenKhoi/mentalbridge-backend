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

## MB-578 reports, blocks, and moderation

- A USER may report one currently visible post/comment with a stable reason and bounded optional context. Owner-scoped idempotency keys replay safely and conflicting reuse is rejected.
- Personal content hides and bilateral profile blocks fail closed in feed, detail, comment reads, and new interactions without changing the target for other users.
- ADMIN-only case queries expose a minimized first-report evidence snapshot and aggregate stable reasons. Actions append actor, reason, timestamp, target version, prior state, and resulting state before changing visibility or Community-only access.
- `SELF_HARM_OR_CRISIS_CONCERN` raises queue priority and the UI may offer the existing help-now route. It never diagnoses, infers suicidality, books care, contacts a third party, or mutates Care/SupportPlan state.

## MB-579 supportive reactions and private bookmarks

- A USER may keep at most one `SUPPORT`, `RELATE`, or `THANK_YOU` reaction per active visible post. Replacing a reaction leaves the aggregate count unchanged; repeated PUT/DELETE requests are natural no-ops.
- Reaction create/remove locks the owning post row so concurrent commands cannot lose or duplicate the non-negative display count.
- A USER may bookmark an active visible post privately. Feed and detail responses expose only that authenticated viewer's reaction/bookmark state and never another profile's bookmarks.
- Owner-deleted, moderation-hidden, moderation-removed, personally hidden, and bilaterally blocked posts share the bounded not-found mutation behavior and cannot receive new interactions.
- Reactions and bookmarks remain Community-owned peer-support facts. They do not alter feed order, a clinical recommendation, Care, Journal/AI, screening, SupportPlan, or a hidden mental-health profile.
- Rolling deployment is compatibility-first: deploy the frontend consumer that accepts both absent and present `viewerState` before Community v1.6. Absence means interaction capability is unavailable and controls stay hidden; once v1.6 returns valid viewer state, controls activate automatically. Backend-first deployment is not supported because the legacy strict parser rejects additional post fields.

## MB-580 minimized interaction facts

- Eligible external root comments, replies, and first reactions create one `mentalbridge.community.interaction.v1` outbox fact in the same local transaction as the Community interaction.
- Root comments route to the post owner, replies route to the parent-comment owner, and reactions route to the post owner. Self-interactions, bookmarks, reaction replacement/removal, and repeated logical interactions do not publish another fact.
- The event contains only its UUID/version, actor Community profile UUID, private target-owner routing UUID, target type/UUID, bounded interaction kind, occurrence time, and a Community-post deep-link descriptor. It excludes all content, media, display identity, email, and Care/Journal/AI/SupportPlan data.
- Kafka relay is post-commit, retryable, and optional at runtime. Kafka or downstream Notification absence cannot fail or roll back Community REST commands, and Notification consumption remains outside Community authority.

## MB-615 bounded sensitive-content warning

- An active post may carry no warning or the single explicit governance marker `SENSITIVE_CONTENT`. The author may add or remove it through the existing owner-only, exact-version post update flow.
- Authorized ADMIN moderation may apply or remove the marker through idempotent, append-only moderation actions. Those actions audit `NONE` and `SENSITIVE_CONTENT` transitions while leaving the post lifecycle unchanged.
- The marker is returned by feed/detail only as optional presentation metadata. It contains no body, diagnosis, severity, sentiment result, Care, assessment, Journal/AI, or SupportPlan data.
- No runtime path automatically derives the marker from negative language or distress. Warning changes never replace `MODERATION_HIDDEN` or `MODERATION_REMOVED`, and hidden/removed targets continue to fail closed.

## MB-616 private saved-post collection

- `GET /api/v1/community/saved-posts` derives the owner only from the authenticated JWT subject and accepts no owner/profile selector.
- Results are ordered deterministically by immutable bookmark `(created_at, post_id)` descending and continue through an opaque bounded cursor.
- Only active posts that remain visible under personal-hide and bilateral-block rules are returned. Owner-deleted, moderation-hidden, moderation-removed, personally hidden, and blocked posts fail closed without exposing their retained bookmark row.
- The query reuses the normal Community post summary, current display identity, media availability, Resource reference, warning, counts, and authenticated viewer state. Successful unbookmark removes the row from the next authoritative query.
- Saved membership remains private Community data and never becomes a recommendation, popularity, diagnosis, severity, Care, Journal/AI, screening, emotion, or SupportPlan signal.
