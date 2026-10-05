# MB-618 Community end-to-end closure evidence

MB-618 closes the MB-573 Community epic by verifying the already-delivered
production paths as one coherent peer-support journey. It does not introduce a
new domain feature or move ownership across service boundaries.

## Backend journey evidence

`CommunityEndToEndJourneyIntegrationTests` runs the real Community REST,
security, transaction, JPA, JDBC, Liquibase, and PostgreSQL paths against a
disposable Testcontainers database. Its closure journey verifies:

- independent Community display profiles;
- a replay-safe anonymous post with a governed `HELPFUL_RESOURCE` topic, exact
  reviewed-resource reference, and bounded sensitive-content warning;
- non-reversible anonymous projections in feed responses;
- symmetric block/unblock and owner-private hide/unhide visibility;
- idempotent root comment, one-level reply, supportive reaction, bookmark, and
  private saved-post behavior with exact aggregate counts;
- replay-safe reporting and audited administrator moderation;
- fail-closed detail, feed, saved-list, and interaction behavior after a
  moderation hide; and
- exactly one minimized v1 interaction fact for each logical comment, reply,
  and reaction, with Community body and hidden identity fields absent.

The existing owner suites remain authoritative for boundary depth, including
media provider lifecycle, deterministic cursor paging, Unicode code-point
limits, optimistic concurrency, revision history, topic governance, schema
compatibility, event relay retry, and privacy-specific edge cases.

## Cross-service failure isolation

`CommunityNotificationRecoveryCrossServiceIT` remains the deterministic
outage-to-recovery proof for the asynchronous boundary. It uses disposable
Community and Content/Notification PostgreSQL owners plus a real Kafka broker:
Community commits while the consumer is absent, the v1 fact remains durable,
the recovered consumer catches up once, and restart/replay does not duplicate
the inbox item. Notification availability is therefore not part of a Community
command transaction.

## Evidence classification

- Backend integration: real service code and real disposable PostgreSQL.
- Notification recovery: real Community and Content/Notification service code,
  real disposable PostgreSQL databases, and real Kafka.
- Frontend closure journey: production Next.js build and real same-origin BFF
  routes driven by deterministic synthetic Identity, Community, and media
  provider fixtures. It is browser-contract evidence, not a live cloud claim.

## Reproduction

From `community-service`:

```text
mvnw.cmd -Dtest=CommunityEndToEndJourneyIntegrationTests test
mvnw.cmd test
```

The cross-service recovery job is also exercised by the dedicated CI workflow
defined for Community notification recovery.
