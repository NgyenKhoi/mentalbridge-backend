# MB-374 SupportPlan lifecycle evidence

## Delivered boundary

MB-374 makes SupportPlan lifecycle changes explicit owner commands. Care owns
the state machine and accepts only these transitions:

- `DRAFT` to `DISCARDED`
- `ACTIVE` to `PAUSED` or `COMPLETED`
- `PAUSED` to `ACTIVE` or `COMPLETED`

Submitting the already-persisted desired state is a no-op so an ambiguous retry
does not create another transition. Optimistic `If-Match` handling and the
owner lock serialize competing commands. Terminal plans remain immutable and
are available through owner-scoped, stable cursor history and detail reads.

Completion can store one optional bounded reason code: `USER_DECISION`,
`PLAN_NO_LONGER_FITS`, or `OTHER`. The code records user intent only; it is not
a clinical interpretation or evidence of recovery.

## Canonical contract and persistence

- `PUT /api/v1/support-plans/{supportPlanId}/status` remains the desired-state
  command and now documents the optional completion reason and retry semantics.
- `GET /api/v1/support-plans/history` returns only terminal owner plans in
  deterministic `updatedAt DESC, supportPlanId DESC` order.
- `GET /api/v1/support-plans/{supportPlanId}` returns an owner-scoped immutable
  detail used from history.
- Changeset `care-016-support-plan-lifecycle-history` adds the bounded nullable
  completion reason and the terminal-history access index. The field data
  dictionary and canonical relational model are synchronized with it.

SupportEvaluation and assessment writes do not invoke lifecycle commands.
There is no AI-controlled or inferred pause, resume, completion, or discard
path.

## Acceptance and regression evidence

`SupportPlanIntegrationTests` covers valid pause/resume/complete/discard,
invalid transitions, same-state replay, owner isolation, optional completion
reason, immutable terminal detail, stable history pagination, reload after a
command, concurrent conflicting commands, and proof that a later assessment or
evaluation does not change the active plan.

Provider-contract and migration tests cover the two additive reads, the status
payload, response fields, authentication, implemented-path inventory, and
Liquibase registration.

Final local verification from `care-service`:

```text
mvn -q test
PASS: 32 test reports / 170 tests, 0 failures, 0 errors, 0 skipped

SupportPlanIntegrationTests
PASS: 14 real-PostgreSQL Testcontainers integration tests
```

The repository paired-change verifier and pull-request CI are required to pass
after the final branch is synchronized with `origin/dev`. No live deployed
cross-stack claim is made by this document.
