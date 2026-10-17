# MB-376 SupportPlan engagement evidence

## Delivered boundary

Care owns mutable engagement on each exact scheduled SupportPlan occurrence.
The authenticated owner can complete, skip, reopen, hide/show, optionally rate
helpfulness or select a barrier, keep a bounded private reflection, approve
minimized reuse in a later bounded summary, and delete the mutable response.
Deletion does not delete the occurrence or its plan, schedule, slot, resource,
or content-version provenance.

The web BFF validates the same contract and the SupportPlan page presents an
accessible responsive workspace. Copy consistently describes these values as
self-reported wellbeing context. There is no specialist checklist endpoint,
continuous observation, adherence metric, clinical-improvement claim, or
recovery score.

This slice follows [ADR 0021](adr/0021-support-plan-engagement.md) and the
[SupportPlan policy v2](policies/support-plan-policy-v2.md). MB-386 remains the
owner of final four-dimension Reassessment Summary composition; MB-376 supplies
the separate minimized engagement evidence and exact provenance it may consume.

## Contract and persistence evidence

- `PUT` and `DELETE /api/v1/support-plan-occurrences/{occurrenceId}/engagement`
  require owner authorization and strong `If-Match` concurrency.
- Desired-state equality is checked before stale-version failure, so exact
  retries are idempotent while conflicting stale changes fail.
- Database checks constrain states, helpfulness, barriers, reflection length,
  and legal combinations. Paused, terminal, cancelled, stale, and wrong-owner
  mutations fail closed.
- `care.support-plan.engagement-changed` commits with the occurrence update and
  attributes the plan version, slot, resource ID, and content version. Its
  schema deliberately has no reflection property.
- Controlled-demo retention follows the occurrence aggregate. No production
  duration or deletion guarantee is inferred without the later retention gate.

## Verification scope

- Backend contract/unit checks cover OpenAPI operations, event minimization,
  Liquibase registration, service validation, and runtime operation coverage.
- Docker-backed integration coverage exercises create/update/repeat, wrong
  owner and specialist denial, inactive plan, stale versions, deletion,
  reload, event minimization, and concurrent writes.
- Frontend validation/BFF/component coverage exercises exact response parsing,
  replacement/deletion forwarding, helpfulness/reflection/approval capture,
  hide/show, reopen, deletion, paused controls, source provenance, and
  non-clinical wording.

Final command results and any environmental block are recorded in the pull
request description; unavailable Docker or live-stack evidence is never
reported as passing.
