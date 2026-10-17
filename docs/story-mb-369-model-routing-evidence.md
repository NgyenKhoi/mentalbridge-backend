# MB-369 model routing and benchmark foundation evidence

## Delivered boundary

MB-369 adds the minimal Consultation-owned current-entitlement projection,
server-authoritative `FREE`/`PLUS`/`PREMIUM` exact-revision routing, structured
Gemini/OpenAI adapters, prompt/model provenance, and a Mongo-backed synthetic
benchmark harness. It does not implement billing, credits, AI Chat quota, RAG,
or provider fallback.

Real provider execution remains gated. The runtime stays in
`DETERMINISTIC_FAKE`; this delivery does not approve Gemini, OpenAI, or any
provider/model candidate for production use.

## Verification evidence

The following non-provider commands were run from the feature branch on
2026-09-19:

| Evidence | Command | Result |
| --- | --- | --- |
| Formatting | `cd journal-ai-service && npm run format:check` | Passed |
| Lint | `cd journal-ai-service && npm run lint` | Passed, zero warnings |
| Type safety | `cd journal-ai-service && npm run typecheck` | Passed |
| OpenAPI | `cd journal-ai-service && npm run contract:check` | Passed; Journal/AI v1 contract validated |
| Mongo migrations | `cd journal-ai-service && npm run migration:check` | Passed through `006_daily_emotion_check_ins.cjs`, `007_entitlement_aware_model_routing.cjs`, and `008_ai_benchmark_metadata.cjs` |
| Unit and HTTP contract tests | `cd journal-ai-service && npm test` | Passed: 55 tests, 0 failed; deterministic fake/provider stubs only |
| Persistence integration | `cd journal-ai-service && npm run test:integration` | Passed: 4 tests, 0 failed; ephemeral MongoDB and fake provider only |
| Consultation contract/changelog | `cd consultation-service && .\\mvnw.cmd -q '-Dtest=ConsultationOpenApiContractTests,ConsultationLiquibaseChangelogTests' test` | Passed |
| Consultation package | `cd consultation-service && .\\mvnw.cmd -q -DskipTests package` | Passed |
| Repository policy | `powershell -ExecutionPolicy Bypass -File scripts/verify-repository.ps1 -BaseSha origin/dev` | Passed for all tracked files and paired changes |

Consultation Testcontainers cannot run locally while the Docker Desktop Linux
engine is unavailable. `docker info` reaches the client but cannot open the
`dockerDesktopLinuxEngine` named pipe. This is an environment blocker before
the Spring/Testcontainers assertions execute, not an assertion failure.

## Live Gemini benchmark status

The live Gemini path was exercised against only the committed synthetic
Vietnamese dataset. The harness successfully built requests, invoked Gemini,
validated structured output, calculated quality/safety/latency/token/cost
metrics, and persisted dataset, run, and case-result evidence in MongoDB. It did
not persist raw provider responses, hidden reasoning, API credentials, request
headers, or bearer tokens.

The most complete live run was
`bed02a11-76ee-47a2-9b7d-f2bb4f0ba4d2` for pinned candidate
`gemini-3.8-flash`:

| Metric | Result |
| --- | ---: |
| Case count | 6 |
| Successful cases | 5 |
| Failed cases | 1 |
| Quality pass rate | 0.8333 |
| Safety pass rate | 0.8333 |

The incomplete case was `governed-safety-guidance`. Gemini returned HTTP `429`
with provider code `RESOURCE_EXHAUSTED` and quota ID
`GenerateRequestsPerDayPerProjectPerModel-FreeTier`. Further live calls were
stopped because this is a daily free-tier quota, not a result/schema failure
that another immediate retry can correct.

The required full six-case candidate gate has therefore not passed and is
deferred to MB-432 or equivalent follow-up verification after quota reset. No
incomplete result may be used to approve a provider/model, create an approval
version, enable `APPROVED_REAL`, or make a production routing decision.

The adapter and benchmark command now expose only bounded safe diagnostics:
HTTP status, provider error code, retry delay, quota identifier/metric, finish
reason, and schema issue paths. Daily-quota fail-fast for the remaining cases is
still a follow-up; it is not required to merge the disabled implementation
foundation.

## Deferred approval gate

MB-432/follow-up verification must run the exact committed six-case dataset
without changing its expected terms or safety requirements and demonstrate:

```text
caseCount = 6
successCount = 6
errorCount = 0
safetyPassRate = 1.0
qualityPassRate >= 0.8333
```

It must also confirm that no case has `TIMEOUT`, `UNAVAILABLE`,
`INVALID_RESULT`, `INVALID_NORMALIZED_OUTPUT`, or `INTERNAL_ERROR`. Only a
separately reviewed passing run may back an explicit provider approval version.
