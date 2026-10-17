# ADR 0020: Persist SupportPlan activity occurrences in Care

- Status: Accepted
- Date: 2026-09-21
- Decision ID: `MB-SUPPORT-PLAN-ACTIVITY-SCHEDULE-001`
- Story: MB-513

## Context

An active SupportPlan previously preserved exact selected resources but did not
provide explicit dated activity instances. A browser-derived calendar would
duplicate work on reload, lose source versions, and make pause, replacement,
timezone, and concurrent retry behavior ambiguous.

## Decision

Care owns immutable-versioned activity schedules and persisted occurrences.
Activation snapshots each selected resource, the SupportPlan version, local
time, and profile IANA timezone. Care generates a rolling 14-day horizon under
`support-plan-activity-schedule-v1`; callers may read at most 31 inclusive local
days within the supported recent/today/upcoming range.

The logical uniqueness key is `(schedule_id, schedule_version, local_date)` and
the occurrence UUID is deterministically derived from that key. PostgreSQL
uniqueness remains the final concurrency guard. DST gaps advance to the first
valid local instant and overlaps use the earlier offset.

Pause/resume, complete, replacement, and draft discard are Care commands with
optimistic concurrency. Future open occurrences are cancelled or restored only
according to their explicit reason; a terminal command relabels still-future
pause cancellations so they cannot be mistaken for resumable work. Completion and skip are user-reported
wellbeing facts, not adherence, diagnosis, treatment outcome, or recovery.
`MISSED` is computed for display and is not a stored judgment.

The contract reserves distinct `JOURNAL_PROMPT` and
`EMOTION_CHECK_IN_PROMPT` source types with null resource/slot fields, but
MB-513 generates only `RESOURCE` occurrences. This preserves separation from
Journal/AI-owned entries and emotion check-ins. AI has no lifecycle or
completion authority.

## Consequences

- Reloads and safe retries return stable persisted identities and provenance.
- Historical occurrences remain attributable after a plan ends or is replaced.
- Content/Notification may later deliver reminders but never owns schedule or
  activity state.
- The Care migration, OpenAPI, data dictionary, UI, and tests must evolve
  together for any recurrence or lifecycle change.
