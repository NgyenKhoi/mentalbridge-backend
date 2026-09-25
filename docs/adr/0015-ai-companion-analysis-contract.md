# ADR 0015: AI Companion analysis contract v1

- Status: Accepted
- Date: 2026-09-13
- Last amended: 2026-09-24 (MB-386 Care-owned immutable Reassessment Summary composition)
- Decision ID: `MB-AI-COMPANION-001`
- Complements: [ADR 0011](0011-defer-phobert-optional-benchmark-baseline.md)
- Amended by: [ADR 0017](0017-product-scope-v2.md), which adds package quota/model behavior and permits AI accompaniment across SupportPlan, reassessment, and approved reminder wording without transferring business-state authority
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

Journal/AI uses MongoDB as its only operational database. Journals, durable
analysis jobs, normalized results, longitudinal analyses, and future dataset
and benchmark metadata remain in owner collections governed by append-only
`migrate-mongo` migrations. MB-367 does not add PostgreSQL, `pg`,
`node-pg-migrate`, a PostgreSQL outbox, or Kafka. Workers claim due jobs with an
atomic update and a bounded lease; an expired lease is recoverable without
creating another logical job. A future event publisher must use a Mongo-owned
outbox or another explicitly accepted recoverable-publication design.

Care exposes `AI_PROCESSING` under policy version
`ai-processing-capstone-v1`, separately from `PRIVACY_POLICY`. It covers only
exact-revision and bounded longitudinal journal analysis. Journal/AI forwards
the verified end-user bearer context to Care through REST; it checks consent
when accepting a request and immediately before every provider attempt. The
bearer credential is never persisted in MongoDB or logged. If a process restart
loses that ephemeral authorization context, a reclaimed job fails closed
rather than calling a provider. Revocation blocks new jobs and retries but does
not delete historical results; deletion is a separate owner workflow.

MB-367 uses only a deterministic fake provider in local development, tests,
and CI. OpenAI/Gemini activation, provider selection, and benchmarking remain
gated by MB-369/Story 6204.

MB-369 adds a versioned `EXACT_REVISION` model-routing policy. Consultation is
the package authority and exposes the authenticated user's current effective
`FREE`/`PLUS`/`PREMIUM` entitlement. Journal/AI forwards the verified end-user
bearer for that lookup, never accepts a client-supplied tier, and never reads
Consultation storage. Absence of an effective paid or explicit demo projection
is `FREE`; explicit demo projections retain `DEMO` provenance and must not be
presented as payment.

The first provider attempt snapshots workload, package, entitlement source and
policy version, routing-policy version, provider, model, prompt version, and
approval version on the durable job. A retry may use only that same route. If
the current entitlement would resolve to a different route, the job terminates
without another provider call. The router never silently falls back to another
provider. `FREE` and `PLUS` share one baseline route in v1; `PREMIUM` may use a
separately approved stronger route.

Gemini and OpenAI adapters use structured JSON output and remain disabled by
default. Real execution requires explicit configuration, credentials, and an
approval identifier backed by a reproducible synthetic benchmark. Local/test/
CI continues to select the deterministic fake. A benchmark run accepts one or
both complete provider/model candidates, so evaluating Gemini does not require
an unused OpenAI credential. It records dataset, rubric, provider, exact model,
prompt and schema versions plus normalized quality/safety, latency, token, cost,
malformed-output, and failure evidence. It never uses production journals.
Implementing adapters and the harness does not itself declare a model approved.

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
The request supplies two half-open, non-overlapping periods of equal duration.
Each period is at least 7 days and at most 31 days, and the current period may
not end in the future. Journal/AI selects every active current revision owned
by the caller whose `occurredAt` falls in the requested period, after applying
an optional list of at most 100 explicitly excluded journal IDs. At most 50
sources may be selected in either period.

Coverage is sufficient only when each period contains at least three selected
entries and neither count is more than twice the other. Sparse or imbalanced
coverage sets `sufficientForComparison` to false and forces the normalized
change direction to `INSUFFICIENT_DATA`. `AiLongitudinalAnalysis` contains:

- explicit `previousPeriod` and `currentPeriod` bounds plus exact source
  revision references;
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

Journal/AI persists only the validated normalized result, exact source
references, coverage, and provider/model/prompt/schema provenance with
timestamps. It does not persist raw provider responses or hidden reasoning. A
longitudinal analysis is deleted when any source journal is deleted. A source
revision change before provider execution fails the job rather than silently
substituting new text.

Care may read a completed result only through the minimized
`REASSESSMENT_SUMMARY` projection while forwarding the verified end-user bearer
context. Journal/AI rechecks current `AI_PROCESSING` consent and owner identity;
dependency uncertainty fails closed. The projection contains coverage, exact
source versions, normalized signals, and provenance, but no journal text,
provider raw response, combined score, or clinical-improvement conclusion.

### MB-386 composition decisions

Care accepts the exact current PHQ-9 result, current GAD-7 result, Journal/AI
analysis ID, and the same two comparison periods used by the longitudinal job.
It validates equal non-overlapping 7-31 day periods and requires a successful
projection to attribute the requested analysis and exact bounds. Care selects
the immediately preceding owned, non-voided result with the same instrument and
scoring version for each standardized trend. A missing compatible predecessor
is `INSUFFICIENT_DATA`; it is not an unchanged trend.

SupportPlan engagement and reflection use the occurrence `scheduled_at` period
and only records for which the owner explicitly set
`summary_reuse_approved=true`. Completion/skip and coded barriers form the
engagement dimension. Helpfulness and the approved bounded reflection form the
separate user-reflection dimension. Care does not infer adherence,
self-reported change, recovery, or a direction from those facts.

The composition command is owner-scoped and idempotent. Care performs no remote
call inside the snapshot transaction and persists the complete returned
four-dimension JSON as `reassessment-summary-v1`. Current, detail, and history
queries read that immutable snapshot, so later source mutation or deletion
cannot rewrite a past summary. A deleted/missing analysis is
`UNAVAILABLE/SOURCE_NOT_FOUND`; consent denial is
`UNAVAILABLE/CONSENT_UNAVAILABLE`; timeout, transport, or provider failure is
`UNAVAILABLE/DEPENDENCY_UNAVAILABLE`; invalid attribution or content is
`UNAVAILABLE/INVALID_PROJECTION`. Sparse valid journal coverage remains
`INSUFFICIENT_DATA` with its exact coverage.

Actor copy must say that unavailable journal information does not hide the
other dimensions, that insufficient data cannot support comparison, and that
different dimensions may point in different directions. It must not resolve a
contradiction into one verdict. The Care OpenAPI contradictory example is the
canonical backend fixture for Story 6502.

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
- Consultation gains only a current-entitlement read-model foundation; MB-369
  does not implement purchase, upgrade, MoMo, billing periods, chat quota,
  consultation credits, or ledgers.
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
