# ADR 0043: Plan-scoped resources and verified progress

- Status: Accepted for MB-670 implementation
- Date: 2026-10-21
- Decision ID: `MB-PLAN-RESOURCE-PROGRESS-001`
- Owners: Care and Content/Notification
- Amends: the RESOURCE completion rule in [ADR 0034](0034-support-plan-v3-cross-domain-actions.md) (`MB-SUPPORT-PLAN-V3-ACTIONS-001`) and the board materialization/evidence rules in [ADR 0037](0037-persistent-resource-engagement.md) (`MB-RESOURCE-ENGAGEMENT-001`)
- Policy: [Resource engagement policy v1](../policies/resource-engagement-policy-v1.md), MB-670 amendment

## Context

Resources and Bingo must reflect the user's current Care-owned SupportPlan.
Catalogue padding, client plan snapshots, and opening a resource cannot prove
that an occurrence was completed. Content already owns progress and practice
facts; Care needs only their bounded identity, version and time references.

## Decision

### Current plan projection

Care exposes `GET /api/v2/support-plans/current/resource-actions?localDate=YYYY-MM-DD`
to the authenticated USER. It returns `ACTIVE`, `PAUSED`, or `NONE`, with only
active RESOURCE actions in plan order. The response includes exact resource and
publication references, schedule, and today's occurrence state/evidence. It
excludes rationale, treatment context and other action types. An absent current
plan is a successful `NONE` response with no actions.

The existing v2 read adapter supplies stable virtual RESOURCE actions marked
`V2_RESOURCE_ADAPTER`, with `today: null`. It creates no native action rows and
does not support native occurrence reconciliation. Entitlement remains the
existing client gate in this story; server enforcement on plan reads is a
separate MB-665/backend follow-up.

### Plan-scoped Bingo

Content calls the Care projection with the owner's bearer and correlation ID.
Only `PUT /api/v1/resource-bingo/{weekStart}/plan-sync` creates or reconciles a
board, for the current local week. Care errors return `503` before changing any
board. Resource Journey reads the latest board or an empty item list and never
creates or changes it.

A board is unique by owner, Monday and SupportPlan ID. A plan switch creates a
separate board; the previous plan's board remains history. Reads select the
most recently synced board of that week. `NONE` retires the latest board's
items. Past weeks cannot be resynced.

Tiles come only from plan RESOURCE actions, in plan order on initial sync,
with at most nine retained rows and no catalogue padding. The existing unique
resource-per-board rule remains: two actions on one resource share a tile.
Item identity uses action ID, resource ID and exact resource version, excluding
action version so an adapter plan-version change cannot churn items.

Removed actions retire their items in place, retaining ordinal and owner
self-confirmation. Content facts remain in their source tables and verification
is computed on read. Retired rows still occupy capacity.
During rollout, legacy catalogue rows without a plan action or personal check
are removed when absent from the current plan; checked legacy rows retire and
keep their ordinal/history. Matching legacy resources rebind in place.
New actions take free ordinals or are reported as `BOARD_FULL`. A returning
resource is rebound in place and unretired; changing its exact version clears
self-confirmation. Versions without a listed publication snapshot are skipped
and reported as `RESOURCE_VERSION_UNAVAILABLE`. An identical projection does
not advance the board's concurrency version. Empty boards are valid.

Numeric board versions are allocated uniquely within an owner/week under the
existing advisory lock. This prevents an old plan's `If-Match` from changing a
new plan's same-position tile. A delayed older projection for an already synced
plan cannot replace its newer stored projection. Care reads finish before the
Content transaction; plan switches retain request-snapshot consistency rather
than a distributed transaction across the two owners.

Retired items reject self-confirmation with `409`. Owner self-confirmation
remains independently reversible under a strong ETag, and never creates a
Care occurrence or any Content completion fact.

### Exact Content evidence

