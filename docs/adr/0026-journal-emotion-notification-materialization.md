# ADR 0026: Journal and emotion notification materialization

- Status: Accepted
- Date: 2026-09-27
- Decision ID: `MB-WELLBEING-NOTIFICATION-001`
- Owner: Content/Notification
- Activity owner: Journal/AI
- Amends: [ADR 0017](0017-product-scope-v2.md), [ADR 0018](0018-daily-emotion-check-in.md), and [ADR 0025](0025-authoritative-emotion-check-in-progress.md)

## Context

MB-564 requires real in-app Journal and daily emotion reminders plus factual
streak milestones. Content/Notification already owns persisted preferences,
quiet hours, inbox state, and producer deduplication. Journal/AI owns the only
authoritative Journal and emotion activity records. The browser must not
calculate or persist streak state, and no integration may expose Journal text
or emotion note text.

The current delivery slice has no approved background email flow or mobile
device registration. In-app reminders still need to be produced without the
owner opening the inbox, so the integration requires a narrowly scoped service
credential rather than retained or replayed end-user access tokens.

## Decision

Journal/AI exposes one service-authorized notification activity projection for
an explicit owner and timezone. It returns only the current local date,
timezone, and independent `completedToday`, `currentStreak`, and
`longestStreak` facts for Journal and emotion check-ins. It never returns entry
text, note text, mood, intensity, or an AI interpretation. The endpoint accepts
only the dedicated reminder service token; it does not grant general Journal
or emotion access.

Content/Notification periodically pages persisted preference aggregates for
owners with in-app Journal, emotion, or milestone notifications enabled. For
each owner it applies the shared channel/content-group and quiet-hour policy,
obtains the minimized activity projection with the scoped service credential,
and materializes due inbox records. One owner's Journal/AI failure is isolated
and retried by a later scheduler run; it cannot make existing inbox history or
other owners' reminder production unavailable. Reading the inbox has no
producer side effect.

Identity publishes its existing `identity.account.registered` contract from the
transactional outbox to `mentalbridge.identity.account-lifecycle.v1`.
Content/Notification consumes registered `USER` events and idempotently creates
the default preference aggregate before the owner first opens notification
settings or the inbox. Specialist registrations are ignored. The lazy
`getOrCreate()` API behavior remains only as a compatibility repair path for
pre-existing accounts and delayed/dead-letter recovery.

The implemented kinds are:

- `JOURNAL_REMINDER`
- `EMOTION_CHECKIN_REMINDER`
- `JOURNAL_STREAK_MILESTONE`
- `EMOTION_STREAK_MILESTONE`

Each producer identity is `<kind>:<local-date>` under source
`JOURNAL_AI_ACTIVITY`. The existing database uniqueness on recipient, source,
and source identity makes concurrent or repeated evaluation idempotent.

Journal activity dates are derived from active Journal `occurredAt` instants in
the persisted notification timezone. Emotion activity uses the aggregate's
frozen `localDate` and the same shared calculator used by the authoritative
MB-567 progress read model; same-day revisions remain one activity day.
Deleted or missing local days are absent and therefore break continuity. An
open current day does not break a streak ending yesterday.

Factual milestones are emitted at 7, 14, and 30 consecutive days, matching the
approved MB-567 progress windows. A milestone is emitted only when that domain
has activity on the current local day. Journal and emotion activity never
contribute to one another.

ADR 0017's single daily wellbeing digest remains the rule for a future email
digest. The in-app inbox uses the four distinct kinds above so users can apply
the independent preferences and understand the source of each factual item.
Email consumption and mobile push delivery remain unavailable in this slice.

## Consequences

- Notification production is recomputable from owner data and database
  deduplication; browser state is not authoritative.
- Quiet hours remain one Content/Notification policy rather than producer
  logic in Journal/AI.
- In-app materialization is driven by the Content/Notification scheduler over
  persisted preferences and does not depend on inbox navigation.
- Newly registered users enter the scheduler candidate source through durable
  account lifecycle delivery instead of requiring a prior preference API read.
- The reminder service credential is injected at deployment, redacted from
  logs, and authorizes only the minimized Journal/AI projection.
- Copy is reviewed deterministic Vietnamese text. AI does not decide timing,
  eligibility, kind, threshold, or suppression.
- Existing historical generic notification kinds remain readable.

## Rejected alternatives

- Browser-calculated streaks: rejected because deletion, timezone boundaries,
  retries, and same-day edits would drift from owner truth.
- Content reading Journal MongoDB: rejected because it violates service data
  ownership.
- Inbox-read materialization: rejected because a reminder that appears only
  after the owner opens the inbox cannot prompt the intended activity and may
  be skipped permanently when the activity is completed first.
- Persisting user bearer tokens for a background scheduler: rejected because a
  notification worker must not retain or impersonate end-user sessions.
- Sending raw Journal or emotion content in an event: rejected because dates
  and factual counts are sufficient for the approved policy.
