# Resource engagement policy v1

- Policy version: `resource-engagement-v1`
- Effective date: 2026-10-12
- Decision: `MB-RESOURCE-ENGAGEMENT-001`
- Owner: Content/Notification

## Bookmark rules

1. The verified JWT supplies the owner. Owner identifiers are never accepted in
   request bodies or paths.
2. Only the current published, reviewed, non-withdrawn resource version can be
   newly bookmarked.
3. Repeating a save for the same exact version is a no-op. Saving after a new
   reviewed publication replaces the bookmarked version and increments its
   version counter.
4. Removal is idempotent. Bookmark absence has no effect on access or progress.

## Weekly Bingo rules

1. Plan-sync materializes one board per owner, local Monday and SupportPlan,
   reading Care's minimized current RESOURCE-action projection and using an
   IANA timezone. Resource Journey only reads the latest board. See the dated
   MB-670 amendment below for retirement, capacity and plan-switch rules.
2. Each item retains its exact publication version. Catalogue updates do not
   silently replace historical board content.
3. `self_confirmed_at` is an optional owner statement. It can be set or cleared
   and never creates completion, practice, reward, streak, safety, clinical, or
   SupportPlan evidence.
4. `CONTENT_VERIFIED` is computed at read time only from an exact-version
   Content fact for the owner whose server occurrence instant falls inside the
   board week in its timezone: completed daily progress or measured practice
   from 60 through 7200 seconds. Action-bound items use this rule; legacy items
   additionally retain their daily-assignment/SupportPlan binding.
5. Verification is displayed separately from self-confirmation. If both exist,
   verification is the evidence label while self-confirmation remains undoable.
6. A strong ETag is required for changes. Identical retries succeed; stale
   state-changing commands fail closed with `412`.
7. Missing weeks return `404`; dependency outages return `503`. Clients expose
   retry and do not present local or stale state as current truth.
8. Legacy board items created before exact versions were stored are bound only
   to the latest immutable publication snapshot whose `published_at` is no later
   than the board `created_at`. If no such snapshot exists, the item is removed
   during migration instead of assigning a mutable or later resource version.
   The persisted board remains valid and may truthfully contain zero items.

Participation is optional. Skipped and unchecked items have no penalty, and a
full board has no clinical or treatment meaning.

## MB-670 amendment — 2026-10-21

Decision: [ADR 0043](../adr/0043-plan-scoped-resource-progress.md),
`MB-PLAN-RESOURCE-PROGRESS-001`. This section prospectively amends rules 1 and 4
and the corresponding materialization clause of ADR 0037.

- Care remains the plan/action authority. Content forwards the owner's bearer
  and correlation ID to the minimized plan read; it accepts no client plan
  snapshot for Bingo. A Care failure is `503` and changes no board. Only the
  current local week can be synced; past-week requests are `409`.
- Tiles are only current plan RESOURCE actions, initially in plan order,
  without catalogue padding. Nine retained rows is the capacity, including
  retired rows. Two actions for one resource share a tile. Item identity ignores
  action-version changes and uses action, resource and exact resource version.
- Removed actions retire their rows without deleting ordinal or personal check.
  Retired items remain visible and reject self-confirmation with `409`.
  Returning resources rebind in place; a version change clears the personal
  check. New resources use free ordinals or report `BOARD_FULL`. Unlisted exact
  publication versions report `RESOURCE_VERSION_UNAVAILABLE` and create no tile.
- A plan switch creates a separate board and preserves the old board as history.
  Week reads select the most recently synced board. Definite `NONE` retires all
  items on the latest board; an empty board is valid. With no board, `NONE`
  returns an empty nonpersisted board with version `0`, without inventing a plan.
  Identical syncs do not advance the board concurrency version.
- Progress saves pin the actually used publication version when provided;
  unlisted versions return `409`. Missing version retains the current-version
  behavior. A newer version cannot satisfy an older pinned action or tile.
- Evidence date uses server `completed_at` for daily progress or
  `completed_at - duration_seconds` for measured practice, including legacy
  sessions. Deprecated client `practiceStartedAt` is ignored. The instant is
  converted to the occurrence's pinned IANA zone for Care, or board zone for Bingo. Client
  `localDate` alone is never proof, including for backdated progress. A practice
  performed today cannot satisfy a past occurrence. Learning completion is not
  a fact source for Care reconciliation or action-bound Bingo.
- Care may reconcile an owned native RESOURCE occurrence in an ACTIVE plan to
  `COMPLETED` using exact Content evidence. It stores only the bounded fact
  reference; one fact revision can satisfy at most one occurrence. Missing
  evidence is `409`, unavailable Content is `503`, stale `If-Match` is `412`,
  and accepted retries are idempotent. Paused plans cannot reconcile. Completed
  occurrences cannot be reopened; explicit acknowledgement remains available.
- Self-confirmation remains an optional reversible owner statement under rule
  3. It never supplies the evidence used by this amendment. This change adds no
  reward, ranking, adherence, recovery, or clinical meaning.

