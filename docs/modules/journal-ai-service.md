# Journal/AI Service specification

## Business boundary

Journal/AI owns encrypted journal entries/revisions, AI conversations,
structured analysis results, analysis-job orchestration, provider provenance,
governed dataset metadata/samples, and benchmark coordination. Consultation
provides versioned package entitlement; Care remains the consent, safety,
Support Guide, SupportPlan, eligibility, and confirmation authority.

## Use cases and acceptance

| Capability             | Main behavior                                                                                                                                | Acceptance                                                                                                                                                                                              |
| ---------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Journal                | Create, read, edit/revise, list and delete private entries with an intentional mood check-in                                                 | Owner authorization; bounded cursor history; encrypted per-revision mood and content; edits create revisions and invalidate current analysis; no plaintext logs/indexes                                 |
| AI Companion analysis  | On explicit user request, check current consent, create one job for an exact journal revision, invoke one provider, and normalize the result | `202` asynchronous job; idempotent duplicate; one bounded retry only for 429/5xx/transport; no cross-provider fallback; journal remains readable on failure                                             |
| AI chat and quota      | Route server-authorized package/model/quota policy and return assistant responses                                                            | `FREE` defaults to five delivered responses/day; `PLUS` has higher quota and may share its model; `PREMIUM` may use a stronger model with no displayed daily cap but retains token/rate/fair-use limits |
| Longitudinal context   | Compare exact consented journal revisions across bounded periods and return contextual/emotional changes plus data coverage                  | Only available-entry claims; sparse/imbalanced evidence returns `INSUFFICIENT_DATA`; no clinical improvement conclusion or combined score                                                               |
| Governed accompaniment | Explain approved content, guide an approved activity, surface reassessment context, and phrase an approved reminder                          | AI cannot score, diagnose, decide safety/eligibility, schedule reminders, own state, mutate a SupportPlan, or prescribe; Care decides and the user confirms                                             |
| Specialist read        | Return only entries/indicators allowed by a current Care decision                                                                            | Exact subject/scope/range/entry authorization; fail closed; minimized audited response                                                                                                                  |
| Dataset governance     | Import licensed de-identified datasets and immutable versions                                                                                | Production journals excluded by default; private assets; validation rejects label/schema/leakage violations                                                                                             |
| Benchmark              | Run the same split/config through configured providers; start with OpenAI and Gemini, with PhoBERT optional later                            | Reproducible versions/split; per-class metrics, latency, errors and cost; retry never duplicates predictions; no dependency on the deferred worker                                                      |

## Implementation design

- Feature slices: `journals`, `ai-chat`, `quota`, `analysis-jobs`,
  `llm-providers`, `analysis-results`, `longitudinal-analysis`, `datasets`,
  `benchmarks`, `consented-access`.
- Runtime: Node.js 22 or newer, strict TypeScript, NestJS 11, Zod, official MongoDB driver, `migrate-mongo`, Pino, OpenTelemetry, Vitest, and Testcontainers as defined in `docs/nodejs-service-stack.md`.
- Define OpenAPI, provider output schema, and MongoDB validation/migrations before handlers. TypeScript strict plus runtime validation is mandatory. Add Kafka schemas only for a separately accepted publication feature; MB-367 has none.
- Use MongoDB for every Journal/AI operational aggregate. Analysis workers use atomic claim and bounded leases; future publication requires a Mongo-owned outbox or another accepted recoverable design.
- AI adapters receive minimized decrypted content only for the approved operation; raw provider responses and hidden reasoning are never persisted.
- The normalized result follows `MB-AI-COMPANION-001`: optional summary/sentiment, context and emotion indicators, themes, preferences, barriers, `modelConfidence`, one allow-listed `suggestedAction`, and complete provider/model/prompt/schema provenance.
- Longitudinal results retain exact source revisions, bounded periods, comparison direction, coverage sufficiency, and provenance. Journal/AI supplies this non-standardized evidence to Care; Care composes the four-dimensional Reassessment Summary and owns every SupportPlan decision.
- ADR 0017 package routing is server-side and versioned. AI may accompany a
  plan, reassessment, or approved reminder wording but never owns those states
  or bypasses user confirmation.
- MB-369 resolves the current package through Consultation using the forwarded
  end-user bearer, snapshots a versioned workload route on the first provider
  attempt, and rejects dependency uncertainty or route-changing entitlement
  changes. `FREE` and `PLUS` share the v1 baseline route; `PREMIUM` may use a
  stronger approved route. No client tier, direct Consultation database read,
  or automatic cross-provider fallback is permitted.
- Gemini and OpenAI adapters, exact-revision prompt registry, synthetic
  benchmark harness, and benchmark metadata remain provider-neutral. Real
  execution needs explicit credentials/configuration and an approval version;
  deterministic fake execution remains mandatory for local/test/CI.
- Initial AI provider and benchmark work remains provider-neutral but does not require `phobert-worker`; ADR 0011 defines the separate activation gate for that optional baseline.

## Ordered tasks

