# Content/Notification Service specification

## Business boundary

Content/Notification owns reviewed self-help resources and eligibility,
reviewed area-directory entries, notification preferences, deterministic
scheduler execution, durable notifications, templates, delivery attempts, and
outcomes. Care owns safety, Support Guide, SupportEvaluation, the official
SupportPlan, and final `PlanChangeRequest` decisions. Content/Notification never
infers safety, proximity, or emergency response; ADR 0017 requires provenance
and area wording for the directory and forbids automatic safety email.

## Use cases and acceptance

| Capability | Main behavior | Acceptance |
| --- | --- | --- |
| Resources | Admin versions, publishes, retires and reviews localized self-help content and separately governed eligibility | Only reviewed active versions served; locale/effective/review dates explicit; future plan use also requires explicit domain/instrument-band/pathway eligibility; unavailable content has an explicit fallback status |
| Area directory | Publish sourced, reviewed and verified entries for user-selected province/district/area | Source/provenance, `reviewedAt`, `verifiedAt`, address, phone, coverage and active state required; never claim nearest without coordinates/distance |
| Preferences | User manages channel/category choices | Mandatory safety/security categories follow approved policy; owner authorization and optimistic locking enforced |
| Reminder scheduling | Create at most one default wellbeing digest/day, explicit opt-in resource reminders, and one appointment reminder near one hour before start | Deterministic selection/deduplication; AI only phrases approved facts; no automatic safety email |
| Notification history | Persist list/detail/read/delete state | Bounded cursor pagination; user sees own records only; read/delete idempotent; retention semantics explicit |
| Domain notification | Consume minimized facts, select versioned template and create notification | Duplicate message creates one logical notification; replay policy prevents repeated external sends |
| Provider delivery | Send through bounded adapter and track every attempt | Domain outcome independent; transient retry honors provider signals; terminal failure visible; no secrets/sensitive body in logs |
| Live delivery | Publish `NotificationCreated` for Realtime | Durable record/outbox atomic; payload minimal; reconnect relies on REST history, not guaranteed socket delivery |

## Implementation design

- Feature slices: `resources`, `area-directory`, `preferences`, `scheduling`,
  `notifications`, `templates`, `provider-delivery`.
- Runtime: Node.js 22 or newer, strict TypeScript, NestJS 11, Zod, `pg`, `node-pg-migrate`, KafkaJS, Pino, OpenTelemetry, Vitest, and Testcontainers as defined in `docs/nodejs-service-stack.md`.
- Define public/admin OpenAPI and versioned consumed/published events first. PostgreSQL migrations include template/delivery-attempt aggregates and outbox/inbox deduplication.
- Template rendering and provider APIs sit behind application ports. Optional delivery never becomes safety truth and cannot claim a human or emergency service was notified.
- Existing resource contracts and rows remain valid for reviewed public reads. Resource Eligibility v1 adds immutable exact-version publications, explicit declarations, withdrawal and a bounded Care batch resolver under #50; no service infers universal plan eligibility from current data.

## Ordered tasks

- [x] CN-01 Scaffold the NestJS/TypeScript service with feature modules, typed configuration, health/readiness, lint, test, and build commands.
- [ ] CN-02 Implement exact-version domain/band/pathway eligibility with approved `PRIMARY`/`ADJUNCT` roles (#50), plus notification template, mandatory-category, delivery retry and retention policies.
  - [x] Exact-version eligibility provider, Care consumer, and initial controlled-demo item matrix are implemented; the notification-policy remainder stays open.
- [ ] CN-03 Define resource/preference/notification OpenAPI and notification event schemas.
  - [x] MB-562 defines the owner preference OpenAPI; notification history and event schemas remain open.
- [x] CN-04 Add owner `node-pg-migrate` migrations, constraints/indexes and field dictionary entries.
- [ ] CN-05 Implement reviewed content administration and safe current-resource reads.
- [ ] CN-06 Implement preferences and durable notification history/state.
  - [x] MB-562 implements persisted owner preferences; notification history/state remains open.
- [ ] CN-07 Implement idempotent event consumption, versioned rendering, provider attempts and outbox to Realtime.
- [ ] CN-08 Verify authorization, stale content, duplicate/replay, rate limit, provider timeout/429/5xx, dead letter and outbox rollback.
- [ ] CN-09 Add observability/readiness/configuration, README, and pass Node/contract/PostgreSQL/Kafka gates.

## Sprint 1 boundary

Sprint 1 covers CN-01, resource OpenAPI and owner `node-pg-migrate` baseline, and a reviewed resource CRUD slice with synthetic data. The previously planned hotline catalogue is removed by ADR 0009 and migration 2. Notification preferences/history, Kafka consumers, templates, Brevo/push delivery, retry/dead-letter handling, and live delivery are deferred. No provider or cloud account is needed.
