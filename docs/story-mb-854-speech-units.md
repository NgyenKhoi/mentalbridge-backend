# MB-854 — independently publishable Voice speech units

PO approved development of the versioned speech-unit contract on16-November2026,
after the guarded incoming ConverseStream foundation was reviewed and merged.
This source remains default OFF and requires Code Owner policy/source review.
It is not activation, a new campaign/window or STREAMING DONE.

## Behavior and ownership

Journal owns validation, current authority, turn/TTS reservation, registration,
and cancellation. Agent owns incremental HTTP consumption, serial TTS playback,
bound playback acknowledgement and immediate local barge-in/teardown.
The pinned Sonnet/Nova3/Sonic3/Liên route is unchanged; no model, dependency,
provider, recording, text-context or public Web API change is introduced.

The provider emits a bounded JSON array of at most two complete structured
speech objects. Each uses the existing authority/safety schema. The same safety
validator checks each complete message and cumulative prefix; Vietnamese,
complete-sentence, markup/link and combined500-character checks also apply.
Provider wire consumption continues independently while publication/TTS wait,
so model messageStop is not delayed artificially by an awaited audio callback.

For each unit Journal performs fresh publication authorization and state/lease
checks. In measured mode, before the first it atomically reserves500 campaign
TTS characters for the entire response; plain pilot mode has no campaign
reservation. It registers the existing turn/reply identity. Each later
unit uses that identity with a closed1..2 unit sequence, and is authorized only
after Agent ACKs complete playback of the previous unit. This avoids publishing
the next unit with an expiry that elapses while it waits in an audio queue.

One incremental application/json response transfers authorized units and an
END_TURN completion or closed failure. It is REST, not a new WebSocket/SSE
protocol. Response is no-store and disables upstream proxy buffering. The
client validates strict wire shape, UTF8/size, IDs, order, expiry and total text
before handing a unit to the existing guarded TTS path. No raw delta is spoken.

Barge-in immediately interrupts local audio and cancels the exact provider turn.
The server preserves the live session and spent reservations only after provider
transport settles and queued work cleans up. Unconfirmed cancellation within
one second fails the session; no silent follow-up inference, retry or allowance
refund. Consent/state/lease failures still stop the session. Model failure,
MAX_TOKENS/STOP_SEQUENCE/missing stop, bad schema or truncated stream prevents
completion and cancels future output. Already heard audio is irreversible.

## Explicit prospective policy difference

The ordinary path still requires full model END_TURN before any publication.
The new path accepts independently complete units earlier. That is an explicit
change to the publication guarantee and needs owner acceptance of
[speech-unit policy v1](policies/companion-voice-speech-unit-v1.md). Existing
server validators and safety instructions are retained; test success is not a
claim that all cross-unit clinical semantics are equivalent to whole-message
review. The path stays OFF if that policy difference is not accepted.

[Canonical internal OpenAPI](../contracts/openapi/journal-ai-voice-speech-units-v1.json)
marks the implemented, default-OFF handlers; that status is not policy or live approval. Journal boundary/provider tests
validate it, and Agent contract checks compare actual wire fields/bounds to it.
Agent is the sole internal consumer, no frontend contracts:sync/UI work needed.
The existing main Journal OpenAPI and migrations remain untouched. MB-871 only
serialized Journal integration test-file startup in its package script. No
persisted shape/model/dictionary change occurs.

## Configuration and rollout

Superseded by MB-846d (owner decision 2026-11-23): the speech-unit and
ConverseStream flags are removed, speech units are always on with the approved
Voice Bedrock route, and the combined limit is 800 characters. The list below is
history.

- Journal: `JOURNAL_AI_VOICE_SPEECH_UNITS_ENABLED=false`, requiring
  `JOURNAL_AI_VOICE_CONVERSE_STREAM_ENABLED=true`, APPROVED_BEDROCK and a bound
  measurement pilot or a configured plain pilot with one or two unique owners
  and a window no longer than24 hours. Agent:
  `AI_VOICE_SPEECH_UNITS_ENABLED=false`, requiring `AI_VOICE_PILOT=true`; a
  measurement hash is required only for a measured campaign.
- Claim capability mismatch fails before media. Source/flags/policy hash must
  agree across the one supported Journal process and worker. ACK/cancel routing
  to a different process fails closed; do not deploy without single-replica
  routing or a separately reviewed affinity solution.
- Exact1900 input/300 output and2sessions/concurrency1/12turns/480seconds/
  4000TTSchars remain cumulative. Full500-character per-turn reservation is
  conservative and can cap a2000-character session at four responses. No refund
  or profile expansion is implied by unused reserved characters.
- Plain mode retains the 500 combined-character limit, Agent pilot TTS budget,
  product quota, two global slots and one admission per allowlisted owner. It
  has no campaign reservation or cumulative USD/LiveKit spend cap.
- Rollout through dev→staging review/CI, initially all live flagsOFF. Existing
  applied020 remains; no new migration,019/021 copy or reset. Rollback turns
  intake/Agent/unit flagsOFF, retains all campaign history/reservations and
  confirms terminal settlement/room/Egress0 before any later reviewed run.
- No live provider/CountTokens/STT/TTS/microphone/admission/window is authorized
  by this source. Fresh credit/privacy/current-authority/config gates and a
  separately confirmed fixed window remain mandatory before use.

## Verification scope and real acceptance

Provider fixtures verify actual binary EventStream framing/CRC/UTF8 and an
independent complete unit before delayed model messageStop; invalid schema,
unsafe/incomplete text, abnormal stops, no retry/fallback and privacy are tested.
Native loopback HTTP tests verify incremental JSON delivery before response
completion and strict Agent parsing/cancellation. Real disposable Mongo tests
cover reservation-before-publication, duplicate/race/ACK binding, reauthorization,
consent withdrawal, cancellation/resume and unconfirmed-cancellation failure.
Worker tests exercise early unit playback, independent heartbeat, barge-in,
acknowledgement failure and teardown. Ordinary Text/Voice tests remain required.

These are source correctness/integration checks, not a synthetic latency
benchmark or real provider result. Real before/after first-audible p50/p95 and
sample count are NOT_RUN/NOT_RUN/0. Model-first-delta and Agent playout are not
browser audibility. Keep failures/not-reached in denominators. The30% p50 goal
and audio-before-LLM-completion require a bounded matched real staging capture.
Source delivery or a flag alone cannot satisfy STREAMING DONE.
