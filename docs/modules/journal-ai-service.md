# Journal/AI Service specification

## Business boundary

Journal/AI owns encrypted journal entries/revisions, structured analysis results, analysis-job orchestration, provider provenance, governed dataset metadata/samples, and benchmark coordination. MongoDB owns journals/results/samples; PostgreSQL owns durable job/dataset/run metadata and outbox. Care remains the current AI-consent and safety/support authority.

## Use cases and acceptance

| Capability | Main behavior | Acceptance |
| --- | --- | --- |
| Journal | Create, read, edit/revise, list and delete private entries | Owner authorization; bounded cursor history; encryption metadata; edits create revisions and invalidate current analysis; no plaintext logs/indexes |
| AI Companion analysis | On explicit user request, check current consent, create one job for an exact journal revision, invoke one provider, and normalize the result | `202` asynchronous job; idempotent duplicate; one bounded retry only for 429/5xx/transport; no cross-provider fallback; journal remains readable on failure |
| Longitudinal context | Compare exact consented journal revisions across bounded periods and return contextual/emotional changes plus data coverage | Only available-entry claims; sparse/imbalanced evidence returns `INSUFFICIENT_DATA`; no clinical improvement conclusion or combined score |
| Governed suggestion | Return reflection signals and one allow-listed navigation action | AI cannot score, diagnose, select eligibility/templates, mutate a SupportPlan, or prescribe; Care decides and the user confirms |
| Specialist read | Return only entries/indicators allowed by a current Care decision | Exact subject/scope/range/entry authorization; fail closed; minimized audited response |
| Dataset governance | Import licensed de-identified datasets and immutable versions | Production journals excluded by default; private assets; validation rejects label/schema/leakage violations |
| Benchmark | Run the same split/config through configured providers; start with OpenAI and Gemini, with PhoBERT optional later | Reproducible versions/split; per-class metrics, latency, errors and cost; retry never duplicates predictions; no dependency on the deferred worker |

## Implementation design

- Feature slices: `journals`, `analysis-jobs`, `llm-providers`, `analysis-results`, `longitudinal-analysis`, `datasets`, `benchmarks`, `consented-access`.
- Runtime: Node.js 22 or newer, strict TypeScript, NestJS 11, Zod, official MongoDB driver, `migrate-mongo`, `pg`, KafkaJS, Pino, OpenTelemetry, Vitest, and Testcontainers as defined in `docs/nodejs-service-stack.md`.
- Define OpenAPI, Kafka JSON Schemas, provider output schema, and MongoDB validation/migrations before handlers. TypeScript strict plus runtime validation is mandatory.
- Use recoverable Mongo publication and PostgreSQL transactional outbox as appropriate; never claim cross-store atomicity. Model job states and reconciliation explicitly.
- AI adapters receive minimized decrypted content only for the approved operation; raw provider responses and hidden reasoning are never persisted.
- The normalized result follows `MB-AI-COMPANION-001`: optional summary/sentiment, context and emotion indicators, themes, preferences, barriers, `modelConfidence`, one allow-listed `suggestedAction`, and complete provider/model/prompt/schema provenance.
- Longitudinal results retain exact source revisions, bounded periods, comparison direction, coverage sufficiency, and provenance. Journal/AI supplies this non-standardized evidence to Care; Care composes the four-dimensional Reassessment Summary and owns every SupportPlan decision.
- Initial AI provider and benchmark work remains provider-neutral but does not require `phobert-worker`; ADR 0011 defines the separate activation gate for that optional baseline.

## Ordered tasks

- [x] JAI-01 Scaffold the NestJS/TypeScript service with feature modules, typed configuration, health/readiness, lint, test, and build commands.
- [ ] JAI-02 Resolve journal retention/encryption plus dataset license/edit and benchmark label policies; AI provider/result retention is fixed by ADR 0015.
- [ ] JAI-03 Define journal/AI Companion analysis/dataset/benchmark OpenAPI and the ADR 0015 normalized provider-result schema.
- [ ] JAI-04 Define analysis command/result schemas and Care consent/structured-indicator contracts.
- [ ] JAI-05 Add `migrate-mongo` validators/indexes plus PostgreSQL job/dataset `node-pg-migrate` history and data documentation.
- [x] JAI-06 Implement encrypted journal revisions, authorization, pagination and deletion.
- [ ] JAI-07 Implement consent-gated idempotent analysis orchestration, adapters, retry/dead-letter and reconciliation.
- [ ] JAI-08 Implement dataset import/versioning and reproducible benchmark coordination.
- [ ] JAI-09 Verify malformed AI output, prompt injection boundary, timeout/cost limit, duplicates/reordering, cross-store recovery and deletion.
- [ ] JAI-10 Add observability/configuration, module README, and pass Node/contract/Mongo/PostgreSQL gates.

## MB-236 delivery boundary

MB-236 delivers JAI-06 plus the journal-only portions of JAI-03 and JAI-05 for create/list/detail/revise/delete. The broader JAI-03 and JAI-05 items remain open because analysis, datasets, benchmarks, and PostgreSQL job history are not part of this delivery. It does not call an AI provider, create analysis jobs, publish Kafka analysis commands, import datasets, or run benchmarks.
