# Engineering Rules

These rules apply to all backend services unless an accepted ADR documents an exception.

## 1. Repository and service structure

- Each deployable has an owner, README, Dockerfile, health check, OpenAPI contract, migrations, and tests; Realtime Service also owns a versioned WebSocket JSON contract.
- Spring services use package-by-feature inside explicit layers: `api`, `application`, `domain`, `infrastructure`.
- Domain code does not import controllers, persistence entities, provider SDKs, or framework-specific transport models.
- A service may consume another service's API/event contract, never its tables, repositories, or internal classes.
- Shared libraries contain cross-cutting primitives only; do not create a shared domain-model library.

## 2. API rules

- Base path: `/api/v1`; breaking changes require a new major path or compatible migration plan.
- JSON uses `camelCase`; database identifiers use `snake_case`; timestamps are ISO-8601 UTC.
- IDs are UUIDs and are opaque to clients. Pagination is bounded and stable; cursor pagination is preferred for journals/chat/audit.
- Use RFC 9457 problem details with stable error codes; do not leak stack traces or provider payloads.
- Validate size, type, format, enum values, and cross-field invariants at the boundary.
- `POST` commands that may be retried use `Idempotency-Key`; duplicate requests return the original outcome.
- OpenAPI examples must contain synthetic data only.

## 3. Data rules

- PostgreSQL migrations are append-only after merge and owned per service through Liquibase changelogs. MongoDB-owning NestJS services use versioned `migrate-mongo` migrations for collection validation, indexes, and controlled data changes.
- Every mutable relational record has `created_at`, `updated_at`, and optimistic `version` where concurrent edits matter.
- Money is not in current scope; if introduced, use integer minor units plus ISO currency, never floating point.
- Store instants as `timestamptz` UTC. Store the originating IANA timezone separately where scheduling requires it.
- Use explicit check, unique, foreign-key, and not-null constraints. Application validation does not replace database integrity.
- Use soft deletion only when product/retention semantics require recoverability. Otherwise use controlled hard deletion plus audit.
- MongoDB documents carry `schemaVersion`, timestamps, owner IDs, and indexes defined in version-controlled migrations/scripts.
- Raw sensitive content must not be copied into analytics projections, events, or logs.

## 3.1 Configuration rules

- Spring Boot 4 services follow `docs/agent-guides/environment-configuration.md` and use the approved `springboot4-dotenv` integration for local development.
- `.env` is ignored and optional; `.env.example` is committed and updated with every configuration-key change.
- CI and production disable dotenv loading and inject real environment/secrets externally.
- Tests are deterministic without a developer `.env`; integration dependencies come from controlled test infrastructure.

## 4. Security rules

- Deny access by default. Every endpoint declares allowed roles and enforces resource ownership/consent below the controller.
- Never trust role, score, risk, specialist status, price, owner ID, or consent claims supplied by a client.
- Tokens, passwords, secrets, journal/chat text, assessment answers, and verification documents are prohibited in logs.
- All sensitive reads and administrative writes produce audit events.
- Test broken-object-level authorization for every resource endpoint.
- Dependency and container scanning run in CI; critical findings block release unless risk acceptance is documented.
- Production/demo datasets never use real participant mental-health content without explicit approved consent and governance.

## 5. AI rules

- User content is untrusted input, not instructions. Prompts clearly delimit it and tools/actions are not granted to the model.
- Provider requests use minimum necessary text and settings that prohibit provider training/retention where available.
- Prompts, model names, parameters, response schemas, and policy versions are versioned.
- Model output is parsed against a strict schema, range-checked, and treated as fallible.
- Do not store or expose chain-of-thought. Store concise reason codes/explanations intended for the user.
- AI never computes PHQ-9/GAD-7 scores, diagnoses, or solely decides severe-risk handling.
- Model evaluation reports per-class metrics and confusion matrices, not accuracy alone.

## 6. Event and job rules

- Publish only through an outbox when an event must correspond to a database transaction.
- Consumers are idempotent and record processed event IDs or use an equivalent deduplication strategy.
- Event schemas are backward compatible; add fields as optional and version breaking changes.
- Retries are bounded. Permanent failures enter an inspectable dead-letter state with no raw sensitive payload in operator UI.
- Scheduled jobs use distributed locking or idempotent claims so multiple replicas do not duplicate work.

## 7. Testing and quality gates

- Unit tests cover scoring bands, all safety flags, risk-policy matrices, state transitions, and consent scope evaluation.
- Integration tests use real PostgreSQL/MongoDB containers for repositories, migrations, uniqueness, and booking races.
- Contract tests cover gateway/service and event schemas.
- Security tests cover role matrix, ownership, consent revocation, Realtime Service WebSocket subscriptions, and admin field filtering.
- Cross-language contract tests verify Spring-to-NestJS and NestJS-to-Spring REST/JSON DTO compatibility.
- Kafka integration tests cover outbox publication, keys/partitions, duplicate consumption, offset commit, retry topics, and dead-letter handling.
- Redis/realtime tests cover TTL presence, reconnect/idempotency, cross-instance fan-out, and recovery after Redis loss.
- AI adapters use recorded synthetic fixtures; CI must not call paid external APIs.
- Each release tests backup/restore procedure and the severe-risk path with AI and broker unavailable.
- CI runs formatter, static analysis, tests, migration validation, dependency scan, image build, and secret scan.

## 8. Pull request definition of done

- Acceptance criteria and relevant threat/privacy impact are stated.
- API/event/database changes are documented and backward compatible or have a migration plan.
- Tests cover happy path, authorization, invalid input, and important failure/retry cases.
- Logs/metrics are useful without sensitive content.
- OpenAPI and domain documentation are updated.
- No unresolved critical vulnerability or plaintext secret is introduced.

## 9. Naming conventions

| Item | Convention | Example |
| --- | --- | --- |
| Java class | `PascalCase` | `SubmitAssessmentUseCase` |
| Java method/field | `camelCase` | `consentGrantId` |
| REST resource | plural kebab-case | `/assessment-submissions` |
| PostgreSQL | singular snake_case table | `assessment_submission` |
| MongoDB | plural snake_case collection | `journal_entries` |
| Event | past-tense PascalCase | `RiskClassified` |
| Metric | snake_case with unit suffix | `ai_request_duration_seconds` |
| Error code | upper snake case | `CONSENT_SCOPE_REQUIRED` |
