# Daily emotion check-in policy v1

| Field | Value |
| --- | --- |
| Policy ID | `MB-DAILY-EMOTION-CHECK-IN-001` |
| Status | Implemented for MB-510 controlled development/demo use |
| Aggregate owner | Journal/AI |
| Consent owner | Care |
| Decision | [ADR 0018](../adr/0018-daily-emotion-check-in.md) |

## Vocabulary and claims

- Emotion is exactly `GREAT`, `GOOD`, `OKAY`, `LOW`, or `VERY_LOW`.
- Intensity is an independent self-reported strength from 1 through 5.
- Owner history is labelled `SELF_REPORTED_EMOTION` and
  `NOT_DIAGNOSIS_OR_RECOVERY`.
- No value is a clinical score, safety classifier, severity, improvement,
  recovery, or treatment outcome.
- UI copy must not reward streaks or infer positive/negative trends from
  intensity.

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
