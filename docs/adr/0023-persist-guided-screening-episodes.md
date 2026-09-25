# ADR 0023: Persist guided screening episodes in Care

- Status: Accepted
- Date: 2026-09-25
- Decision ID: `MB-SCREENING-EPISODE-001`
- Delivery: MB-292 corrective architecture slice
- Extends: [ADR 0012](0012-two-domain-screening-and-system-proposed-support-plans.md) and [ADR 0022](0022-current-product-blueprint-amendments.md)

## Context

Assessment submissions and SupportEvaluation were durable, but the fact that
one PHQ-9 and one GAD-7 belonged to the same guided initial-check or
reassessment journey existed only in browser session cookies or a “latest
assessment” query. Logout, cookie loss, and newer standalone screenings could
therefore lose or silently change the evidence used for Support Guide,
SupportPlan, and replacement review.

## Decision

Care persists a user-owned `ScreeningEpisode` with purpose `INITIAL_CHECK` or
`REASSESSMENT`, lifecycle `IN_PROGRESS`/`READY`/`COMPLETED`, exact nullable
PHQ-9 and GAD-7 references, the resulting domain-aware SupportEvaluation, and
versioned timestamps.

Guided assessment submission and episode attachment are one Care transaction.
Standalone screening remains ordinary history and is never attached by a
“latest” lookup. Clients resume the latest episode from Care; cookies may only
be non-authoritative presentation hints.

SupportPlan proposal accepts only a v2 SupportEvaluation belonging to a
completed episode. ReassessmentSummary composition accepts only the exact pair
in the current completed reassessment episode. Replacement review additionally
compares the summary's current PHQ-9/GAD-7 pair with the proposed draft's exact
SupportEvaluation and returns `REASSESSMENT_SCREENING_CONTEXT_MISMATCH` before
any mutation when they differ.

The current result UI still reads the historical v1 presentation evaluation.
Its optional episode reference is compatibility-only and has no SupportPlan
decision authority.

## Consequences

- Guided work resumes across browser and authentication-session loss.
- A new standalone screening cannot silently change a plan context.
- Initial check and reassessment remain separate persisted journeys.
- Existing assessment and evaluation history remains immutable; there is no
  backfill that guesses past grouping.
- A new guided run creates a new episode after the previous one completes.

## Rejected alternatives

- Longer-lived cookies: rejected because the browser is not the evidence owner.
- Always choose the latest PHQ-9 and GAD-7: rejected because two independent
  history rows do not prove one user journey.
- Persist grouping in the frontend database: rejected because Care owns
  assessments, evaluation, and SupportPlan admission.
