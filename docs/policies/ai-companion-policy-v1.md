# AI Companion policy v1

## Policy metadata

| Field                           | Value                                                                                                                 |
| ------------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| Policy ID                       | `MB-AI-COMPANION-001`                                                                                                 |
| Status                          | `HISTORICAL POLICY; EXACT-REVISION AND LONGITUDINAL BACKENDS IMPLEMENTED UNDER ADR 0015/0017`                          |
| Effective decision date         | 2026-09-13                                                                                                            |
| Owner                           | Journal/AI                                                                                                            |
| Consent and Care-decision owner | Care                                                                                                                  |
| Decision                        | [ADR 0015](../adr/0015-ai-companion-analysis-contract.md)                                                             |
| Amended by                      | [ADR 0017](../adr/0017-product-scope-v2.md); current rules are in [AI Companion policy v2](ai-companion-policy-v2.md) |

AI Companion supports reflection and navigation. It is not a diagnostician,
clinician, safety authority, or autonomous SupportPlan agent.

It has three bounded roles in the journey:

1. before or at plan review, explain journal/context signals;
2. during a plan, capture preference, barrier, and helpfulness signals and guide
   only approved activities;
3. at reassessment, compare consented journal/context evidence across bounded
   periods for a richer Care-owned SupportPlan review.

## Request and job flow

- The user explicitly requests analysis of one exact owned journal revision.
- Journal/AI verifies current `AI_PROCESSING` consent with Care.
- The API returns `202 ACCEPTED` for one idempotent asynchronous job.
- Job states are `RUNNING`, `SUCCEEDED`, or `FAILED` after acceptance; retrying
  the logical request does not create duplicate results.
- Journal CRUD remains available when consent, provider, or analysis is
  unavailable.

## Allowed context

Only currently authorized, minimized facts may be supplied:

- screening context;
- SupportEvaluation;
- active SupportPlan;
- current conversation context;
- consented journal context.

Provider prompts and events exclude unrelated user facts. Revoked consent blocks
new provider execution.

## Normalized result

```text
summary?
contextSignals[]
emotionIndicators[]
themes[]
preferenceSignals[]
barrierSignals[]
sentiment?
modelConfidence?
suggestedAction
provider
model
promptVersion
schemaVersion
createdAt
```

`sentiment` is optional and not a core clinical signal. `modelConfidence` means
only model-reported confidence and is not required in user-facing UI.

`suggestedAction` is exactly one of `NONE`, `OFFER_RESOURCE_EXPLANATION`,
`GUIDE_APPROVED_ACTIVITY`, `REQUEST_ALLOWED_ALTERNATIVE`,
`REQUEST_PLAN_REVIEW`, `OPEN_PROFESSIONAL_SUPPORT`, or
`OPEN_SAFETY_GUIDANCE`. It opens a governed flow; it never performs the action.

## Longitudinal analysis

An explicit longitudinal request supplies two equal-duration, half-open,
non-overlapping comparison periods. Each period spans 7 through 31 days and the
current period cannot end in the future. Journal/AI selects the caller's active
current revisions by `occurredAt`, after applying an optional exclusion list of
at most 100 journal IDs, and persists the selected exact versions. Selection is
limited to 50 entries per period. The normalized contract is:

```text
AiLongitudinalAnalysis {
  previousPeriod { startAt, endAt }
  currentPeriod { startAt, endAt }
  sourceJournalRevisions[]
  contextSignals[]
  emotionIndicators[]
  recurringThemes[]
  changesComparedWithPreviousPeriod[] {
    signal
    direction: MORE_FREQUENT | LESS_FREQUENT | SIMILAR | INSUFFICIENT_DATA
  }
  preferences[]
  barriers[]
  helpfulPatterns[]
  dataCoverage {
    previousPeriodJournalEntryCount
    currentPeriodJournalEntryCount
    sufficientForComparison
  }
  provider
  model
  promptVersion
  schemaVersion
  createdAt
}
```

All statements are qualified as applying “within the available journal
entries.” A missing mention is not evidence that a difficulty resolved. Sparse,
imbalanced, or otherwise insufficient source coverage returns
`INSUFFICIENT_DATA` instead of a directional claim.

Coverage is sufficient only when each period contains at least three selected
entries and neither period contains more than twice the entries of the other.
When this rule fails, `sufficientForComparison` is false and every exposed
change direction is `INSUFFICIENT_DATA`.

Care's MB-386 Story 6501 composition calls the minimized
`REASSESSMENT_SUMMARY` read with an explicit deadline and the verified end-user
bearer context. Journal/AI rechecks current `AI_PROCESSING` consent and fails
closed on denial or Care unavailability. The response includes exact source
versions, coverage, normalized evidence, and provenance, never raw text.

## Reassessment Summary

Care presents four dimensions separately:

| Dimension             | Authority and wording                                                                                         |
| --------------------- | ------------------------------------------------------------------------------------------------------------- |
| Screening trend       | Deterministic, versioned PHQ-9/GAD-7 score and band comparison; the standardized symptom-measure trend        |
| Journal/context trend | Non-standardized model-derived observations limited to consented available entries and explicit data coverage |
| Support engagement    | Plan activities, completion, barriers, and other Care-owned engagement facts                                  |
| User reflection       | User-rated helpfulness, self-reported change, and notes                                                       |

There is no combined “mental health improvement score,” recovery percentage,
or AI verdict that a condition improved. The summary may say current screening
is lower and certain journal indicators appear less frequently in the available
entries; it cannot say AI confirmed recovery, cure, or clinical improvement.

For SupportPlan review, AI may identify evidence such as a reported helpful
pattern, persistent barrier, or recurring context. Care maps that evidence only
to allowed candidate alternatives. The user reviews and confirms any plan
change.

## Prohibited authority

AI cannot diagnose, score PHQ-9/GAD-7, change screening level, safety status, or
support tier, determine clinical improvement, select a SupportPlan template,
determine resource eligibility, choose a replacement resource directly, mutate
a SupportPlan, or prescribe treatment. Care decides and the user confirms every
governed plan action.

## Provider execution and storage

- One configured provider executes each run; there is no automatic
  cross-provider fallback with the same journal data.
- Timeout is 30 seconds per attempt.
- Maximum retry count is one, only for HTTP 429, provider 5xx, or transport
  failure.
- Persist the validated normalized result, provider, model, prompt version,
  schema version, and timestamps.
- Never persist raw provider responses or hidden reasoning/chain-of-thought.
- Bind a single-entry result to its exact journal revision. Bind a longitudinal
  result to every exact source revision. Deleting a source revision deletes or
  invalidates every dependent result so it cannot be reused.

## Benchmark gate

The OpenAI/Gemini benchmark does not block the provider-neutral contract,
adapters, or asynchronous job runtime. It does block final production-provider
selection and official AI Companion enablement for the controlled demo.
PhoBERT remains optional and deferred under ADR 0011.

## Runtime gates

The single-entry and longitudinal owner runtimes, current-consent checks,
provider adapters, exact-source persistence/deletion behavior, and minimized
Care read and MB-386 Reassessment Summary composition are implemented. The
actor-facing presentation remains dependent Story 6502 rather than MB-371.
