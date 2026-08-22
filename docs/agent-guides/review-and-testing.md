# Review and Testing

## Test pyramid by boundary

| Scope | Required focus |
| --- | --- |
| Domain unit | scoring bands, safety flags, state transitions, consent scopes, retry classification, pure mappings |
| Application/component | transaction boundaries, authorization, idempotency, outbox creation, error mapping |
| Repository integration | real PostgreSQL migrations, constraints, optimistic/pessimistic concurrency, indexes/query behavior |
| REST provider contract | implementation matches OpenAPI for success and every documented error |
| REST consumer contract | Spring and Node callers handle the provider contract, timeouts, Problem Details, optional fields, evolution |
| Message contract | producer and consumer validate the same schema/version and reject poison payloads safely |
| Broker integration | real Kafka topics, keys/partitions, offset-after-commit, retry, dead letter, duplicate delivery |
| Realtime integration | WebSocket authentication, reconnect, duplicate client message, ordering, Redis fan-out, notification delivery |
| End-to-end | critical workflows across gateway/services with synthetic data and controlled dependencies |

Use containerized PostgreSQL, Kafka, and Redis for integration tests. In-memory database or mocked infrastructure tests may supplement but do not replace compatibility and concurrency tests.

## Required data-consistency scenarios

- Two simultaneous booking requests for one slot result in exactly one active appointment.
- Repeating a REST command with the same idempotency key returns the same outcome and does not duplicate state/outbox messages.
- Aggregate update and outbox insertion both commit or both roll back.
- The relay can crash before/after Kafka acknowledgment without losing an event; consumers tolerate duplicates.
- A consumer crash before acknowledgment does not duplicate the committed side effect.
- Optimistic-lock conflicts are surfaced as stable conflicts or deliberately retried at the use-case boundary.
- Consent revoked concurrently with a specialist read fails closed according to the documented owner decision.
- Stale or reordered projection events cannot overwrite newer aggregate versions.
- Deletion retries reach the same final state without restoring or duplicating data.

## Spring-to-Node REST scenarios

Test a Spring consumer against a Node provider contract and a Node consumer against a Spring provider contract. Cover:

- authentication/service identity, role and resource authorization;
- valid response plus every documented `4xx`/`5xx` Problem Detail;
- UUID and 64-bit string identifiers, timestamps with offsets, `null`/missing fields, unknown fields and enum evolution;
- connection refusal, timeout before send, timeout after a mutation may have been accepted, malformed/truncated JSON;
- retries only for eligible idempotent operations;
- breaker closed/open/half-open behavior and safe fallback;
- correlation and trace propagation.

Paid AI, email, push, or other provider APIs are not called by CI. Use synthetic fixtures and provider sandboxes only in explicit integration environments.

## `dev` CI baseline

The backend repository currently has no required GitHub Actions status check for pull requests targeting `dev`. Every feature branch must be synchronized with `origin/dev`, reviewed, and verified locally before merge; the absence of a CI status is not a passing result.

After the current bootstrap integration is stable, the repository owner adds basic CI for pull requests targeting `dev`. The first gate covers dependency installation, formatting, lint/static analysis, typecheck, unit tests, contract and migration static checks, and build for affected modules. Branch protection is enabled after the workflow is stable. Integration tests required by a module remain part of local review evidence until they are represented reliably in CI.

The CI introduction PR updates every status statement that describes CI as absent.

## Configuration scenarios

- `mvn clean install` passes without a developer `.env` file.
- A local Spring context resolves a documented `.env` key when the real process environment is absent.
- A real environment variable overrides the `.env` value.
- Test-owned/Testcontainers properties override developer configuration and cannot accidentally target a shared local database.
- Missing required runtime configuration fails startup with a stable safe error that does not expose values.
- CI/production profiles disable dotenv loading and never depend on `.env`.
- `.env.example`, module README, and typed configuration properties contain the same key set and semantics.

## Review checklist

Before declaring completion, inspect the full diff and answer yes to each applicable item:

- The change belongs to one clear owner and does not read another service's storage.
- REST/OpenAPI and event schemas match implementation and are backward compatible or versioned.
- No database transaction spans a remote REST/provider call.
- Retry, timeout, circuit breaker, idempotency, and fallback behavior are explicit and tested.
- Message publishing uses outbox when coupled to state; consumption is idempotent and ack occurs after commit.
- Database constraints enforce important invariants; every table/field has a useful entry in the Markdown data dictionary.
- Payment IPN tests use complete official MoMo fixtures and cover every required/optional field, missing/extra fields, invalid signatures, partner/order/request/amount mismatch, duplicate delivery, result finality, and the HTTP 204/15-second acknowledgement contract.
- Authorization is enforced in the data owner and uncertainty fails closed.
- Logs/events/errors contain no token, password, raw journal/chat text, assessment answers, private object URL, or payment-provider payload.
- Feature structure remains cohesive; no god service, dumping-ground `shared`, copied cross-service DTO, unused abstraction, or explanatory production-code comment was added.
- Tests cover happy path, validation, authorization, conflict/concurrency, duplicate/retry, and dependency failure.
- Formatting, static analysis, tests, migration validation, contract checks, and build pass for every affected module.
- Before the required `dev` CI gate exists, exact local commands/results are recorded; afterward the required GitHub status also passes.

If a required check cannot run, document the exact command, failure, and impact. Do not call the implementation complete merely because the missing dependency belongs to another module.
