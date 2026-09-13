# ADR 0015: AI Companion analysis contract v1

- Status: Accepted
- Date: 2026-09-13
- Decision ID: `MB-AI-COMPANION-001`
- Complements: [ADR 0011](0011-defer-phobert-optional-benchmark-baseline.md)
- Policy: [AI Companion policy v1](../policies/ai-companion-policy-v1.md)

## Context

Journal CRUD is implemented, but AI analysis runtime, its result vocabulary,
provider behavior, persistence, and relationship to Care decisions were not
frozen. A useful AI Companion must help a user understand journal context while
remaining optional, consented, reproducible, and unable to take over screening,
safety, resource eligibility, or SupportPlan decisions.

## Decision

A user explicitly requests analysis for one exact journal revision while a
current `AI_PROCESSING` consent exists. Journal/AI creates an asynchronous job:

```text
POST analysis -> 202 ACCEPTED -> RUNNING -> SUCCEEDED | FAILED
```

Each run uses exactly one configured provider. Journal content is not sent to a
second provider as automatic fallback. Each attempt has a 30-second timeout and
at most one retry, only for HTTP 429, provider 5xx, or transport failure.

The versioned normalized result may contain optional summary and sentiment,
plus context signals, emotion indicators, themes, preference signals, barrier
signals, and one allow-listed suggested action. It records provider, model,
prompt version, schema version, and timestamps. Stored confidence is named and
treated as `modelConfidence`, never clinical confidence, and need not be shown
to the user.

Allowed suggested actions are:

- `NONE`;
- `OFFER_RESOURCE_EXPLANATION`;
- `GUIDE_APPROVED_ACTIVITY`;
- `REQUEST_ALLOWED_ALTERNATIVE`;
- `REQUEST_PLAN_REVIEW`;
- `OPEN_PROFESSIONAL_SUPPORT`;
- `OPEN_SAFETY_GUIDANCE`.

AI may use only consented, authorized context from screening, SupportEvaluation,
active SupportPlan, current conversation, and journal data. It cannot diagnose,
score PHQ-9/GAD-7, change screening level, safety status, or support tier,
select a template, determine eligibility, directly choose a replacement,
mutate a SupportPlan, or prescribe treatment.

The governing invariant is:

```text
AI understands -> Care decides -> User confirms
```

### Longitudinal reassessment evidence

AI Companion also supports an explicit, consented longitudinal analysis over
exact journal revisions in two bounded periods. It may describe contextual and
emotional patterns only within the available entries and returns
`INSUFFICIENT_DATA` when coverage cannot support a comparison. Its normalized
`AiLongitudinalAnalysis` contains:

- `periodStart`, `periodEnd`, and the exact source revision references;
- `contextSignals`, `emotionIndicators`, and `recurringThemes`;
- signal changes of `MORE_FREQUENT`, `LESS_FREQUENT`, `SIMILAR`, or
  `INSUFFICIENT_DATA` compared with the prior period;
- `preferences`, `barriers`, and `helpfulPatterns`;
- `dataCoverage` with entry counts and comparison sufficiency;
- provider, model, prompt, and schema provenance.

Care composes a `ReassessmentSummary` from four separately labelled dimensions:

1. deterministic standardized PHQ-9/GAD-7 trend;
2. non-standardized AI-derived journal/context trend;
3. SupportPlan engagement;
4. user-rated helpfulness, self-reported change, and reflection.

The dimensions are never collapsed into a mental-health improvement score.
Only the screening comparison is a standardized symptom-measure trend. Journal
absence never proves symptom resolution, and AI cannot conclude that the user
has improved, recovered, or been cured. AI evidence may help Care explain
candidate items to keep, review, or replace, but Care finds allowed alternatives
and the user confirms every SupportPlan change.

Journal/AI persists only the validated normalized result and provider/model/
prompt/schema provenance with timestamps. It does not persist raw provider
responses or hidden reasoning. An analysis belongs to its exact journal
revision and is deleted with that revision.

The provider-neutral contract, adapters, and asynchronous job runtime do not
wait for the OpenAI-versus-Gemini benchmark. The benchmark does gate final
production-provider selection and official AI Companion enablement for the
controlled demo. PhoBERT remains separately deferred by ADR 0011.

## Consequences

- Journal remains usable when consent is absent or provider execution fails.
- Care remains authoritative for deterministic screening, safety, support tier,
  templates, eligibility, Reassessment Summary composition, and SupportPlan
  mutation.
- Runtime implementation needs an additive analysis contract, async job state,
  provider adapters, single-entry and longitudinal normalized-result
  validation, exact-source coverage, and deletion coupling.
- Paid provider calls are replaced by deterministic fakes in CI.
- Dataset/benchmark administration remains a separate later batch.

## Rejected alternatives

- Automatic cross-provider fallback: rejected because it sends the same
  sensitive journal data to another processor and can duplicate cost/results.
- Persisting raw responses or chain-of-thought: rejected because product
  behavior needs only the validated normalized result.
- AI-authored SupportPlans: rejected because Care and the user retain those
  decisions.
- Blocking runtime foundations on PhoBERT or benchmark completion: rejected
  because their governance and delivery gates are independent.
