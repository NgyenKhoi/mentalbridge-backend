# MB-379 appointment decision evidence

## Delivered boundary

Consultation Service owns the decision lifecycle for the exact-slot appointment
request created by MB-378. The assigned specialist can accept or reject only a
`REQUESTED` appointment before its persisted deadline. Acceptance changes the
appointment to `CONFIRMED` while its consultation credit remains `HELD`.
Rejection changes it to `REJECTED`, releases the exact held credit, and frees
the slot for a new request.

The deadline is persisted as the earlier of 24 hours after the request and two
hours before the appointment. A server-side scheduler expires unanswered
requests to `EXPIRED`; expiry releases the same held credit without requiring a
browser session. Decisions record their time, reason, actor where applicable,
and append-only history.

## Consistency and authorization

- Specialist decision endpoints require the `SPECIALIST` role and verify that
  the authenticated account is the assigned specialist.
- `If-Match` protects the appointment version and an idempotency key makes an
  exact replay stable.
- Appointment and credit rows are locked in one transaction. Acceptance,
  rejection, expiry, history, and credit-ledger effects therefore settle once.
- Terminal decisions cannot be changed by a later command. A decision racing
  with expiry resolves to one terminal state without a second credit release.
- The appointment response exposes the authoritative appointment status,
  decision reason, credit state, and aggregate version for both specialist and
  user reloads.

## Contract and persistence

OpenAPI publishes specialist listing plus accept/reject operations, required
optimistic-concurrency and idempotency headers, terminal states, decision
metadata, and credit outcome. Liquibase change `008-appointment-decisions.sql`
adds the decision fields, history command key, state constraint, and due-request
index while preserving existing MB-360 and MB-378 records.

## Verification

On 2026-09-26 the Consultation Service full suite passed locally against
PostgreSQL Testcontainers with synthetic accounts, slots, appointments, and
credits:

```text
.\mvnw.cmd -q test
Tests run: 47, Failures: 0, Errors: 0, Skipped: 0

powershell -ExecutionPolicy Bypass -File scripts/verify-repository.ps1 -BaseSha origin/dev
Repository policy passed; paired-change policy passed.
```

The focused integration coverage verifies accept, reject, exact replay, wrong
actor, stale version, deadline expiry, decision-versus-expiry race, authoritative
reload, a single history transition, and exactly one credit release. Contract
and migration tests verify the OpenAPI surface and the eight-change Liquibase
chain. No production account data or external provider is used.
