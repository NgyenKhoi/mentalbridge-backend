# Daily emotion check-in policy v1

| Field | Value |
| --- | --- |
| Policy ID | `MB-DAILY-EMOTION-CHECK-IN-001` |
| Status | Implemented for MB-510 controlled development/demo use |
| Aggregate owner | Journal/AI |
| Consent owner | Care |
| Decision | [ADR 0018](../adr/0018-daily-emotion-check-in.md) |
| Progress extension | [ADR 0025](../adr/0025-authoritative-emotion-check-in-progress.md) |

## Vocabulary and claims

- Emotion is exactly `GREAT`, `GOOD`, `OKAY`, `LOW`, or `VERY_LOW`.
- Intensity is an independent self-reported strength from 1 through 5.
- Owner history is labelled `SELF_REPORTED_EMOTION` and
  `NOT_DIAGNOSIS_OR_RECOVERY`.
- No value is a clinical score, safety classifier, severity, improvement,
  recovery, or treatment outcome.
- UI copy may state authoritative factual streak and coverage counts under ADR
  0025. It must not reward or shame streaks, infer positive/negative trends
  from intensity, or describe adherence, improvement, or recovery.

## Factual progress

- One active local date is one checked-in day; same-day revisions never add a
  day.
- The current streak ends on the caller's server-derived current local date,
  or the immediately preceding date while the current day is still open. A
  missing completed date or a deleted date breaks the run.
- The longest streak is recomputed from active emotion check-in dates only.
  Journal dates are an independent signal.
- Rolling 7-, 14-, and 30-day windows expose checked-in-day coverage and label
  counts only. Empty and sparse data remain explicit.
- The authoritative calculation belongs to Journal/AI and is not reimplemented
  in browser state or a notification producer.

## Local day and editing

- Create accepts only the current calendar day in the supplied valid IANA zone.
- One active/tombstoned aggregate exists per owner and local day.
- The creation timezone is immutable.
- Update is allowed only during that stored local day, requires the current
  revision, and appends an encrypted revision.
- Exact retries replay; conflicting key reuse fails; one of two concurrent
  writers wins.

## Privacy, retention, and consumers

- The optional note is non-blank and at most 500 characters.
- Emotion, intensity, and note share an encrypted envelope and are never logged,
  indexed, or emitted in an event.
- Active records remain until owner/account deletion. Direct deletion erases
  encrypted revisions immediately and retains a minimized 30-day tombstone.
- AI reflection receives a note-free minimized projection only under current
  `AI_PROCESSING` consent. Consent withdrawal blocks future reads.
- Reminder composition receives no data until a distinct authorization and
  consent decision is accepted.
