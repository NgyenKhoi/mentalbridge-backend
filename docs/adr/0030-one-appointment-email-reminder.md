# ADR 0030: One appointment email reminder

- Status: Accepted
- Date: 2026-09-30
- Decision ID: `MB-APPOINTMENT-EMAIL-REMINDER-001`
- Implements: MB-547
- Governs delivery: MB-548, MB-549, and MB-550
- Appointment owner dependencies: MB-379 and MB-380
- Product scope: [ADR 0017](0017-product-scope-v2.md)

## Context

The approved product scope permits one appointment-specific email near one hour
before a confirmed in-app chat or video appointment. It is not part of the
MB-514 daily wellbeing digest. Before implementation, the exact offset,
recipient preference, quiet-hour outcome, lifecycle invalidation, deduplication,
late execution, and minimized content need one owner-reviewed decision.

Consultation already owns the authoritative appointment state, immutable
scheduled interval and modality snapshot, optimistic appointment version, and
MB-380 cancel/reschedule relationship. Content/Notification already owns the
user's notification preference, timezone and quiet hours. Neither owner may
infer the other's current state from copied database data or browser claims.

## Decision

### Ownership and handoff

The only recipient is the `USER` account that owns the appointment. A
specialist, admin, emergency contact, or third party does not receive this
email. AI does not select the recipient, eligibility, timing, suppression, or
retry outcome.

Consultation remains authoritative for `appointmentId`, `appointmentVersion`,
owner ID, status, `scheduledStartAt`, modality, and replacement relationship.
Its appointment transition and a minimized `AppointmentStatusChanged` v1
outbox record commit in the same local transaction. The durable event is keyed
by appointment ID and contains only those scheduling facts; it contains no
email address, display name, health data, Journal data, assessment data,
`ConsultationBrief`, message content, or provider credential.

Content/Notification idempotently consumes that event and owns reminder intent,
due-time calculation, preference evaluation, delivery attempts, and the email
provider adapter. Before provider submission it performs an authenticated,
bounded Consultation owner check for the exact appointment ID and version.
Unavailability, version uncertainty, a status other than the same confirmed
version, or a start time that is no longer in the future fails closed and no
email is submitted. The check is not performed while a Content/Notification
database transaction is open.

At delivery time Content/Notification resolves the current verified address
for the appointment owner through a minimized authenticated Identity contract.
The address is not copied into the appointment event or reminder business
payload. An unavailable Identity owner, missing verified address, or owner
mismatch fails closed and remains retryable only while the appointment has not
started.

### Exact schedule and preference

The policy offset is exactly 60 minutes:

```text
targetAt = scheduledStartAt - 60 minutes
```

An eligible email requires all of the following persisted preference values at
the delivery decision:

- global notifications enabled;
- the `EMAIL` channel enabled;
- the appointment/message content group enabled; and
- a dedicated `appointmentRemindersEnabled` email preference enabled.

The dedicated preference defaults to `false`. Email cadence does not move this
transactional reminder into the daily or weekly digest. Disabling any required
preference before provider submission records a terminal preference-suppressed
outcome for that appointment/version; enabling it later does not resurrect that
intent. Preference changes made before its delivery decision apply to the
pending intent.

### Timezone and quiet hours

`scheduledStartAt` and `targetAt` are UTC instants. The persisted notification
IANA timezone is used only to render the appointment time and evaluate the
owner's local quiet window. The email shows the local date, local time, and IANA
timezone explicitly. Daylight-saving transitions are resolved by the timezone
database rather than a stored numeric offset.

The candidate delivery instant is `targetAt` during normal execution, or the
current server instant when confirmation or scheduler recovery occurs later.
When quiet hours are disabled or that candidate is outside the local quiet
window, the reminder is due at the candidate. When the candidate is inside
quiet hours, the reminder is deferred to the next local quiet-hour end only if
that instant is strictly before `scheduledStartAt`. Otherwise the intent
receives a terminal quiet-hours-suppressed outcome. A timezone or quiet-window
change before provider submission is re-evaluated from the current persisted
preference. The reminder is never moved earlier, never sent inside quiet hours,
and never sent at or after appointment start.

An absent or invalid timezone is not guessed. It produces a safe preference
failure and no provider submission until corrected before a still-pending
delivery decision.

### Lifecycle, deduplication, and races

Content/Notification persists no more than one intent for the identity:

```text
(recipientUserId, appointmentId, confirmedAppointmentVersion)
```

Duplicate events, consumer retries, scheduler overlap, and provider retries
reuse that identity. A deterministic provider idempotency key derived from the
same tuple is reused for every provider attempt. An already delivered identity
is never submitted again, including during event replay.

Only an exact `CONFIRMED` appointment/version creates a sendable intent.
`REJECTED`, `EXPIRED`, `CANCELLED`, `IN_PROGRESS`, `SESSION_ENDED`,
`COMPLETED`, no-show, dispute, and every other non-confirmed or terminal state
invalidate any undelivered intent for the older version. Invalidation and
delivery claiming use conditional version/state updates so a stale scheduler
claim cannot turn an invalidated intent back into a sendable one.

