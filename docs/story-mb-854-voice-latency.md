# MB-854 — AI Voice latency investigation and optimization

Date: 2026-11-15, Asia/Ho_Chi_Minh. Owner: Journal/AI and Voice Agent.
[Jira MB-854](https://vunguyenkhoi47.atlassian.net/browse/MB-854).

The supplied staging logs prove a 1.627–1.678 second final-transcript dispatch
delay, multiple authorization intervals, and a non-streaming validated-response
barrier before TTS. This change reduces avoidable scheduling and independent
LiveKit read serialization. It preserves the response-validation barrier.
Controlled source benchmarks improve; **after-deployment staging latency and
the proposed 1.5 second p50 / 3 second p95 targets remain unverified**.

## Deployed revision and evidence boundary

The server operator supplied clean-checkout/build records and filtered JSONL
events. These bind the described running images to source; this investigation
did not authenticate to EC2 or rebuild/deploy its containers. Fetching `dev`
or observing a successful quality workflow alone does not prove deployment.

| Component | Operator-reported source / branch                              | Locally built running image ID                                            |
| --------- | -------------------------------------------------------------- | ------------------------------------------------------------------------- |
| Journal   | backend `81ee08bd5ced530cc461459793b5917b48f59a06`, `staging`  | `sha256:f2936dc5363a7b4dc01b4f0a8e7c180a056fd2a8cc0773d939e32782cae3a176` |
| Web       | frontend `2d0847182ccd8a7a12772269c5b83a3805084aea`, `staging` | `sha256:96a97a3d5c4e8f8eeac6670afa17adf28911f91862af4ec52d514577a3ad4b69` |
| Agent     | same backend `81ee08bd`, self-hosted EC2                       | `sha256:d7e3198389fc98eae7dc63df46acef3a6fb311ecff04df7235724ba5fd4dd51f` |

These are image IDs, not registry digests: images were built on EC2 without
registry publication. The Agent runs on EC2 `ap-southeast-1a`, connected to
LiveKit Cloud; it is not a LiveKit Hosted Agent. Its dispatch name is
`mentalbridge-ai-voice-v1`, Agents SDK 1.9.4, RTC Node 1.2.1, Node 22.23.5.
Journal's Bedrock request region is `ap-southeast-1`; a global inference profile
does not guarantee Singapore-only model processing. LiveKit/STT/TTS compute
regions and EC2 CPU/memory limits were not established. Effective TTS voice
UUID was not included in this operator metadata; the change preserves it.

The operator confirms `AI_VOICE_FINAL_AGGREGATION_MS` was unset, selecting the
old 1500 ms default. The new branch was isolated from the closed pilot and
synchronized with current `dev`; its base is not substituted for these running
image revisions. No frontend change is needed for the optimizations below.

## Observed pipeline and timing

The supplied JSONL files contain one call with three generated replies. Times
below are milliseconds. Per-request Journal monotonic timings and per-attempt
Agent timings are correlated by existing turn/attempt identity. Only minimized
numeric aggregates are committed in [the evidence JSON](evidence/mb-854-voice-latency.json).
Raw supplied logs, video, frames, audio and transcripts are not committed.

| Boundary                                               | Turn 1 | Turn 2 | Turn 3 |
| ------------------------------------------------------ | -----: | -----: | -----: |
| Accepted STT final → Agent `turn_requested`            |   1667 |   1678 |   1627 |
| Initial Journal authorization                          |    841 |   1219 |   1164 |
| Initial authorization complete → Bedrock request       |    748 |    757 |    749 |
| Bedrock request → response headers                     |   2559 |   1543 |   1398 |
| Headers → output validated                             |      7 |      1 |      0 |
| Output validated → publication authorized              |   1159 |   1171 |   1164 |
| Publication authorized → registration/recheck complete |     25 |     29 |     41 |
| Entire Journal request                                 |   5339 |   4720 |   4516 |
| Agent request → TTS begin, excluding Journal duration  |    602 |      7 |      7 |
| TTS begin → Agent first playout audio                  |    209 |    188 |    182 |
| Agent request → Agent first playout audio              |   6150 |   4915 |   4705 |
| Accepted STT final → Agent first playout audio         |   7817 |   6593 |   6332 |

All three responses ended with `END_TURN`; input tokens were 1054/1119/1183
and output tokens 102/105/109. Those token counts do not demonstrate a need to
truncate context. No CountTokens event is present in the supplied extract;
the 748–757 ms interval must not be labeled CountTokens time without evidence.
The first turn's extra 602 ms can include waiting for queued control/heartbeat
acknowledgement and transport; the old events do not isolate its components.

Nearest-rank percentiles describe **these three turns only**:

| Metric                               |  p50 |  p95 | Worst |
| ------------------------------------ | ---: | ---: | ----: |
| Accepted final → request             | 1667 | 1678 |  1678 |
| Request → Agent first playout        | 4915 | 6150 |  6150 |
| Accepted final → Agent first playout | 6593 | 7817 |  7817 |
| Bedrock request → response headers   | 1543 | 2559 |  2559 |
| Publication authorization interval   | 1164 | 1171 |  1171 |
| TTS begin → Agent first playout      |  188 |  209 |   209 |

The video has two approximate acoustic/UI response gaps: 9.86–10.20 seconds
and 7.38–8.16 seconds. Midpoint interpolation gives p50 8.90 seconds, p95
9.917 seconds and worst 10.03 seconds, descriptive of this clip only. These
are inferred speaker/activity boundaries from mixed audio and UI frames, not
instrumented speech-end labels or a representative staging benchmark. The
video and JSONL call were not proven to be the same attempt; do not combine
their clocks or samples.

| Requested milestone        | What is established                                                                        |
| -------------------------- | ------------------------------------------------------------------------------------------ |
| T0, user finishes speaking | Approximate video intervals only; no synchronized browser marker                           |
| T1, turn detection         | Not measured; pinned Nova-3 endpointing is 500 ms                                          |
| T2, STT final              | Agent receipt is a proxy; transport/finalization cannot be separated                       |
| T3, LLM request begins     | Journal `bedrock_request_started`                                                          |
| T4, first token            | Unknown: `bedrock_response_received` means fetch headers, not TTFT                         |
| T5, first TTS audio        | Old logs only bound TTS begin → Agent playout; new SDK TTFB metric helps isolate synthesis |
| T6, user hears audio       | Unknown in JSONL; Agent playout is not browser audibility                                  |

## Root causes, hypotheses and priorities

| Finding / action                                               | Evidence                                                                                                    | Impact, complexity and risk                                                                                                                                |
| -------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Reduce final aggregation and timer quantization                | Observed 1.6 second final→dispatch; actual worker uses 1500 ms hold and 250 ms timer                        | Implemented: default 750 ms, 50 ms scheduler. High predictable saving; low implementation complexity; resumed-speech/prefix regression tests required.     |
| Run independent room/dispatch/participant reads concurrently   | Actual `LiveKitVoiceMedia.inspect` awaited rooms before starting the other two reads                        | Implemented: same three reads start together. Medium saving dependent on network; low complexity; room privacy checks remain first and errors fail closed. |
| Split authorization/context/provider/publication telemetry     | Initial authorization takes 0.84–1.22 s and publication authorization 1.16–1.17 s                           | Implemented timing only. Fresh owner/media checks remain; individual RPC timings are needed before further optimization.                                   |
| LLM full response precedes TTS                                 | Actual non-streaming Converse request and schema/safety/publication checks                                  | Confirmed barrier, not assumed provider TTFT. Streaming is a larger protocol/safety change and is deferred.                                                |
| TTS or frontend buffers entire response                        | TTS already streams; first Agent audio follows TTS begin by 182–209 ms; frontend attaches audio immediately | No evidence of application whole-reply audio buffering. Browser jitter/device delivery remains unmeasured.                                                 |
| VAD double detection / CPU / cold start / cross-region latency | Pinned SDK, startup and source inspection only                                                              | Unproven per-turn causes. No provider/region/resource change is justified by this sample.                                                                  |

The pinned SDK emits `END_OF_SPEECH` after every final transcript, including
non-terminal provider segments. Bypassing aggregation on that generic event
would risk speaking over a continuing user. The change waits for a fresh valid
final after nonempty resumed Vietnamese interim speech, including low-confidence
interims; it does not submit interim text. Exact duplicate segments stay deduped.
Confidence must still be finite and at least 0.8 for a submitted final. If no
usable final follows an interim, the worker waits until a new final or the
existing idle/session deadline; it does not invent a completed utterance.

The SDK's Silero 1.5 second value is not proven to add to Deepgram endpointing
in this Inference path; do not add both values to a measured critical path.
The 21 EMPTY finals and one low-confidence discard are separate recognition
quality observations, not evidence that the LLM was called 21 times.

Token streaming is supported by [Bedrock ConverseStream](https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_ConverseStream.html),
but forwarding partial structured output before MentalBridge's complete
`END_TURN`, schema, safety and current-authority validation would violate the
existing publication rule. This PR neither removes that rule nor claims an
LLM TTFT improvement. Model, STT/TTS provider, voice, prompts and context are
unchanged. No retry, fallback, preemptive inference or paid warm-up is added.

## Controlled before/after benchmarks

These use actual compiled worker/provider code with identical virtual clock
and synthetic dependency delays. No real provider, staging call or microphone
was used. They isolate implementation savings, not deployed end-to-end results.

| Controlled metric                     |           Samples per variant | Before p50 / p95 / worst | After p50 / p95 / worst |
| ------------------------------------- | ----------------------------: | ------------------------ | ----------------------- |
| Distinct final → Journal dispatch, ms | 250 per scenario, 4 scenarios | 1624 / 1737 / 1749       | 774 / 797 / 799         |
| LiveKit media inspection, ms          |                           200 | 318 / 376 / 392          | 178 / 198 / 199         |

Worker scenarios cover an isolated final, adjacent finals, duplicates, and
interim resumption. In the resumption fixture the old code dispatched a prefix
before the last final in 250/250 trials; the candidate does so in 0/250 and
submits one joined turn. Its final→dispatch statistics above are measured from
the last distinct final, not the start of resumed speech.

The media benchmark supplies the same three independent RPC latencies in the
100–199 ms range and verifies the same authorized room, dispatch and participant
result with exactly three reads. It proves removal of serialization, not that
the observed 1.16 second publication interval will halve on staging. Do not sum
the two benchmark savings into an unmeasured end-to-end percentile.

Reproduction after installing locked dependencies:

```powershell
# From ai-voice-agent: actual candidate worker; no remote calls.
npm.cmd run latency:benchmark -- --label candidate
# Compare against a separately compiled 81ee08bd Agent with the same lockfile.
node scripts/benchmark-latency.mjs --label baseline --package-dir <baseline-agent-package> --allow-premature-interim
# From journal-ai-service: reads baseline source with git show, no checkout mutation.
npm.cmd run benchmark:voice-media
```

Each runner prints aggregate metrics and source hashes only. The baseline
commit must be present in the local Git object store. After-deployment
T6−T0 p50/p95/worst are **NOT_RUN**, not zero or inferred from these fixtures.

## Files, functions and telemetry

- Agent `configuration.ts` / `agent.ts` / `.env.example`: 750 ms default;
  existing explicit 500–1500 ms configuration bounds remain. An existing
  explicit env value is retained. Roll back the aggregation default via an
  explicit 1500 value or revert the source change; no data migration is needed.
- Agent `voice-worker.ts`: `receive` aggregation/interim handling,
  `tick` cadence/dispatch timing and `turn` reply/playout timing.
  `diagnostics.ts` defines closed phases and numeric fields.
- Agent `livekit-media.ts`: finite SDK TTS first-byte and synthesis-duration
  usage values only; missing/invalid values are omitted.
- Journal `voice-media-provider.ts`: `inspect` concurrent read start with
  room-first privacy checks; `inspectRead` reports fixed operation, elapsed ms
  and SUCCESS/FAILED. Module wiring emits `voice_media_inspection`.
- Journal `voice-turn.ts`: milestones around context, claim, activity,
  initial/pre-inference/publication authorization, provider reply, registration
  and final state recheck. `turnSequence` correlates the current turn.
- Journal `companion-chat.ts`: request-body-prepared and response-body-received
  events distinguish headers from full JSON body; prompt-prepared is bounded
  Voice-only. `voice-diagnostics.ts` restricts their phase vocabulary.
- Matching tests, the two portable benchmark runners, this report and the
  aggregate evidence JSON cover these changes. No REST/schema/storage change.

New observations contain existing validated attempt/turn identity, numeric
duration/sequence and closed operation/outcome values. They contain no raw
language, transcript, audio, prompt, reply, token/key, URL, body or raw error.
An observer failure cannot authorize a failed request. No external tracing SDK
or export is introduced. Existing bounded diagnostic capacity can truncate
long-call events; missing observations must remain UNKNOWN rather than zero.
`schedulerLagMs` is time past the aggregation deadline and can include waiting
for a busy prior turn or coordinator work; it is not a pure CPU/timer metric.

## Validation and preserved behavior

Agent: typecheck, lint, format, build, contract and migration checks passed;
103 tests passed, including 14 pinned-SDK wire cases. Worker regressions cover
adjacent/duplicate finals, low-confidence interim resumption, barge-in,
muting/reconnect, lease/heartbeat, cancellation and diagnostic privacy.

Journal: typecheck, lint, format, build, contract and migration checks passed;
the full unit run passed 367 tests before the final four read-observer tests.
The final media-provider focused run passed 11/11. Session and turn Mongo
integration suites passed against disposable local replica sets. Delayed body
fixtures prove headers are not body receipt. Otherwise-valid JSON with MISSING
or STOP_SEQUENCE completion remains rejected, with one usage observation,
closed diagnostics and no retry/publication. Full CI on the PR head is the
delivery gate; no unrun CI result is asserted here.

All fresh consent, entitlement, quota, capacity, owner/media and lease checks
remain. Publication still follows complete structured/safety validation and
authorization. Active duration, financial/quota settlement, maximum sessions,
context selection, Text semantics, speaker identity, interruption, reconnect,
teardown, recording/export settings and provider/voice pins are unchanged.

## Disconnect investigation and staging follow-up

The call ends at Agent elapsed 95,974 ms with `ROOM_DISCONNECTED`, mapped to
`PROVIDER_FAILURE`. Correlating the three Journal/Agent clocks within 3 ms puts
that event at approximately **07:18:20.884 UTC**. The separate Agent error is
**09:16:39.068 UTC**, with restart **09:16:40 UTC**: almost two hours later.
It is not evidence that a restart caused this call to disconnect. Three valid
Sonnet responses preceded the disconnect.

To establish its cause, correlate authoritative session terminal reason and
settlement, owner END time, room deletion/participant departure reason, Docker
exit/OOM/signal events and EC2 network/resource events. Do not retry a consumed
campaign or attribute this to AWS/STT/TTS without that evidence.

After source review, merge and deployment require separate authorization.
On a reviewed staging revision, use a separately approved, consented,
credit-capped test window and compare the same devices, speech fixture,
context size, effective voice UUID, provider pins and warmed/cold conditions.
Record non-secret effective config, resources and exact image/source binding.
Collect several calls with enough turns to report sample counts and meaningful
percentiles; the current three turns cannot establish a service p95.

Collect Agent dispatch/Journal round-trip/playout timings, Journal context and
authorization phases, each LiveKit read, response headers/body and SDK TTS
TTFB. Where approved tooling supports it, add synchronized numeric client
speech-end/playback markers without retaining content; otherwise leave T0/T6
and network/device delay unknown and obtain human listening confirmation.
Check Vietnamese pause handling, false endpointing, barge-in, context/safety,
consent revocation, quota/lease exhaustion, reconnect and clean teardown.

The current change reduces measured avoidable components. It cannot yet claim
the requested end-to-end acceptance target or a repaired historical disconnect.
