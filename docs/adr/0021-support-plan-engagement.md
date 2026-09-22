# ADR 0021: Keep SupportPlan engagement user-owned in Care

- Status: Accepted
- Date: 2026-09-22
- Decision ID: `MB-SUPPORT-PLAN-ENGAGEMENT-001`
- Story: MB-376

## Context

SupportPlan occurrences already preserve deterministic local-time and exact
resource provenance. Completion and skip were terminal user inputs, but the
workspace could not correct an input, hide an item, record structured
helpfulness or a barrier, save a bounded reflection, or erase that mutable
engagement record.

Engagement must remain distinct from adherence, recovery, clinical outcome,
and specialist monitoring. Reassessment composition belongs to MB-386, but it
needs a reproducible Care-owned engagement dimension rather than browser state.

## Decision

Care owns engagement on the occurrence that owns its schedule and exact source
versions. The occurrence optimistic version guards a complete desired-state
replacement containing state, visibility, structured helpfulness or barrier,
an optional reflection of at most 500 characters, and explicit approval for
later bounded-summary reuse.

`SCHEDULED`, `COMPLETED`, and `SKIPPED` remain the engagement states.
Reopening means replacing `COMPLETED` or `SKIPPED` with `SCHEDULED`, which
clears helpfulness, barrier, reflection, and reuse approval. Helpfulness is
valid only for completion, while a barrier is valid only for skip. Hiding is a
separate reversible display preference and never changes completion state.

Only an occurrence in the authenticated user's `ACTIVE` current plan accepts
an engagement write. Repeating the complete desired state is a no-op even when
the supplied version predates that same result. A different stale request
fails with the stable occurrence version error. Deleting engagement clears the
mutable self-report and unhides the occurrence while retaining immutable
schedule and source provenance.

Every material change writes a minimized outbox fact in the same Care
transaction. It contains identifiers, exact source versions, structured state,
visibility, helpfulness, barrier, reuse approval, and timestamps. It excludes
reflection text. There is no specialist checklist endpoint. A future bounded
summary may use only records with explicit reuse approval and must obtain its
own scoped sharing authorization.

For controlled synthetic/demo data, the engagement record follows its owned
occurrence until the user deletes the mutable record or account-deletion
coordination removes owned data. This is not an indefinite production
retention claim; public real-user retention, backup expiry, and deletion SLA
remain blocked on the existing production privacy/legal review.

## Consequences

- Reload returns authoritative user-owned engagement with exact provenance.
- Reassessment can consume engagement and reflection as separate dimensions;
  MB-376 creates no combined score and MB-386 owns final composition.
- Notification consumers can react to minimized state without receiving free
  text.
- Terminal or paused plans reject engagement mutation, preserving lifecycle
  truth while current reads remain available.
