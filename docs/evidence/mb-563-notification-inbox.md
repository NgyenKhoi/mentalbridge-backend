# MB-563 persisted notification inbox evidence

## Delivered boundary

Content/Notification owns one durable in-app notification aggregate per source
event and recipient. The owner API lists only the authenticated user's active
records, orders them by `(created_at, id)` newest first, and uses an opaque
keyset cursor. Single-read, bulk-read, and deletion are persisted, idempotent
lifecycle operations.

The aggregate supports reminder, message, appointment, system/resource,
assessment/reassessment, and streak/milestone kinds. Producers supply only a
closed action enum and optional UUID target; the service derives the relative
route and never stores an arbitrary external URL. `(source, source_identity)`
is unique and a request fingerprint rejects changed retry payloads.

## Privacy and retention

The producer command is strict and has no Journal text, assessment answers,
chat body, self-report, provider payload, URL, diagnostic, or AI-decision field.
Title and body are bounded safe copy. Producers MB-564 and MB-565 remain
responsible for selecting reviewed factual templates from authoritative source
events; this story does not fabricate product activity.

Every row has a deadline no later than 90 days after creation. Inbox reads
tombstone expired rows before selecting visible records, while explicit delete
sets the same audit-preserving tombstone.
Any historical `SAFETY` row is migrated as `CANCELLED`, so this foundation does
not activate an automatic safety notification path.

## Verification map

- OpenAPI/controller tests: authentication, owner isolation, page validation,
  single/bulk read, deletion, dependency failure, and no-store behavior.
- Service tests: approved action enum, malformed targets, arbitrary URL and
  unexpected sensitive-field rejection, and stable retry fingerprint.
- PostgreSQL integration: unique dedupe/replay, changed-retry conflict,
  deterministic keyset pagination, concurrent read time, wrong owner,
  expiry tombstone, repeated deletion, constraints, and indexes.
- Frontend tests: validated BFF responses, dependency failure, loading/empty/
  error/page continuation, persisted single/bulk read, deletion, and approved
  internal navigation.

Live product producer mappings are explicitly delivered by MB-564 and MB-565.
