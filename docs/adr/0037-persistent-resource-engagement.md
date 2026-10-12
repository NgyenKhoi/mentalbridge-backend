# ADR 0037: Persistent resource bookmarks and truthful Bingo evidence

- Status: Accepted for MB-660 implementation
- Date: 2026-10-12
- Decision ID: `MB-RESOURCE-ENGAGEMENT-001`
- Owner: Content/Notification
- Policy: [Resource engagement policy v1](../policies/resource-engagement-policy-v1.md)

## Context

Resource bookmarks and Bingo checks were previously presentation state. Browser
storage could not preserve them across devices, isolate owners reliably, or
distinguish a person's check from completion verified by Content facts. A cached
or copied check must not become evidence of practice, adherence, or improvement.

## Decision

Content/Notification persists bookmarks by authenticated owner, resource, and
the exact immutable publication version saved. Saving the same current version
is idempotent; saving after a reviewed publication change advances the bookmark
to that current version. Deletion is an idempotent undo.

The weekly board remains the plan-derived board materialized by the Resource
Journey boundary. Its Monday, IANA timezone, SupportPlan identity/version, item
order, and exact resource versions are retained. A Bingo item stores only the
owner's optional `self_confirmed_at`. The read model separately recomputes
Content-verified evidence from exact-version daily completion or an eligible
plan-bound practice session. Verified evidence takes presentation precedence,
while `selfConfirmedAt` remains independently reversible.

Board writes require a strong numeric ETag through `If-Match`. A retry that
already matches the requested state succeeds without another version increase.
A stale request that would change current state fails with `412`, requiring the
client to reload before retrying. Historical week boards remain addressable by
their original Monday and timezone. Resource publication withdrawal or later
versions do not rewrite an existing board; evidence must still match the exact
board version and current authoritative facts.

## Consequences

- Reloads, browsers, and devices observe one owner-scoped state.
- Self-confirmation can be undone and never creates progress, practice, rewards,
  streak, clinical, or SupportPlan occurrence evidence.
- Missing or unavailable dependencies remain explicit errors; clients must not
  replace them with browser state or stale successful responses.
- Skipping an item has no penalty and completing every board item has no special
  authority or nine-of-nine meaning.

## Rejected alternatives

- Browser storage was rejected because it is device-local and cannot provide an
  owner or concurrency boundary.
- Persisting a single `completed` flag was rejected because it would conflate a
  personal check with Content-owned evidence.
- Copying verified completion onto the board was rejected because later source
  correction could leave a false permanent mark.