- [x] JAI-01 Scaffold the NestJS/TypeScript service with feature modules, typed configuration, health/readiness, lint, test, and build commands.
- [ ] JAI-02 Resolve journal retention/encryption plus dataset license/edit and benchmark label policies; AI provider/result retention is fixed by ADR 0015.
- [ ] JAI-03 Define journal/AI Companion analysis/dataset/benchmark OpenAPI and the ADR 0015 normalized provider-result schema. The exact-revision request/status/result slice is complete; dataset and benchmark contracts remain deferred.
- [ ] JAI-04 Define analysis command/result schemas and Care consent/structured-indicator contracts. Exact-revision, longitudinal, and `AI_PROCESSING` contracts are complete; specialist-sharing remains deferred.
- [ ] JAI-05 Add `migrate-mongo` validators/indexes for journal, job, result, dataset, and benchmark collections plus data documentation. Journal, exact-revision and longitudinal job/result, and MB-369 synthetic benchmark metadata collections are complete; general dataset import remains deferred.
- [x] JAI-06 Implement encrypted journal revisions, authorization, pagination and deletion.
- [ ] JAI-07 Implement consent-gated idempotent analysis orchestration, adapters, bounded retry and reconciliation. MB-367 completes the exact-revision job runtime; MB-369 adds gated Gemini/OpenAI adapters and entitlement routing. An accepted real route and broader reconciliation remain deferred until benchmark approval.
- [ ] JAI-08 Implement dataset import/versioning and reproducible benchmark coordination. MB-369 completes the exact-revision synthetic harness for one or both configured Gemini/OpenAI candidates; general governed dataset import remains deferred.
- [ ] JAI-09 Verify malformed AI output, prompt injection boundary, timeout/cost limit, duplicates/reordering, cross-store recovery and deletion.
- [ ] JAI-10 Add observability/configuration, module README, and pass Node/contract/Mongo gates. Journal/AI is Mongo-only and has no PostgreSQL gate.

## MB-371 delivery boundary

MB-371 adds owner-scoped idempotent longitudinal jobs over two equal 7-31 day
periods. Journal/AI selects at most 50 active current revisions per period,
persists the exact selected versions, rechecks `AI_PROCESSING` consent before
provider execution, and uses the existing entitlement-aware single-provider
route with one bounded retry. At least three entries per period and a maximum
2:1 count ratio are required for a directional comparison; otherwise every
exposed change direction is `INSUFFICIENT_DATA`.

Migration `009_longitudinal_context_analysis.cjs` owns the job/result validators
and indexes. Deleting any source journal deletes dependent longitudinal jobs
and results; a source revised before execution fails without substituting the
new revision. The owner job endpoint and minimized
`REASSESSMENT_SUMMARY` Care read return exact source versions, coverage, safe
normalized evidence, and provenance without raw text. MB-386 implements Care
composition with the standard 2-second consumer deadline; MB-371 does not
create a frontend screen or a combined improvement score.

Implementation decisions, exact verification commands, and evidence
classification are recorded in
[MB-371 bounded longitudinal journal context evidence](../story-mb-371-longitudinal-context-evidence.md).

## MB-236 delivery boundary

MB-236 delivers JAI-06 plus the journal-only portions of JAI-03 and JAI-05 for create/list/detail/revise/delete. The broader JAI-03 and JAI-05 items remain open because analysis, datasets, and benchmarks are not part of that delivery. It does not call an AI provider, create analysis jobs, publish analysis events, import datasets, or run benchmarks.

## MB-367 delivery boundary

MB-367 adds the backend-only exact-revision slice: Care-owned
`AI_PROCESSING` consent, the Journal/AI request/status contract, MongoDB job
and normalized-result collections, atomic leased claims, one bounded retry,
deletion/stale coupling, and a deterministic fake provider. The verified
end-user bearer is forwarded to Care at request time and immediately before
each provider attempt, retained only in worker memory, and never persisted or
logged. Frontend disclosure/reflection UI remains in Story 6203; real provider
selection and benchmark enablement remain in Story 6204/MB-369.

## MB-369 delivery boundary

MB-369 adds Consultation's minimal authoritative current-entitlement lookup,
Journal/AI's workload/package router, Gemini/OpenAI structured-output adapters,
versioned exact-revision prompts, additive provenance, and a synthetic
benchmark harness that records normalized quality/safety, latency, token,
cost, malformed-output, and failure evidence. Adapter availability does not
approve a model: real execution remains disabled until the exact route carries
an accepted benchmark approval identifier and credentials. This Story does
not add billing/payment/credit lifecycle, AI Chat or its quota ledger, vector
retrieval/RAG, or PhoBERT.

The implementation and deferred live-provider approval evidence are recorded
in [MB-369 model routing and benchmark foundation evidence](../story-mb-369-model-routing-evidence.md).
The live harness has been exercised, but the full candidate gate remains
deferred after Gemini daily free-tier exhaustion; runtime remains
`DETERMINISTIC_FAKE`.

## Story 6201 authoring decision

Journal authoring remains bounded plain text. The approved mood vocabulary is
`GREAT`, `GOOD`, `OKAY`, `LOW`, and `VERY_LOW`; it records the user's selected
reflection and is not a score, diagnosis, or inferred sentiment. The frontend
owns localized labels and emoji. Journal/AI encrypts mood independently inside
each revision, returns `null` for legacy revisions, and preserves the current
mood when a compatible older client omits it during revise.

Saving is explicit and successful create/revise calls produce one durable
revision. There is no server autosave and no browser-persisted raw journal
draft. The frontend retains a draft in memory after validation, dependency, or
revision-conflict failures and warns before closing the editor or navigating
away with unsaved changes. Prompt selection is presentation-only and is not
persisted.
