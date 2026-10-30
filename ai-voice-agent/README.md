# MentalBridge AI Voice agent — MB-759

Journal/AI owns this independently deployable Node 22 worker. The accepted
[ADR 0046](../docs/adr/0046-ai-voice-companion-contract.md) permits LiveKit's
media SDK; every synchronous business call still uses
[Journal REST](../contracts/openapi/journal-ai-service-v1.yaml).
Default: **disabled**. This package does not start a consultation participant,
call Bedrock directly, create a room or allocate quota. Journal alone owns the
global two-call limit, one active call per user, consent, entitlement, usage,
30-day minimized metadata and provider-verified teardown. Local admission/load
limits supplement that authority; they are not a distributed quota counter.

## MB-762 local real pilot

The first authorized real pilot runs this worker locally against LiveKit Cloud,
with Journal on verified loopback HTTP. No Hosted Agent or staging deployment is
needed for this checkpoint. Keep `AI_VOICE_AGENT_ENABLED=false` until preflight
and explicit `LIVE_APPROVED`; registration, named dispatch and real media remain
unverified while disabled.

Set `AI_VOICE_PILOT=true` in the external private env for the approved run. It
pins Nova-3 and Xia `b8cd71e3-bc14-4538-a530-d6314731c036`, and reserves at most
1,200 TTS input characters per worker attempt before synthesis, including failed
or interrupted speech. Never restart/re-dispatch an unknown attempt. Journal's
pilot window bounds the campaign to two admissions and 90 seconds each, with
four Claude turns per admission. Existing no-retry, no-recording and no-export
controls still apply. See [preflight evidence](../docs/mb-762-live-preflight.md).

## Build and verify without Cloud

MB-782 adds the official `lk.agent.state` display hint through a single scoped
server API writer. Agent and USER own-metadata permissions remain denied to
avoid competing SDK writes; no content enters attributes. See the
[Web consumer handoff](../docs/story-mb-782-voice-web-prerequisite.md)
for transitions, exact identity/room filtering and bounded stale-state fallback.

MB-760 adds [speech QA tooling](qa/README.md) for a scripted matrix, Unicode
WER/CER, aggregate-only reports and a mock pilot. The first live benchmark remains
`BLOCKED_ALLOWANCE`; no live provider or microphone command is added. QA source
is excluded from the runtime image and runs through the existing worker CI lane.

```sh
npm ci
npm run format:check
npm run lint
npm run typecheck
npm test
npm run contract:check
npm run migration:check
npm run build
```

