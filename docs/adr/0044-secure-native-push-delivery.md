# ADR 0044: Secure native push delivery

- Status: Accepted for MB-675 implementation
- Date: 2026-10-22
- Decision ID: `MB-NATIVE-PUSH-DELIVERY-001`
- Owner: Content/Notification

## Context

MentalBridge already persists an owner-level PUSH preference and authoritative
in-app notifications. Native delivery requires an owner-scoped device lifecycle,
provider isolation, bounded retries, and privacy-minimized payloads. A device or
provider acknowledgement must never become notification truth.

## Decision

Content/Notification owns device registrations and push delivery attempts. The
first provider adapter uses Expo Push Service because the approved mobile stack
is Expo/React Native and Expo accepts both Android and iOS installations. The
adapter remains behind a local provider boundary so a later FCM/APNs adapter does
not change the public registration contract.

`PUT /api/v1/push-devices/{installationId}` is a natural idempotent replacement
operation. The verified JWT supplies the owner; client-supplied owner identifiers
are prohibited. One installation and one provider token may each be active for
only one owner, so registration atomically revokes every prior assignment even
when both the owner and token change. An identical PUT is a no-op and does not
advance eligibility timestamps or versions. `DELETE` is an idempotent
owner-only sign-out/revocation transition. Permission denial keeps a durable
disabled registration, while provider `DeviceNotRegistered` makes it invalid.
Both commands share a database-atomic per-owner fixed-window rate limit.

Provider tokens are encrypted with AES-256-GCM under an externally injected key.
Only a SHA-256 token hash is used for uniqueness and rotation. Tokens, provider
payloads, and provider responses are excluded from logs and REST responses.

The worker derives one delivery attempt per authoritative inbox notification and
active device. Top-level/PUSH preference, device permission, quiet hours, and
notification retention remain authoritative and are rechecked before submission.
Deletion atomically suppresses unsent attempts. Claims use database locking, at
most three provider submissions, and bounded exponential retry. Expo ticket
acceptance is recorded as `ACCEPTED`, never `DELIVERED`. Bounded delayed receipt
lookups record `CONFIRMED`, provider rejection, or unknown outcome and invalidate
the device when Expo reports `DeviceNotRegistered`. A successful Expo receipt
still does not prove that the user saw or physically received the notification.

Every push contains only a reviewed title, generic body, notification UUID, and
closed action pointer. It excludes assessment answers, Journal/emotion text,
crisis status, chat content, and AI payloads. The authenticated app fetches the
authoritative notification after opening.

Provider execution is disabled by default. Enabling it requires an Expo access
token and token-encryption key. Mocked tests prove adapter behavior but do not
claim production or real-device delivery. Android staging evidence remains gated
by MB-666 credentials and device availability.

## Consequences

- Multiple installations per owner are supported without using hardware IDs.
- Reinstall/token rotation and cross-owner token reassignment are explicit and auditable.
- Provider outages cannot change inbox truth and do not hold a database transaction open.
- Expo acceptance is observable without overstating end-device delivery.
- Disabling the worker is the rollback switch; the additive tables remain safe for rolling deployment.

## Amendment 2026-11-15 (MB-849 Android push)

This amendment narrows the "generic body" rule above and adds the daily reminder
timing. All other decisions stand.

### Reviewed copy allowlist

A push carries the stored inbox title and body only for kinds whose producers write
fixed copy with no person name, clinical result or user-written text:
`JOURNAL_REMINDER`, `EMOTION_CHECKIN_REMINDER`, `JOURNAL_STREAK_MILESTONE`,
`EMOTION_STREAK_MILESTONE` (reminder materialization, two reviewed title variants
per reminder kind picked per owner and local date) and `APPOINTMENT` (fixed
appointment-reminder text and the fixed specialist status table). Every other
kind, a legacy category or an unknown value is sent with the generic copy title
`MentalBridge` and body `Bạn có thông báo mới trên MentalBridge.`, which also
satisfies the community/chat lock-screen rule in `community-chat-policy-v1.md`.
`SYSTEM_RESOURCE` and `ASSESSMENT_REASSESSMENT` stay off the list because no
producer in this service writes them; add a kind only together with a producer
whose copy is fixed and reviewed.

### Payload

The Expo message adds `channelId` `mentalbridge-chime-v1`, `sound`
`mentalbridge_chime.wav`, `priority` `high` and `data` limited to
`notificationId`, `kind` and `action` (the closed `{ type, targetId, href }`
pointer or `null`). No other free text is sent. The app opens the destination from
`data` and marks the notification read; it does not fetch by id.

### Daily reminder timing

The inbox row for `JOURNAL_REMINDER` and `EMOTION_CHECKIN_REMINDER` is still
created at local 00:00. Push for these two kinds is gated at send time, after the
claim transaction has committed, by the owner's local date taken from
`source_identity`:

- local date later than the reminder date: suppressed as `REMINDER_EXPIRED`
  (quiet hours covering the push time, or worker lag, never push the next morning);
- before `CONTENT_PUSH_DAILY_REMINDER_TIME` (default `20:00`, `HH:MM` in the owner
  time zone): deferred to that local time as `REMINDER_NOT_DUE`;
- otherwise the activity source is read: the activity already done today is
  suppressed as `ACTIVITY_COMPLETED`, activity attributed to another local day as
  `REMINDER_EXPIRED`, and an unavailable source defers five minutes as
  `ACTIVITY_UNAVAILABLE` (never sent blindly). Quiet hours still apply at claim.

A deferral does not consume one of the three provider submissions.

### Same-day device eligibility

For these two kinds a device is eligible when its `delivery_eligible_from_at` is
before the end of the reminder's local day (`occurred_at + 1 day`), so a device
registered at 15:00 receives that evening's reminder. Other kinds keep
`created_at >= delivery_eligible_from_at`; earlier days stay excluded.
