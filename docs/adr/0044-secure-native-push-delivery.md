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

## Amendment 2026-11-15 (MB-855)

This amendment replaces the server-wide daily reminder time, adds the SupportPlan
daily reminder kind and closes the MB-849 registration/preference race. All other
decisions stand.

### Per-user reminder time read at claim

`notification_preference.daily_reminder_time` (default `20:00`, local `HH:mm` in
`time_zone`) replaces `CONTENT_PUSH_DAILY_REMINDER_TIME`, which is removed; a
leftover value in an environment is ignored because the environment schema strips
unknown keys. One time applies to Journal, emotion and SupportPlan reminders. The
claim statement returns the owner's current time and time zone, so an edit made
after the inbox row exists but before the claim moves the due instant. Expiry at
the reminder's local midnight is unchanged.

An edit after a claim already deferred the push also counts: the preference update
makes that owner's `REMINDER_NOT_DUE` daily attempts claimable again in the same
transaction, and the gate re-defers them to the new instant (earlier or later, never
an early send). A deferral is written only if the preference still holds the time and
time zone the gate used (read under `FOR SHARE`); otherwise the attempt stays
claimable, which closes the edit-during-deferral race.

### SupportPlan daily reminder

Content's existing reminder scheduler asks Care
(`POST /internal/v1/support-plan-reminder-eligibility`, header
`X-MentalBridge-Service-Token`, Content variable
`CONTENT_CARE_SUPPORT_PLAN_REMINDER_SERVICE_TOKEN`) once the owner's daily time has
passed and creates at most one `SUPPORT_PLAN_DAILY_REMINDER` inbox row per owner and
local day (`source` `CARE_SUPPORT_PLAN`, `source_identity`
`SUPPORT_PLAN_DAILY_REMINDER:YYYY-MM-DD`, targetless `OPEN_SUPPORT_PLAN` resolving to
`/support-plan`). Care alone decides eligibility (current ACTIVE plan, SCHEDULED
occurrence due today in its own schedule time zone, active schedule and action,
`reminderEnabled`) and returns only a boolean; Care is never called inside a Content
database transaction. A Care failure creates nothing and is retried on the next
scheduler run that day; a definite `false` settles the owner for that local day in
process memory (a restart costs one extra Care call; the inbox insert is idempotent).
The row needs the master, in-app and `supportPlanReminder` group choices and is not
created during quiet hours. An unset token disables the reminder without failing
startup.

At claim the push gate applies the same time and expiry rules, then rechecks Care:
`false` or an unconfigured token suppresses as `ACTIVITY_COMPLETED`, a Care failure
defers five minutes as `ACTIVITY_UNAVAILABLE`. The push always carries the fixed
copy title `Bạn có hoạt động trong kế hoạch hôm nay` and body
`Mở MentalBridge để xem kế hoạch hỗ trợ hôm nay.`, never an action title, count or
clinical detail, whatever the stored row holds. The plan kind uses the same-day
device eligibility rule as the other two daily kinds and the new
`supportPlanReminder` content group in every claim-time preference check.

### Registration before push opt-in

For the three daily kinds the worker no longer writes a terminal
`SUPPRESSED/PREFERENCE_DISABLED` attempt while the master switch or PUSH channel is
off; it writes nothing, so a later run after opt-in inserts a `PENDING` attempt that
is still bounded to the reminder's local day by the same-day device rule and the
claim-time expiry. Every other kind keeps the terminal suppression, so enabling push
never replays older messages. Existing terminal rows stay as history. The
`(notification_id, device_registration_id)` unique key keeps concurrent workers at
one attempt per pair.

### Authorization rechecked after the gates

The worker repeats the claim-time preference and device check after the appointment
and daily gates answer `SEND` and immediately before the provider call, so a push
turned off or a device revoked while Care or Journal AI was responding suppresses the
attempt as `NOTIFICATION_UNAVAILABLE` instead of sending.