Tests use synthetic local HTTP and fake media/provider boundaries. They never
enable the worker or need Cloud, model or owner-service credentials.
`migration:check` verifies the no-database/no-direct-reasoning-provider boundary;
there is no worker database, migration, durable transcript or audio store.
SDK versions, including native RTC/Silero, are pinned in the lockfile. SDK
transitive OpenAI/telemetry packages are not configured or invoked by this worker.
Zod is pinned to 4.6.7 and ONNX's `adm-zip` is narrowly overridden to 0.6.1 for
[Zod validation](https://github.com/advisories/GHSA-mrhx-c7xc-m3w5) and
[ZIP extraction](https://github.com/advisories/GHSA-rcw4-f5rp-g42v) advisories.
The patched production image passes `npm audit --omit=dev` and loads the
packaged Silero VAD with `--network none`; revisit the override when ONNX updates.

## Configuration and startup

Use exactly the keys in [.env.example](.env.example), injected into the server
through managed secrets. No dotenv file is loaded automatically. Never put keys
in a client, dispatch, Jira, repository, command-line value or build argument.

| Key                                     | Meaning                                                                                                         |
| --------------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| `AI_VOICE_AGENT_ENABLED`                | Defaults to `false`; explicit `true` is rejected in CI/test                                                     |
| `AI_VOICE_AGENT_APPROVAL_VERSION`       | Reviewed account/privacy/speech QA record identifier, required when enabled                                     |
| `LIVEKIT_URL`                           | Existing mentalbridge project's `wss://…livekit.cloud` origin                                                   |
| `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET` | Server-only existing project credentials, also used for LiveKit Inference                                       |
| `AI_VOICE_JOURNAL_BASE_URL`             | HTTPS Journal origin reachable by hosted worker; loopback HTTP allowed locally; default `http://127.0.0.1:3002` |
| `AI_VOICE_JOURNAL_SERVICE_TOKEN`        | Purpose-specific token matching Journal `JOURNAL_AI_VOICE_WORKER_SERVICE_TOKEN`; distinct from LiveKit secret   |
| `AI_VOICE_STT_MODEL`                    | Candidate `deepgram/nova-3` (default), or `deepgram/nova-2`, Vietnamese `vi`                                    |
| `AI_VOICE_TTS_MODEL`                    | Pinned candidate `cartesia/sonic-3-2026-01-12`, Vietnamese `vi`                                                 |
| `AI_VOICE_TTS_VOICE_ID`                 | Required reviewed default Cartesia voice UUID selected in MB-760; blank means no enablement                     |

No additional Deepgram, Cartesia, OpenAI or AWS credentials belong in this
worker. Enabled startup rejects those provider credentials and OTLP export
configuration. No fallback model, cloned/custom voice or worker LLM is used.
Candidate selection is not speech-quality approval. MB-760 must benchmark and
record the chosen allowed model/voice before approving enablement; a different
model requires a reviewed configuration/code change.

The existing LiveKit Cloud `mentalbridge` project is sufficient for development.
No new account, paid upgrade or VPS is required by this change. Before an opt-in
Cloud test, verify Hosted Agents/Inference allowance, model/voice access, privacy
settings and Journal's existing Bedrock access using the
[controlled-demo runbook](../docs/ai-voice-demo-runbook.md). Unknown allowance
blocks enablement; this implementation has not inspected account quotas.

From the repository root:

```sh
docker build -t mentalbridge-ai-voice-agent:mb759 ai-voice-agent
```

The image contains no secrets and runs as the unprivileged `node` user. Default
startup exits safely with `VOICE_AGENT_UNAVAILABLE` without connecting. After
the opt-in gates are approved, deploy this image to the existing Hosted Agent
project using a Dockerfile rooted in `ai-voice-agent/` and inject runtime secrets.
For local authorized QA, with an explicit environment file **outside** the repo:

```sh
node --env-file=/secure/path/voice-agent.env ai-voice-agent/dist/main.js
```

Use `SIGTERM`/Ctrl+C or stop the hosted deployment to shut down. The parent closes
SDK job processes (5-second shutdown deadline); each job disables input, cuts
playout, cancels Journal work, disconnects and discards RAM. Journal verifies
actual room/agent absence before releasing capacity. Failed/unknown cleanup does
not free a slot from a worker-local timeout. Follow the runbook to reconcile it.
Do not start another agent by joining an arbitrary room or replaying a job.

## Media behavior and limits

Only named dispatch `mentalbridge-ai-voice-v1` with exact opaque session,
attempt and logical dispatch UUIDs is accepted. Runtime rooms use `ai-voice-UUID`,
room metadata is the logical dispatch UUID, and the accepted agent identity is
`va-ATTEMPT_UUID`. Journal must accept `agent-claim` before connecting. Subscribe
to one standard `vu-UUID` microphone participant only, no video/screens/data/text,
and reject recorded, consultation, extra-participant or changed-owner rooms.
RoomIO creates an audio input but it is detached before session startup. Only
after `MEDIA_READY` acknowledgement does the worker attach it and explicitly
subscribe the owner's microphone. Mute/disconnect/end detach and unsubscribe;
newly published owner tracks follow the same gate. This is distinct from
`inputOptions.audioEnabled:false`, which omits the SDK input permanently.

Every job owns one session and current-turn RAM. Silero VAD is CPU-local and
ships its model with the plugin. Endpointing plus a 1.5-second final-segment
aggregation pause accommodates hesitant Vietnamese speech. Interim words can
interrupt after 500 ms/two words but cannot call Journal. Low-confidence
(`<0.8`), non-Vietnamese, oversized or uncertain **final** input closes the call;
it never invents a transcript or model response. Clients can offer explicit text
or manual push-to-talk re-entry; this worker adds no client UI or silent fallback.

Only Journal's validated short `spokenText` reaches TTS. First audio publication
reports its exact turn/reply identity once. Barge-in cuts the current TTS stream,
reports interruption and never resumes an old prefix or retries generation.
Speech arriving while a Journal request is still unresolved cancels the session
instead of generating a second uncertain turn. There are no automatic REST,
STT/TTS or dispatch retries after an unknown outcome.

Control requests time out after 2 seconds, turns after 30 seconds; STT/TTS
connection/frame failures stop safely. Renew every 5 seconds against Journal's
10-second lease, and never extend the original hard stop locally. Muting clears
pending speech and input; owner disconnect between turns permits the same owner
to return within 30 seconds after fresh authorization. Agent network loss or
disconnect during generation/playback ends the attempt. Reconnect creates no
new quota or extended duration.

The caller must enable acoustic echo cancellation and browser/device noise
suppression. Exact normalized recent TTS echoes are additionally suppressed.
This guard is not proof of acoustic noise rejection: speakers, headphones,
background speech and hesitant pauses require MB-760 real-device benchmarks.
No paid managed noise filter is enabled without allowance/quality evidence.

## Privacy and operational evidence

`AgentSession.start({record:false})` disables SDK session recording and content
observability. Text/video input, transcription output/sync, preemptive generation,
SDK LLM and chat-history insertion are disabled. Raw STT is intercepted before
SDK history; TTS uses `addToChatCtx:false`. The SDK logger is silent in both
parent and job processes; errors expose only fixed safe codes. No debug export,
audio file, transcript, prompt, reply or token is logged/persisted by this package.
Current input/reply and deduplication digests remain only in bounded call RAM
and are dropped on end; there is no recovery from disk or previous chats.

The operator must also disable project-side recording, session insights, content
tracing, exports and content sampling before enablement. Application settings
cannot prove provider/account retention. See official pinned-boundary references:
[recording options](https://docs.livekit.io/reference/agents/session-recording-options/),
[pipeline nodes](https://docs.livekit.io/agents/logic/nodes/),
[STT](https://docs.livekit.io/agents/models/stt/deepgram/),
[TTS](https://docs.livekit.io/agents/models/tts/cartesia/).

No hosted deployment, real spoken reply, provider privacy/quota verification or
Vietnamese noise benchmark is claimed by automated tests. Record exact commit
SHA, model/voice IDs, device and provider modes in the authorized opt-in QA story.
Do not attach raw audio/transcript to evidence.