MB-380 reschedule-as-new never mutates or reuses the old reminder identity. It
invalidates the original appointment intent. The linked replacement may create
one new intent only after that new appointment reaches its own exact
`CONFIRMED` version. A replacement that remains requested, is rejected, expires,
or is cancelled produces no reminder.

If confirmation or scheduler execution occurs after `targetAt` but strictly
before appointment start, the reminder is due immediately, subject to the
current preference and quiet-hour rules. At `scheduledStartAt` or later it
expires without provider submission. There is no after-start grace period.

### Provider failure and audit state

Provider rejection, timeout, or transport failure never marks the reminder as
delivered. Delivery uses at most three provider submissions including the first
attempt, with bounded backoff and the same provider idempotency key. The current
Brevo transactional-email contract documents a 15-minute idempotency-key TTL;
therefore an outcome that might already have been accepted may be retried only
with the same key inside that TTL. After the TTL, an unknown outcome is recorded
as terminal rather than risking a duplicate submission. An explicit transient
non-acceptance may be retried only after a fresh appointment, preference, and
quiet-hour check and only while the next attempt remains strictly before
appointment start. Exhaustion or arrival at appointment start records a
terminal failed or expired outcome; it does not roll into a digest or another
reminder. MB-548 must verify the provider header and TTL against the
[official Brevo contract](https://developers.brevo.com/changelog/2021/11/10)
and keep them adapter-owned rather than treating them as appointment policy.

Audit state may retain the reminder identity, policy version, calculated
target/due instants, safe lifecycle state, attempt count, timestamps, coarse
failure category, and provider message identifier. Logs, metrics, events, and
audit state do not store the address, rendered email body, raw provider
response, health data, Journal data, assessment data, `ConsultationBrief`, or
chat content.

### Approved email and actor flow

The deterministic template contains only:

- a generic MentalBridge appointment-reminder subject;
- the appointment's local date and time with its IANA timezone;
- `IN_APP_CHAT` or `IN_APP_VIDEO` expressed as an in-app modality; and
- one MentalBridge application link to the appointment entry surface.

The link is an application-owned HTTPS URL containing only the opaque
appointment ID. Opening it requires normal authentication and current
appointment authorization. It is not a bearer link, provider room credential,
external meeting URL, or proof that video runtime is available. The email does
not contain a specialist name, health reason, Journal text, assessment answer
or score, `ConsultationBrief`, session summary, chat excerpt, safety signal, or
marketing/digest content. The adapter sets Brevo's per-recipient
`contactPixelTrackingConsent` to `false`; enabling Brevo's per-contact consent
feature with unknown-contact tracking disabled is a deployment gate because the
provider otherwise documents that this request field can be ignored. Safe
delivery, rejection, and bounce evidence is correlated through the provider
message identifier without persisting the address, link, or rendered body in
logs or audit state.

The actor flow is:

1. The user explicitly enables email, appointment messages, and the dedicated
   appointment-reminder preference and saves a valid timezone/quiet window.
2. The assigned specialist confirms the real appointment under MB-379.
3. Consultation publishes the minimized versioned fact; Content/Notification
   persists the single intent and deterministic due time.
4. Cancellation or reschedule under MB-380 invalidates the old intent. A linked
   replacement starts a separate flow only after its own confirmation.
5. At the due time, Content/Notification rechecks current owner truth,
   preference, quiet hours, verified address, and the strict before-start
   boundary, then submits the safe template once.
6. The user follows the link into the authenticated in-app appointment surface.
   The UI describes this as one reminder and never implies recurrence.

No appointment reminder is created by a safety signal. No failure in this
email flow changes appointment, safety, credit, chat/video, or digest state.

## Consequences

- MB-548 must add the versioned Consultation event/current-state boundary and
  Content-owned intent, delivery, provider, and audit behavior without sharing
  databases.
- MB-549 must expose the dedicated opt-in and existing timezone/quiet-hour
  values, save/error states, and non-recurring copy.
- MB-550 must prove duplicate/retry, reorder/version, reschedule race,
  invalidation, quiet-hour, timezone, provider-failure, and strict late-cutoff
  behavior with synthetic non-sensitive data.
- The appointment email remains operationally and semantically separate from
  MB-514 wellbeing digest production and identity.

## Rejected alternatives

- A configurable user-selected offset: rejected because this slice approves
  one deterministic reminder, not a recurring reminder product.
- Moving a quiet-hours reminder earlier: rejected because it would no longer be
  the approved one-hour reminder and could arrive many hours early.
- Sending at quiet-hour end after the appointment starts: rejected because a
  reminder after start has no approved purpose.
- Deduplicating by appointment ID alone: rejected because a confirmed
  replacement/version needs a distinct identity while stale versions must be
  invalidated explicitly.
- Carrying an email address or sensitive appointment context in Kafka: rejected
  because mutable delivery routing and clinical context are unnecessary for
  scheduling.
- Treating provider timeout as a new reminder: rejected because unknown
  provider outcomes require the same idempotency identity.
- Including the reminder in a wellbeing digest or deriving it from safety
  state: rejected because both change the approved product and privacy boundary.
