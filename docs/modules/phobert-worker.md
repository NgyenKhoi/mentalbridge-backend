# PhoBERT Worker specification

## Status

`PROPOSED / DEFERRED` under ADR 0011. This optional Vietnamese NLP benchmark baseline is not required for the initial OpenAI/Gemini implementation, current Sprint scope, Compose topology, readiness, or release flow. No runtime implementation currently exists.

## Business boundary

If activated, PhoBERT Worker executes inference for an explicitly versioned narrow classification command and emits a structured result. It owns no account, journal, consent, safety/support, dataset, benchmark, or notification truth; it exposes no business CRUD API. Journal/AI owns job/run state, dataset access authorization, provider comparison, and normalized analysis results.

Pretrained PhoBERT is not treated as an emotion detector. Activation requires an approved label taxonomy, governed labeled dataset and evaluation split, deterministic preprocessing, and a compatible fine-tuned checkpoint with immutable version and checksum.

## Use cases and acceptance

| Capability | Main behavior | Acceptance |
| --- | --- | --- |
| Inference | Validate command, obtain approved minimized input, preprocess and run pinned model | Unsupported schema/model fails safely; no business lookup; deterministic preprocessing/version provenance |
| Result publication | Emit structured prediction, labels/scores, latency and safe error code | No raw text/token/secret; result validates shared schema; aggregate/message identifiers preserved |
| Retry/idempotency | Tolerate duplicate, delayed and poison commands | Duplicate job does not create conflicting output; transient retry bounded; poison goes dead letter; offset after successful outcome publication |
| Model operations | Load verified artifact and report readiness | Hash/version verified; bounded memory/concurrency; failed model load makes worker unready without consuming jobs |

## Implementation design

- Components: command validation, input adapter, preprocessing, model adapter, result mapper, Kafka consumer/producer, telemetry.
- Event JSON Schema is the cross-language source of truth. Model artifacts and configuration are immutable inputs; business state remains in Journal/AI.
- Tests use synthetic Vietnamese text and a lightweight deterministic model double for CI; explicit integration environments may test the real pinned artifact.

## Deferred activation tasks

These tasks are not part of the current runtime or Sprint scope.

- [ ] PB-01 Scaffold Python packaging, formatting, type checking, tests and container build.
- [ ] PB-02 Agree command/result, label mapping, preprocessing, model artifact and input-access contracts with Journal/AI.
- [ ] PB-03 Implement runtime schema validation and safe Kafka retry/dead-letter/idempotency flow.
- [ ] PB-04 Implement versioned preprocessing/model adapter and structured result mapping.
- [ ] PB-05 Verify duplicate/delayed/poison messages, corrupt artifact, timeout/resource limit, invalid input and publication failure.
- [ ] PB-06 Add readiness/metrics/configuration, README, reproducibility evidence and pass Python/contract/container gates.
