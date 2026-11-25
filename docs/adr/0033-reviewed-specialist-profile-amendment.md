# Reviewed amendments to approved specialist profiles

- Decision ID: `MB-SPECIALIST-PROFILE-AMENDMENT-001`
- Status: Accepted product direction, implementation under MB-635
- Authority: owner's explicit request to implement MB-635, 2026-10-08
- Owner: Consultation

## Decision

An approved professional profile remains the authoritative public snapshot.
Its six public fields cannot be edited through the initial-submission PUT.
An approved specialist instead starts a private amendment derived from that
snapshot, saves changes, and explicitly submits them for a separate ADMIN review.

The normal profile lifecycle remains `PENDING -> APPROVED | REJECTED`,
`REJECTED -> PENDING`, `APPROVED -> SUSPENDED`, `SUSPENDED -> APPROVED`.
The amendment lifecycle is separate:

```text
APPROVED profile (unchanged, public and operational)
  -> amendment DRAFT -> PENDING_REVIEW -> APPROVED (promote public snapshot)
                         |                    |
                         -> REJECTED          -> next amendment DRAFT
                              -> edit REJECTED -> explicit resubmit PENDING_REVIEW
PENDING_REVIEW -> edit -> DRAFT (withdraw review)
DRAFT | PENDING_REVIEW | REJECTED -> cancel -> CANCELLED (terminal)
CANCELLED -> new amendment DRAFT from the current public snapshot
```

The owner's follow-up request on 2026-10-08 adds cancellation. It is an
ETag-protected owner command serialized with ADMIN review, not deletion or
local hiding. Public content, appointments and availability are unchanged.
The cancellation revision retains the proposed payload and actor/time in
append-only history. Current submission/review metadata is cleared; historical
review revisions remain intact. Cancelled amendments cannot be edited or
submitted again. Missing/stale version and suspension protections still apply.
Migration 019 expands both persisted state checks before enabling cancellation;
old strict-enum consumers must be upgraded together with the owner.

Suspension wins over amendment commands through the existing profile lock.
It does not destroy the private amendment; it blocks edits, submission and
decisions and removes pending amendments from the reviewable queue. Restoration
retains the same published content version and does not revive cancelled
appointments or withdrawn slots.

## Data and consistency

- `published_version` identifies approved content only. Initial approval and
  amendment promotion increment it; amendment edits and operational
  suspension/restoration do not.
- Approved-version history is append-only, including the initial approval and
  every promotion. Migration captures one baseline for already approved or
  suspended profiles using available approval audit provenance; it does not
  reconstruct unavailable historical content.
- At most one DRAFT/PENDING_REVIEW/REJECTED amendment exists per specialist.
  Each amendment references the approved content version from which it began.
- Every persisted amendment revision appends its exact six-field payload and
  bounded actor/state/reason/time provenance. Editing after rejection never
  overwrites the previously reviewed payload.
- Commands acquire the specialist profile lock before reading mutable amendment
  state. ADMIN resolves the owner using a scalar ID projection before locking,
  not a preloaded entity. This serializes promotion, edits, suspension and starts.
- Start checks the profile ETag. Other commands check the amendment ETag.
  Stale versions return 412; missing If-Match returns 428; incompatible state or
  changed published base returns stable 409. A repeated command at the current
  resulting version is a no-op when it already represents the requested outcome.
- Promotion updates the live profile and its language/support-area collections,
  increments its content/optimistic versions, decides the amendment and appends
  both histories in one local transaction. No appointment, credit, payout,
  availability or remote-service data is rewritten.

## Privacy and compatibility

Only the authenticated specialist reads/edits their own amendment; ADMIN can
review it. Discovery continues reading only the approved profile tables and
never joins private amendment state. Updated approved fields participate in the
normal authoritative discovery/availability checks after promotion.

Existing endpoints remain compatible. New endpoints are nested under the
existing role-protected specialist-profile and ADMIN specialist-profile paths.
The schema migration is additive and must run before the updated owner starts.
Old application instances must be drained before enabling amendment writes:
they do not record new approved-version history. Rollback must not delete audit
records; it disables amendment commands rather than reverting published facts.

No Kafka, outbox, Redis, external verification upload or second identity is added.

## Verification slices

1. Owner slice: additive contract, migration, JPA/API behavior, canonical model,
   dictionary and focused real-PostgreSQL lifecycle/concurrency/discovery tests.
2. Consumer slice: generated contract adoption, BFF authorization/validation,
   specialist draft/public comparison and separate ADMIN amendment queue/review.
3. Closure: focused compatibility checks, changed UI/browser states and evidence.

These are proposed commit groups; no Git write is authorized by implementation alone.