Progress saves may pin `resourceVersion`; it must identify a listed immutable
publication snapshot, otherwise the save returns `409`. Omitting it preserves
the current-version behavior. Both progress and practice retain the version
actually used. A newer publication cannot satisfy an older pinned action.
A supplied version differing from an already completed daily row returns
`409 RESOURCE_VERSION_UNAVAILABLE` before writing. The daily row keeps its
original completion version/time. This also limits a second-version practice
save through this daily progress endpoint on the same day; a later local day
has its own progress row.

Content exposes owner-only `GET /api/v1/resource-completion-evidence` with
resource ID, exact version, local date and IANA timezone. It returns the newest
qualifying completed daily progress or measured practice session, `404` when
none exists, and an explicit unavailable error for dependency failure.
Candidates sort by server completion time. Care supplies optional repeated
`excludedFactIds` for facts already used for that owner/resource/revision;
the acceptance uniqueness constraint remains authoritative under concurrency.
Learning-completion rows are not evidence for this flow.

For `RESOURCE_PRACTICE_SESSION`, the fact ID is the practice session ID and its
occurrence instant is server `completed_at` minus `duration_seconds`; duration
must be 60 through 7200 seconds. This derivation also applies to historical
sessions whose `started_at` was client supplied.
For `RESOURCE_COMPLETION`, the fact ID is `<resourceId>:<localDate>` for the
completed daily progress row and its occurrence instant is `completed_at`.
The server ignores deprecated request `practiceStartedAt`, preserving the
request shape without trusting a supplied start date. The fact revision is the resource
version. A client's local date never proves
when work happened: the server fact instant converted into the requested zone
must fall on that date. Action-bound Bingo uses the same owner/exact-version
facts inside the board week and zone; legacy items retain assignment binding.

### Care reconciliation

`POST /api/v2/support-plan-occurrences/{occurrenceId}/resource/reconcile`
requires `If-Match` and an owned native RESOURCE occurrence in an ACTIVE plan.
Care queries Content outside its database transaction, then rechecks ownership,
plan state, exact source version, date in the occurrence's pinned timezone and concurrency
under the existing occurrence locking policy before recording completion.
The minimized projection exposes that zone as `today.timeZone`; top-level
`timeZone` remains the plan/week zone used for Bingo.

Found evidence completes the occurrence with `CONTENT` authority. Missing
evidence returns `409 RESOURCE_EVIDENCE_NOT_FOUND`; unavailable Content returns
`503`; stale state returns `412`. These failures leave the occurrence unchanged.
The unique fact index enforces one use of a fact revision per owner;
reusing it returns `409 RESOURCE_EVIDENCE_ALREADY_USED`. An accepted retry is
idempotent. Completed occurrences cannot be reopened through state replacement.
Explicit Care acknowledgement remains valid. Opening content, marking a Bingo
item, or client state never completes an occurrence.

Care stores only owner/kind/ID/revision/occurredAt/verifiedAt evidence metadata.
Content owns resource facts and Care owns occurrence state. Neither service
reads the other's database. No new broker, reward, ranking, streak, clinical
score or entitlement inference is introduced.

## Compatibility and migration

Care migration `031` extends the native RESOURCE evidence CHECK to allow the
two Content fact kinds while preserving the one-fact-one-occurrence index and
every existing authority/action constraint. The index also includes `user_id`:
the specified daily completion ID is resource/date within its owner, so a
global key would incorrectly collide across different users. Content migration
`27_plan_scoped_resource_bingo.sql` changes board uniqueness and adds nullable external action references and
retirement state.
Legacy boards retain their publication provenance and are reconciled on the
first current-week plan sync. Existing exact-version fact columns are reused.
Content adds `last_synced_at` to select the latest synced board independently
of its ETag version. If Care reports `NONE` and no board exists, plan-sync
returns an empty, non-persisted board shape with version `0`; it creates no
placeholder SupportPlan or durable board.

The endpoints and evidence kinds are additive. The existing Journey `bingo`
field remains required but becomes read-only. Daily Journey behavior on native
v3 plans, native mobile UI, and server entitlement checks on plan reads remain
named follow-ups. Participation and missed opportunities remain neutral.
