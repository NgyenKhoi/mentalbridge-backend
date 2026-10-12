# ADR 0036: Authoritative non-punitive resource rewards

- Status: Accepted for MB-659 implementation
- Date: 2026-10-12
- Decision ID: `MB-RESOURCE-REWARD-001`
- Owner: Content/Notification
- Policy: [Resource reward policy v1](../policies/resource-reward-policy-v1.md)

## Context

The Resources presentation previously had no authoritative basis for XP, levels,
or reward history. Views, client retries, and local UI state cannot prove that a
person completed reviewed content or a sufficiently evidenced practice session.
The feature also must not turn voluntary self-care participation into adherence,
recovery, treatment, or emotional-improvement scoring.

## Decision

Content/Notification calculates an owner-scoped read model from its persisted
resource facts. It does not create a second XP ledger. Eligible completion facts
come from terminal `resource_daily_progress` rows joined to the exact immutable
`resource_publication_snapshot`. Eligible practice facts additionally require a
plan-bound `resource_practice_session` with a start instant and 60–7200 measured
seconds. Only the earliest eligible session for a resource and local date counts.

The read model applies the versioned deterministic policy effective on each
fact's local date. A grant identity hashes kind, resource, content version, local
date, and rule version. The API returns total XP, display level, next threshold,
current policy version, source availability, and the ten most recent grants with
source provenance. Missing source access fails with 503; it is never converted
to zero. Source correction or deletion recomputes the result. Published resource
withdrawal keeps legitimate historical evidence understandable through the
immutable publication snapshot.

Only the owner's optional `VISIBLE` or `HIDDEN` preference is persisted. Hiding
rewards never restricts the Resources catalogue, saved progress, Support Guide,
SupportPlan, safety support, or professional support. XP is recognition of saved
participation and has no penalty, expiry, leaderboard, rank, clinical meaning,
or entitlement effect.

## Consequences

- Retry and concurrent reads cannot create awards because reads do not write XP.
- Same-day duplicate practice sessions collapse to one policy fact.
- Completed progress freezes its exact resource version even if later checklist
  edits occur after a resource version changes.
- Historical facts retain the policy effective on their local date when a later
  rule is introduced; policy tests cover mixed-version calculation.
- The API deliberately exposes no owner identifier because JWT ownership is the
  authorization boundary.

## Rejected alternatives

- A materialized XP ledger was rejected because it duplicates Content facts and
  requires correction, rollback, and race-safe award writes without improving
  the current query volume.
- Client-calculated XP was rejected because it permits phantom awards and cannot
  prove reviewed content, exact version, ownership, or persisted completion.
- Using streaks, views, mood, or SupportPlan occurrences was rejected because
  they have different authorities and meanings.
