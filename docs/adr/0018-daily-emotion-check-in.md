# ADR 0018: One daily self-reported emotion check-in

- Status: Accepted for MB-510 implementation
- Date: 2026-09-16
- Decision ID: `MB-DAILY-EMOTION-CHECK-IN-001`
- Owner: Journal/AI
- Consent owner: Care
- Policy: [Daily emotion check-in policy v1](../policies/daily-emotion-check-in-policy-v1.md)

## Context

The dashboard contains a static mood selector and fabricated progress data. MB-510
requires a small persisted reflection record without turning it into a clinical
score, safety signal, diagnosis, recovery measure, or automatic reminder input.
The decision must precede implementation because allowed values, local-day
identity, edit/deletion semantics, optional free text, and downstream exposure
affect the durable aggregate.

## Decision

Journal/AI owns `EmotionCheckIn` in MongoDB. One active aggregate is identified
by `(ownerAccountId, localDate)`. `localDate` is accepted only when it equals the
server acceptance instant rendered in the submitted IANA timezone. The timezone
is immutable for that aggregate. This permits travel to produce adjacent local
days while preventing arbitrary backdating.

The allowed emotion labels reuse the existing reviewed Journal set:
`GREAT`, `GOOD`, `OKAY`, `LOW`, and `VERY_LOW`. `intensity` is an integer from
one through five describing only the strength of the selected feeling. It is not
a wellness score and must not be averaged or presented as severity, diagnosis,
safety, improvement, or recovery.

An optional non-blank note of at most 500 characters is included. Emotion,
intensity, and note are encrypted together with AES-256-GCM. None is indexed,
logged, or emitted to Kafka. Owner responses may decrypt the note after current
authorization; the AI consumer projection never includes it.

Create, update, and delete require hashed idempotency evidence. Updates require
`If-Match-Revision`, append an immutable encrypted revision, and are accepted
only while the original timezone still renders the stored local day as current.
The aggregate has at most 32 revisions. A stale concurrent writer receives
`412`; conflicting key reuse or revision exhaustion receives `409`.

Deletion is final for that local day. It atomically removes all encrypted
revisions and leaves a minimized tombstone containing no emotion, intensity, or
note. Tombstones expire after 30 days. Active check-ins remain until explicit
owner/account deletion; backups follow the platform expiry and deletion replay
process.

History is owner-scoped, newest-first, and labels all records
`SELF_REPORTED_EMOTION` with `NOT_DIAGNOSIS_OR_RECOVERY`. Cross-owner reads use
opaque `404` behavior.

The only implemented downstream projection is `AI_REFLECTION`. It forwards the
verified end-user bearer to Care and requires current `AI_PROCESSING` consent.
It returns only local day, timezone, emotion, intensity, revision, recorded time,
and the self-reported label. Missing/revoked consent fails closed with `403`;
Care failure fails closed with `503`. Reminder composition receives no check-in
data because no approved reminder-consent authorization contract exists. Adding
one requires a new reviewed contract/policy rather than reusing AI consent.

No Kafka event is produced: synchronous owner CRUD and consented local REST
projection are sufficient for this slice.

## Consequences

- The dashboard can reload persisted loading, empty, saved, updated, validation,
  conflict, and dependency-failure states.
- History is reflective evidence only; no streak pressure or clinical trend is
  derived.
- A user cannot recreate a check-in for a deleted local day until its tombstone
  expires; this preserves safe delete replay and prevents a delayed retry from
  deleting replacement data.
- Reminder integration remains deliberately unavailable instead of silently
  broadening consent.

## Rejected alternatives

- Reusing journal entries: rejected because a daily check-in has a separate
  one-per-local-day identity and must not require free text.
- Client-only local storage: rejected because reload, ownership, deletion, and
  concurrency would not be authoritative.
- UTC-day uniqueness: rejected because the product requirement is the user's
  local day.
- Emitting check-ins to Kafka: rejected because raw optional text is prohibited
  and there is no asynchronous consumer requirement in this story.
- Treating intensity as progress: rejected because intensity measures strength,
  not positive/negative direction or recovery.
