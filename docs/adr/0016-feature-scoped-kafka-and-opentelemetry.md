# ADR 0016: Feature-scoped Kafka and OpenTelemetry

## Status

Accepted — 2026-09-14

## Context

Kafka and OpenTelemetry are useful platform capabilities, but installing them
in every service or foundational feature increases dependencies, local startup
time, test duration, deployment configuration, and operational work before the
product flow benefits from them. Story 6101 is a synchronous specialist-profile
approval flow owned entirely by Consultation and is a concrete example where a
database transaction plus REST is sufficient.

## Decision

The default implementation is the smallest owner-local path that preserves the
approved business behavior: local database transaction, REST contract,
structured logs, correlation IDs, health/readiness, and focused Prometheus
metrics.

Kafka is introduced only when the feature documents at least one real need:

- work is accepted now and durably completes later;
- one committed fact has an independent consumer or required fan-out;
- a projection/audit feed requires retention and replay; or
- a cross-owner workflow cannot correctly be represented by synchronous REST.

Possible future consumers are not sufficient justification. A feature without
one of these needs adds no Kafka library, broker configuration, topic/schema,
producer/consumer, outbox/inbox, retry/dead-letter machinery, or Kafka test
container. When Kafka is selected, the existing outbox, idempotency, minimal
payload, and at-least-once rules remain mandatory.

OpenTelemetry is also feature-scoped. It is introduced only when a distributed
request, external provider call, or asynchronous workflow has a demonstrated
traceability/debugging or SLO need that correlation IDs, structured logs, and
metrics do not adequately cover. The implementing task must define the trace
boundary, sampling/configuration, expected diagnostic question, and sensitive
field exclusions. No raw journal, assessment answer, private chat, token, or
secret may enter spans.

## Consequences

- Synchronous slices remain smaller and faster to build, run, and test.
- Infrastructure tests match dependencies actually used by the feature.
- Kafka and distributed tracing remain available where their operational value
  is explicit rather than becoming architecture ceremony.
- ADR 0001 and ADR 0006 continue to define how Kafka/OpenTelemetry are used
  when selected; this ADR supersedes any interpretation that they must be
  installed in every service or implemented feature.
