# Journal/AI Service specification

## Business boundary

Journal/AI owns encrypted journal entries/revisions, structured analysis results, analysis-job orchestration, provider provenance, governed dataset metadata/samples, and benchmark coordination. MongoDB owns journals/results/samples; PostgreSQL owns durable job/dataset/run metadata and outbox. Care remains the current AI-consent and risk authority.

## Use cases and acceptance

| Capability | Main behavior | Acceptance |
| --- | --- | --- |
| Journal | Create, read, edit/revise, list and delete private entries | Owner authorization; bounded cursor history; encryption metadata; edits create revisions and invalidate current analysis; no plaintext logs/indexes |
| Analysis | Check current consent, create one job per requested revision, normalize provider output | No consent saves journal without analysis; duplicate request is idempotent; schema-invalid/provider failure is retryable/terminal while journal remains readable |
| Specialist read | Return only entries/indicators allowed by a current Care decision | Exact subject/scope/range/entry authorization; fail closed; minimized audited response |
| Dataset governance | Import licensed de-identified datasets and immutable versions | Production journals excluded by default; private assets; validation rejects label/schema/leakage violations |
| Benchmark | Run same split/config through LLM and PhoBERT and compare | Reproducible versions/split; per-class metrics, latency, errors and cost; retry never duplicates predictions |

## Implementation design

- Feature slices: `journals`, `analysis-jobs`, `llm-providers`, `analysis-results`, `datasets`, `benchmarks`, `consented-access`.
- Runtime: Node.js 22 or newer, strict TypeScript, NestJS 11, Zod, official MongoDB driver, `migrate-mongo`, `pg`, KafkaJS, Pino, OpenTelemetry, Vitest, and Testcontainers as defined in `docs/nodejs-service-stack.md`.
- Define OpenAPI, Kafka JSON Schemas, provider output schema, and MongoDB validation/migrations before handlers. TypeScript strict plus runtime validation is mandatory.
- Use recoverable Mongo publication and PostgreSQL transactional outbox as appropriate; never claim cross-store atomicity. Model job states and reconciliation explicitly.
- AI adapters receive minimized decrypted content only for the approved operation; no chain-of-thought/raw provider response persistence by default.

## Ordered tasks

- [x] JAI-01 Scaffold the NestJS/TypeScript service with feature modules, typed configuration, health/readiness, lint, test, and build commands.
- [ ] JAI-02 Resolve provider retention, journal retention/encryption, dataset license/edit and benchmark label policies.
- [ ] JAI-03 Define journal/analysis/dataset/benchmark OpenAPI and provider result schema.
- [ ] JAI-04 Define analysis command/result schemas and Care consent/structured-indicator contracts.
- [ ] JAI-05 Add `migrate-mongo` validators/indexes plus PostgreSQL job/dataset `node-pg-migrate` history and data documentation.
- [ ] JAI-06 Implement encrypted journal revisions, authorization, pagination and deletion.
- [ ] JAI-07 Implement consent-gated idempotent analysis orchestration, adapters, retry/dead-letter and reconciliation.
- [ ] JAI-08 Implement dataset import/versioning and reproducible benchmark coordination.
- [ ] JAI-09 Verify malformed AI output, prompt injection boundary, timeout/cost limit, duplicates/reordering, cross-store recovery and deletion.
- [ ] JAI-10 Add observability/configuration, module README, and pass Node/contract/Mongo/PostgreSQL gates.

## Sprint 1 boundary

Sprint 1 covers JAI-01, the journal-only part of JAI-03/JAI-05, and JAI-06 for create/list/detail/revise/delete. It does not call an AI provider, create analysis jobs, publish Kafka analysis commands, import datasets, or run benchmarks.
