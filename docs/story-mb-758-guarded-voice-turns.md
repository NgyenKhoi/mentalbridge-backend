# MB-758 guarded voice turns

Story: [MB-758](https://vunguyenkhoi47.atlassian.net/browse/MB-758), MB-754 AI Voice
Companion; owner Journal/AI, Thảo Nguyên Trần. Baseline `8c514108` is merged MB-757.
Status: implementation in progress; no live provider success or deployment claim.

## Brainstorm and implementation contract

Reuse the existing Companion route, prompt and normalized assistant validation.
Do not call persisted text `send`, its context assembler or quota repository.
Only final vi-VN STT in the frozen MB-755 internal turn contract is accepted.
Both consents, current entitlement, active attempt/lease, remaining admitted
duration and the approved pinned Claude Bedrock route must be current before
generation and publication. The common 500-character output validator is stricter
than the 1,000-character voice maximum and stays backward compatible.

Keep up to four current-call pairs in process RAM, with a conservative UTF-8 byte
budget of 2,000 for serialized context instead of relying on characters/4 for
Vietnamese. This deliberately admits less history than the 2,000-token ceiling.
No model prompt-cache checkpoint is sent. Only keyed intent/provenance digests,
opaque IDs, sequence and original retention timestamps may be stored to prevent
duplicate provider effects. Missing RAM on retry or another replica is stale,
never permission to reconstruct content or invoke the model again. Live routing
must preserve affinity to the current context process; restart ends that context.

Cancellation, termination, revocation and lease expiry abort/discard provider
work and clear RAM. A stable opaque reply ID registers with MB-757 before TTS;
generation/replay itself never commits the first-spoken-reply session count.
Existing owner policy and text behavior stay unchanged. No Care, Consultation,
SupportPlan, worker, frontend, speech provider or account mutation belongs here.
Existing AWS/LiveKit accounts suffice; account/privacy/model/worker/device gates
remain opt-in downstream, and CI uses deterministic providers only.

## Ordered commit slices

1. Reuse the Companion provider boundary: shared route selector and assistant
   validator, optional cancellation and voice presentation hints. Verify existing
   chat tests plus focused provider request/cancellation/safety tests.
2. Guarded turn vertical slice: frozen REST operation, module lifecycle, closed
   input, cross-process metadata fence, bounded RAM, authorization/publication/
   cancellation and migration 015 with paired model/dictionary. Actual Nest +
   real Mongo integration tests require these to commit together rather than
   depending on a later wiring commit. Verify concurrency, stale replay, HTTP
   response contracts and absence of transcript/Text-quota persistence.
3. Operational handoff, acceptance evidence and traceability. Run affected
   Journal gates, refresh dev, review task-only diff/overlap, push/create PR and
   wait CI green. No unrelated runtime change belongs in this documentation slice.

## Verification

Pending implementation. Exact local results and remote head/run will be recorded
before handoff. No cloud/account access, paid inference or real speech is run.
