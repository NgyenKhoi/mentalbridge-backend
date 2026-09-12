# ADR 0011: Defer PhoBERT to an optional benchmark baseline

- Status: Accepted
- Date: 2026-09-11
- Supersedes: ADR 0001 only where it requires `phobert-worker` as a baseline deployable; the service ownership and transport decisions in ADR 0001 remain accepted.

## Context

Journal/AI owns analysis jobs, governed datasets, benchmark runs, provider provenance, and normalized analysis results. The repository has an executable Journal/AI foundation but no `phobert-worker` runtime, accepted PhoBERT command/result contract, classification task, label mapping, preprocessing contract, or approved model artifact.

PhoBERT is a pretrained Vietnamese language model, not an application-specific emotion classifier by itself. Producing MentalBridge labels requires an approved narrow task and a compatible fine-tuned checkpoint backed by governed labeled data. Implementing that path now would add dataset preparation, fine-tuning or checkpoint selection, artifact governance, a Python runtime, Kafka integration, and separate deployment work before it can provide a valid comparison.

The current product priority is a consent-gated, provider-neutral Journal/AI workflow and a reproducible benchmark across the initially selected OpenAI and Gemini adapters. Care scoring, safety, and support routing must remain independent of every AI provider.

## Decision

The initial Journal/AI implementation and benchmark scope uses configured OpenAI and Gemini provider adapters. Journal/AI remains the sole owner of analysis orchestration, datasets, benchmark state, and normalized results.

`phobert-worker` is `PROPOSED / DEFERRED`. It is an optional future Vietnamese NLP baseline and is not required for the initial AI runtime, current Sprint work, Docker Compose, service readiness, or the critical product flow.

PhoBERT may be activated only after product and research review approve all of the following:

- one narrow classification task and its user-facing/non-user-facing purpose;
- a versioned label taxonomy and mapping;
- dataset license, provenance, de-identification, leakage controls, and evaluation split;
- a compatible fine-tuned checkpoint with immutable version, checksum, and reproducibility evidence;
- deterministic preprocessing and minimized input-access rules;
- versioned command/result contracts, timeout, retry, idempotency, and dead-letter behavior;
- resource, deployment, observability, and benchmark acceptance thresholds.

A pretrained base checkpoint must not be presented as an emotion detector or allowed to invent labels. If activated, the Python worker performs inference only, owns no business data, and returns a structured prediction to Journal/AI. It does not query account, journal, consent, safety/support, dataset, benchmark, or notification stores. It cannot score PHQ-9/GAD-7 or affect Care safety and routing decisions.

## Consequences

- The current application and benchmark can progress without a Python runtime or PhoBERT artifact.
- OpenAI and Gemini can be compared through the same governed dataset, split, output schema, latency, error, safety, and cost measurements.
- WBS benchmark requirements remain owned by Journal/AI and provider-neutral; PhoBERT remains an optional third baseline rather than a release dependency.
- Documentation and delivery plans distinguish the executable Journal/AI foundation from the deferred worker.
- Activating PhoBERT later requires a follow-up ADR or an accepted amendment to this record plus contract-first implementation evidence.

## Rejected alternatives

- Implement the worker before defining the task, labels, and checkpoint: rejected because the output would not be reproducible or meaningful.
- Treat pretrained PhoBERT as an emotion classifier without fine-tuning: rejected because the base model does not supply MentalBridge's application labels.
- Remove PhoBERT permanently: rejected because a governed Vietnamese-specific baseline may still strengthen a future controlled benchmark.
- Make Care depend on the selected AI model: rejected because screening, safety, and support routing are deterministic owner-local behavior.
