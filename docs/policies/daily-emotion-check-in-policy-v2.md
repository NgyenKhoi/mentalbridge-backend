# Daily emotion check-in policy v2

| Field | Value |
| --- | --- |
| Policy ID | `MB-EMOTION-CONTEXT-001` |
| Status | Implemented for MB-658 controlled development/demo use |
| Aggregate owner | Journal/AI |
| Decision | [ADR 0035](../adr/0035-optional-emotion-influence-context.md) |
| Base decision | `MB-DAILY-EMOTION-CHECK-IN-001` |

## Required and optional input

- Emotion and intensity remain required exactly as defined by policy v1.
- Influence factors and the private note are optional and may be cleared.
- Factor codes are exactly `sleep`, `work`, `study`, `family`, `friends`,
  `health`, and `other`; at most three distinct codes may be saved.
- A present note contains non-whitespace text and is at most 500 characters.
- The interface must not require an explanation or imply that choosing context
  improves care, safety, diagnosis, or recovery.

## Consent and presentation

- Opening the optional section does not save data. Explicit save of selected
  context is the consent action for owner-scoped retention.
- Before entry, web and mobile state that context is optional, private, and not
  automatically sent to AI, a support plan, or analytics.
- Localized labels are presentation only; stable codes are persisted.

## Privacy, retention, and minimization

- Emotion, intensity, factors, and note share the encrypted revision payload.
- None is indexed, logged, emitted as an event, or exposed in operational
  metrics. Logs may contain only request/correlation and bounded error metadata.
- Factors and note are absent from AI reflection, notification activity,
  SupportPlan, reassessment, analytics, and all other consumer projections.
- Active records and account deletion follow policy v1. Direct check-in delete
  erases every encrypted revision and keeps only the existing content-free
  30-day tombstone.
- Owner export/read responses include the current factors and note. Owner
  isolation and opaque cross-owner behavior remain unchanged.

## Compatibility

- Requests from older clients may omit factors and continue to succeed.
- Legacy encrypted payloads without factors are read as an empty array.
- The collection envelope, indexes, idempotency, optimistic revision, and
  local-day rules do not change, so no MongoDB rewrite is performed.
