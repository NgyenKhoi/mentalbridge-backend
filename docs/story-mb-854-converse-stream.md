# MB-854 — ConverseStream transport and early-audio boundary

User execution authority is recorded in Jira MB-854 comment10470. It permits
reviewed source/controlled staging rollout, not a provider call before a fresh
confirmed window and current preflight. Existing closed windows/history are
not reused. This slice implements actual incoming Bedrock streaming behind a
default-OFF Voice-only flag; it does not claim the whole Voice path streams early.

## Source slices and preserved boundaries

1. Journal provider transport + typed configuration + wire/provider regressions:
   native fetch to `/model/{pinned-global-model}/converse-stream`, existing
   Bearer authentication, same prompt/body/CountTokens/output caps and deadline.
   No SDK/dependency, REST/event schema or migration change. No Text routing change.
2. Delivery evidence/runbook: privacy, benchmark entry/exit and the explicit
   early-audio policy gate. No synthetic benchmark replaces real baseline.

The decoder consumes the actual binary Amazon EventStream protocol. Prelude
and message CRC32, frame/header lengths and UTF8/JSON are checked before use.
It accepts an assistant message, ordered text-only content blocks, one model
stop, one usage metadata event and complete EOF. Text may begin with a delta
without a contentBlockStart, as in text-only ConverseStream. Tool/reasoning or
unknown events, duplicate/out-of-order/truncated/corrupt frames fail closed.
Limits are1MiB/frame,16KiB headers,2MiB/wire response,4096events,16text blocks
and65536text code units; these are parser bounds, not new request allowances.

Native AbortSignal propagates the original request/call authority lifetime.
Stalled readers are cancelled on cancellation/deadline; late results cannot
publish. Body transport errors, wire/protocol errors and provider exceptions
are separate closed categories; error/body/header content is not logged.
No redirect, retry or fallback is introduced. The existing no-retry policy
is preserved rather than treating an unknown streamed inference as unspent.

Provider usage is observed at the metadata event, once, before schema/safety
validation. Missing usage/truncated metadata fails the stream; it is not cost0.
The existing turn service additionally consumes the final returned usage without
double counting. Reservations and all existing current-authority rounds remain.

## What telemetry measures

- `bedrock_requested`: request begins after the unchanged CountTokens gate.
- `bedrock_response_received`: response headers, not first token.
- `bedrock_stream_first_text`: first nonempty provider text delta, not validated
  speech. It may be JSON syntax; define TTFT as first model text delta explicitly.
- `bedrock_stream_message_stopped`: complete model message boundary and closed
  END_TURN/MAX_TOKENS/STOP_SEQUENCE/OTHER/MISSING reason.
- `bedrock_stream_completed`: metadata and EOF; not a synonym for model finish.
- Existing JSON/schema/safety, publication authorization, reply registration,
  Agent TTS/first-audio and independent browser first-heard remain separate.

Only phases/enums/counts/opaque validated correlation and bounded elapsed time
go to existing telemetry. No delta, transcript, audio, request/response header,
body, raw provider error, key, owner identity or room address is added.
Observer failures cannot turn a denied response into an authorized one.

## Explicit blocker: audio before model completion

The current Voice request/response contract is one structured reply. It accepts
only END_TURN, then strict full JSON/schema, Vietnamese/message checks and the
whole-message safety rules, followed by a fresh publication authorization and
durable TTS reservation/reply registration. Agent receives only that result.

A complete prefix is not the full structured object or END_TURN. Per-sentence
pattern checks also cannot establish whole-message safety: a prohibited phrase
can cross a chunk boundary, and a later field/text can invalidate an earlier
prefix. Model-provided NONE fields cannot grant safety authority. TTS streamed
after this full validation is already supported and does not meet the new
first-audio-before-LLM-finish acceptance condition.

Therefore this source deliberately returns no partial reply. Unit/native-HTTP
tests verify first delta arrives while the provider promise stays pending,
including after metadata until EOF, and that invalid/abnormal final output never
reaches publication. **Early-audio remains BLOCKED, not STREAMING DONE.**

A separately reviewed prospective contract/policy would need independently
complete, server-validated publishable speech units, sequence/replay/usage/TTS
reservation and current-authorization semantics, cancellation of any later
invalid unit, explicit crisis/guidance equivalence and acknowledgement that
already-heard audio cannot be withdrawn. This slice neither accepts such a
policy nor silently adds a detector, second model, stop sequence or API protocol.
It completes the safe streaming foundation allowed by the user while surfacing
the exact gate for actual early playback.

## Rollout and real measurement gates

Baseline rollout remains the normal dev→staging promotion, Code Owner review,
green required CI and the agreed020 migration order versus member PR299.
The source flag defaults false. Current staging was put in maintenance with
Voice admission OFF and Agent stopped before source rollout; no new provider
window was opened by that operation. Do not re-enable an old unfenced binary.

After reviewed source merge/promotion/deploy, verify every Journal/Agent image,
policy hash, allocation/cumulative and concurrency limits, exact1900/300 token
ceilings, current consent/entitlement/quota, no recording/export, actual eligible
credit/no-overage and confirmed teardown. `InvokeModelWithResponseStream`
permission is required; no automatic IAM/key edit or chargeable permission probe.

The fixed USD0.50/2sessions/concurrency1/12turns/480seconds/4000TTS-character
budget is cumulative across baseline/comparison, not per variant. Final date/T0
and window require separate confirmation after preflight. Do not consume a
baseline window while an unreviewed comparison build still needs hours of work;
both approved variants and reader/collector must be ready before one fixed
window can safely include them. No reset/extension/replacement is implied.

True first-audible is an independent browser/listener observation, not a server
first chunk/PLAYBACK_STARTED event. Match device/network/context/utterance type
and length, use metadata-only physical speech-end/first-heard markers, report
clock/reaction uncertainty, n/p50 definition/nearest-rank p95/worst/errors and
interruption quality. Preserve failures/NOT_REACHED in denominators. With n<=12,
p95 is the maximum and cannot establish a population SLO. No30% improvement or
before/after number is claimed until such a real comparable capture exists.

This foundation can measure actual model TTFT but cannot, by itself, make audio
precede full LLM output or promise faster E2E. MB863's real transport correlation
is still needed before choosing a safe authorization latency optimization.

## Verification and references

Tests exercise byte-split Vietnamese UTF8, native loopback HTTP streaming,
checksums/order/size bounds, malformed/duplicate/truncated events, provider/body
errors, cancellation/deadline, once-only usage after failure, no private content,
default-OFF/Text compatibility, input caps and all complete response rejections.
The unchanged real-Mongo regression suite covers consent withdrawal/publication,
quota/campaign reservations/races/replay, lease/reconnect and teardown.

- [AWS ConverseStream API](https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_ConverseStream.html)
  specifies event payloads and `bedrock:InvokeModelWithResponseStream`.
- [AWS EventStream encoding](https://docs.aws.amazon.com/transcribe/latest/dg/streaming-setting-up.html#streaming-event-stream)
  specifies prelude/header/payload and CRC32 framing; this is framing reference,
  not a change to MentalBridge's STT provider.
- [Current Voice API](../contracts/openapi/journal-ai-service-v1.yaml),
  [Voice policy](policies/companion-voice-policy-v1.md),
  [MB863 profiling](story-mb-863-authorization-profiling.md).
