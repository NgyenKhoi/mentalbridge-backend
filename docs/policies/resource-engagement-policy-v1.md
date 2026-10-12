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

1. Resource Journey materializes one stable board per owner and local Monday,
   using the authoritative active SupportPlan snapshot and IANA timezone.
2. Each item retains its exact publication version. Catalogue updates do not
   silently replace historical board content.
3. `self_confirmed_at` is an optional owner statement. It can be set or cleared
   and never creates completion, practice, reward, streak, safety, clinical, or
   SupportPlan evidence.
4. `CONTENT_VERIFIED` is computed at read time only when an exact-version fact
   exists for the same owner, week, and SupportPlan: a completed assigned daily
   progress row, or a plan-bound practice session with a start instant and a
   measured duration from 60 through 7200 seconds.
5. Verification is displayed separately from self-confirmation. If both exist,
   verification is the evidence label while self-confirmation remains undoable.
6. A strong ETag is required for changes. Identical retries succeed; stale
   state-changing commands fail closed with `412`.
7. Missing weeks return `404`; dependency outages return `503`. Clients expose
   retry and do not present local or stale state as current truth.

Participation is optional. Skipped and unchecked items have no penalty, and a
full board has no clinical or treatment meaning.

