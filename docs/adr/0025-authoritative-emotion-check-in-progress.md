# ADR 0025: Authoritative factual emotion check-in progress

- Status: Accepted for MB-567 implementation
- Date: 2026-09-27
- Decision ID: `MB-EMOTION-CHECK-IN-PROGRESS-001`
- Owner: Journal/AI
- Extends: [ADR 0018](0018-daily-emotion-check-in.md), Decision ID `MB-DAILY-EMOTION-CHECK-IN-001`

## Context

The daily emotion check-in aggregate already owns one active record per owner
and local date. MB-567 adds self-tracking history, factual streak continuity,
and rolling 7-, 14-, and 30-day coverage. Calculating those facts in a browser
would create competing definitions and would prevent notification producers
from consuming the same authoritative result.

## Decision

Journal/AI owns the progress calculation as a read model over active
`EmotionCheckIn` aggregates. No additional progress state is persisted.

The caller supplies a valid current IANA timezone. The service derives
`asOfLocalDate` from the server clock in that timezone. Stored aggregate dates
retain the immutable timezone accepted when each check-in was created. One
active local date counts once regardless of revision count. Deleted dates do
not count.

`currentStreak` is the number of consecutive active local dates ending on
`asOfLocalDate` when it has a check-in, or ending on the immediately preceding
date while the current local day is still open. A missing completed local day
breaks the run. `longestStreak` is the longest consecutive run across all
active dates no later than `asOfLocalDate`. Deleted dates break a run.

Each 7-, 14-, and 30-day window is inclusive of `asOfLocalDate` and returns
only checked-in-day count, total day count, and counts for the five existing
self-reported emotion labels. The service does not average intensity, rank
emotions, infer direction, or create a clinical, adherence, improvement, or
recovery score.

Owner history remains newest-first and cursor-paginated through the existing
endpoint. The progress endpoint is owner-scoped and note-free. Future reminder
or milestone producers may consume the authoritative result with an approved
end-user authorization flow; they must not duplicate the algorithm or receive
raw notes.

## Consequences

- Same-day edits can change the current emotion distribution but cannot add a
  checked-in day or increment a streak.
- Deletion immediately changes coverage and can shorten or break streaks
  because the read model is recomputed from active aggregates.
- Empty and sparse windows return truthful zero/count values without a trend
  claim.
- Journal activity remains a separate aggregate and never contributes to an
  emotion streak.
- The earlier prohibition on streak pressure remains: the UI may state factual
  counts but must not reward, shame, or imply treatment adherence.

## Rejected alternatives

- Browser-only calculation: rejected because it creates contract drift and is
  unusable by authorized server-side consumers.
- Persisted streak counters: rejected because same-day revisions, deletion,
  and timezone correction would require fragile repair logic.
- Intensity averages or positive/negative trend labels: rejected because the
  source is self-reported reflection, not a clinical or recovery measure.
