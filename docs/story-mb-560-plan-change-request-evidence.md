# MB-560 verification evidence

## Delivered behavior

- An assigned specialist may attach one exact reviewed MentalBridge resource
  version and a bounded reason to an agreed next step in the latest immutable
  SessionSummary of an evidence-completed appointment.
- Consultation remains the authority for proposal provenance. Its internal
  proposal read verifies the appointment participants, `COMPLETED` state,
  completion fact, latest visible summary version, and exact next-step identity.
- Care owns the `PlanChangeRequest`. Opening the review revalidates current
  `PLUS`/`PREMIUM` entitlement, the one official current plan/version, compatible
  SupportEvaluation and template policy, exact Content eligibility/publication,
  and a compatible plan slot. It creates no draft and mutates no plan.
- Only the owning user may explicitly accept or reject. Rejection records the
  final outcome without changing the plan. Acceptance reloads the authoritative
  Consultation proposal, repeats all Care and Content checks, and atomically
  supersedes the old plan, activates the replacement, and links the exact
  request to the idempotent SupportPlan command.
- The specialist can read the resulting request status for their own proposal.
  The specialist has no endpoint that can decide the request or directly mutate
  a SupportPlan.

## Contracts and persistence

- `contracts/openapi/consultation-service-v1.yaml` adds the bounded resource
  proposal reason and the internal authoritative proposal response.
- `contracts/openapi/care-service-v1.yaml` adds create/read/decision contracts
  for user review and the status-only specialist read.
- Consultation changeset `consultation-012-resource-proposal` stores the reason
  only on exact `PLATFORM_RESOURCE` steps.
- Care changeset `care-024-plan-change-request` stores exact source, plan,
  resource, idempotency, decision, and replacement provenance. The accepted
  SupportPlan command references the request.

## Automated verification

- `SessionSummaryFlowIntegrationTests` verifies authorized proposal retrieval,
  participant isolation, and stale-summary rejection.
- `SupportPlanIntegrationTests#userReviewsRejectsOrAtomicallyAcceptsAnExactSpecialistProposal`
  runs against PostgreSQL Testcontainers and verifies review creation without a
  draft, rejection without mutation, accepted atomic replacement, idempotent
  retry, exact command provenance, and specialist status visibility.
- Care and Consultation OpenAPI and Liquibase inventory tests include the new
  endpoints and migrations.
- Frontend component tests cover the user review/decision UI and specialist
  status-only UI; session-summary validation tests cover the bounded proposal
  reason.

## Fail-closed outcomes

Unauthorized actors, non-completed or stale appointment evidence, missing or
changed proposal provenance, free/uncertain entitlement, missing current plan,
stale plan version, incompatible evaluation/template/slot, and stale,
withdrawn, deleted, malformed, or unavailable exact resource data do not change
the official SupportPlan. A conflicting reuse of an idempotency key is rejected.
