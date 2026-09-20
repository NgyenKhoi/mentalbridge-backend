# MB-371 bounded longitudinal journal context evidence

## Delivered behavior

MB-371 implements a Journal/AI-owned asynchronous comparison over exact current
journal revisions selected from two bounded periods. The owner creates an
idempotent job, polls its state, and receives normalized non-standardized
evidence. Care may read the completed evidence through a minimized
`REASSESSMENT_SUMMARY` projection while forwarding the verified end-user bearer
context.

The runtime does not calculate screening trend, a combined wellness score, or
a clinical-improvement verdict. Care remains the future Story 6501 composition
owner.

## Confirmed decisions

- Periods are half-open, non-overlapping, equal in duration, and each spans
  7-31 days; the current period cannot end in the future.
- Journal/AI selects active current owned revisions by `occurredAt`, with an
  optional list of at most 100 excluded journal IDs and a maximum of 50 selected
  sources per period.
- Directional coverage requires at least three entries per period and no
  greater than a 2:1 count imbalance. Otherwise
  `sufficientForComparison=false` and exposed changes use only
  `INSUFFICIENT_DATA`.
- A missing mention is not resolution evidence. Prompts prohibit diagnosis,
  PHQ-9/GAD-7 scoring, recovery/cure/improvement conclusions, treatment
  prescription, eligibility decisions, and SupportPlan mutation.
- Request-time and pre-provider `AI_PROCESSING` consent checks fail closed.
  Minimized Care reads recheck current consent.
- Source text and bearer credentials are never persisted. Deleting any source
  journal deletes dependent jobs/results; a source revision changed before
  execution fails without substitution.

## Contract and persistence

Implemented operations:

- `POST /api/v1/longitudinal-analysis-jobs`
- `GET /api/v1/longitudinal-analysis-jobs/{jobId}`
- `GET /internal/v1/users/{userId}/longitudinal-analyses/{analysisId}?purpose=REASSESSMENT_SUMMARY`

Migration `009_longitudinal_context_analysis.cjs` creates
`longitudinal_analysis_jobs` and
`journal_longitudinal_analysis_results` with strict validators and indexes for
idempotency, claims, owner history, exact sources, analysis IDs, job IDs, and
bounded user-period reads. OpenAPI version `1.2.0`, the canonical Mongo logical
model, the detailed collection definition, ADR 0015, and AI Companion policies
v1/v2 describe the same boundary.

## Verification

Evidence classifications:

- Unit/HTTP tests use deterministic fakes and synthetic journal content.
- Mongo service integration uses a disposable real MongoDB process started by
  `mongodb-memory-server` and applies migrations 001-009.
- No browser fixture evidence, live provider call, or live cross-stack E2E is
  claimed.

Commands executed from `journal-ai-service`:

| Command | Result |
| --- | --- |
| `npm run format:check` | Passed |
| `npm run lint` | Passed |
| `npm run typecheck` | Passed |
| `npm run contract:check` | Passed; OpenAPI validated |
| `npm run migration:check` | Passed; migrations 001-009 validated |
| `npm test` | Passed: 66 unit/HTTP tests after correcting a timestamp-varying duplicate-test fixture; the implementation fingerprint remained strict |
| `npm run test:integration` | Passed: 5 Mongo integration tests |
| `node --env-file-if-exists=.env.integration --test dist/longitudinal-analysis/longitudinal-analysis.test.js dist/longitudinal-analysis/longitudinal-analysis.mongo.integration.test.js` | Passed: 10 focused unit/integration tests after exclusion and provider-failure coverage was added |

The focused scenarios cover sufficient coverage, sparse coverage, imbalanced
coverage, explicit exclusion, duplicate replay, conflicting key reuse, period
validation, owner isolation, current-consent Care access, revoked consent,
provider failure, deleted/changed source, deletion coupling, exact source
versions, and absence of raw text/token/provider-response persistence.

## Intentional deferrals

- Story 6501 owns the Care `ReassessmentSummary` composition, its standard
  2-second Journal/AI client deadline, resilience policy, and actor-facing
  presentation.
- The frontend has no direct MB-371 surface because this Story exposes the
  Journal/AI owner capability and Care consumer boundary; it does not own the
  future composed reassessment screen.
- Production provider activation remains governed by the separately accepted
  benchmark approval and configuration gate. Local/test/CI evidence uses the
  deterministic provider.
