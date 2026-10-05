# MB-617 Community notification consumer evidence

Content/Notification consumes `mentalbridge.community.interaction.v1` without
being present in the Community command transaction. The runtime schema is
strictly aligned with the frozen MB-573 v1 contract and accepts only event,
routing, target, interaction, occurrence, and safe post deep-link metadata.

Eligible facts materialize one owner-scoped notification with reviewed generic
copy. `eventId` is the durable source identity and a canonical SHA-256 event
fingerprint rejects changed identity reuse. Preference evaluation and insertion
share one PostgreSQL transaction. A disabled master switch, in-app channel, or
Community content group creates a `CANCELLED` row, preventing replay after a
later opt-in from creating a stale notification.

Malformed or incompatible facts are parked in the dedicated DLQ with source
topic, partition, offset, error code, timestamp, and payload digest only.
Transient database failures are retried and then propagated so Kafka retains
the offset for recovery. No raw Community body, media, identity display field,
email, Care, Journal, AI, or SupportPlan data is persisted or logged.

The inbox action is derived locally as `/community/{postId}`. The Community
detail route uses one indistinguishable unavailable state for removed, hidden,
blocked, and unknown posts, so a stale notification cannot reveal moderation or
existence details.

`CommunityNotificationRecoveryCrossServiceIT` supplies deterministic
outage-to-recovery evidence with disposable Community and Content/Notification
PostgreSQL owners plus a real Kafka broker. It commits an eligible comment
through the Community REST boundary while the Notification process is absent,
verifies the durable Community outbox and retained v1 Kafka fact, starts the
real Content/Notification consumer for catch-up, then restarts it and
republishes the same event. The consumer group advances through both records
while the inbox retains exactly one notification. The dedicated
`Cross-service / Community notification recovery` CI job builds both owners and
runs this test on every pull request.
