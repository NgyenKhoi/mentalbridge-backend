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
- Public author data is limited to Community profile ID, chosen display name, and active/deleted presentation state. Deleted authors receive a neutral tombstone label.
- Only `READY` media delivery metadata is public. `PARTIAL` and `UNAVAILABLE` states remain explicit without leaking provider or moderation details.
- Feed/detail visibility uses only Community-owned post, topic, media, profile, and block data. It never reads Care, Journal/AI, SupportPlan, assessment, emotion, package, diagnosis, or severity data.

## Delivery boundary

MB-574 does not create posts, upload media, add comments/reactions/bookmarks, report, moderate, or publish asynchronous integrations. Those planned operations remain owned by MB-575 through MB-582. Community remains independent of synchronous Identity business, Care, and Journal/AI APIs.
