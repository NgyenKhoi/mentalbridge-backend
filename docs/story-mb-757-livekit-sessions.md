# MB-757 implementation plan and evidence

Story: [MB-757](https://vunguyenkhoi47.atlassian.net/browse/MB-757).
Owner: Journal/AI. Baseline: `7e900060` (merged MB-756 PR #231).
Status: implementation in progress; verification and PR results will be recorded
here before delivery.

## Brainstorm and selected approach

Use the accepted MB-755 contract and MB-756 atomic ledger rather than another
quota or session authority. Public start/status/connection/end stay USER-owned;
the dedicated worker claim/control endpoints implement lifecycle only. Voice
turns, Bedrock/STT/TTS and the hosted Node worker remain MB-758/759 work.

The official pinned LiveKit server SDK handles room creation, explicit named
agent dispatch, microphone-only participant tokens, participant revocation and
room removal. AI room names and identities are opaque and use a separate AI
namespace. No Consultation video handler, appointment webhook, attendance or
financial path is called. The existing Cloud project needs no new registration.
Live calls remain disabled by default and require reviewed downstream enablement
evidence; deterministic CI never uses Cloud secrets or paid providers.

Persist only minimal room/dispatch progress, logical UUID dispatch identity,
opaque provider dispatch identity, worker/control sequencing and keyed command
bindings. LiveKit's provider dispatch ID is not the frozen UUID `dispatchId`;
the server maps its own UUID to the provider result. Never persist or replay a
participant token, raw command key, audio or transcript. Metadata expires at the
session's original creation plus 30 days, without activity-based extension.

Creation/dispatch commands have one durable claimant across processes. Unknown
provider outcomes are inspected using the same opaque references, not retried
as another dispatch. Ending blocks further joins and worker authorization.
Confirmed cleanup revokes the identities and removes the exact AI room before
settling the MB-756 ledger. An uncertain teardown retains its slot; privacy
expiry keeps the existing anonymous quarantine behavior.

Worker signals are authenticated and bound to the exact admitted attempt,
dispatch, lease and monotonically increasing worker sequence. Backend control
responses use a separate sequence. Heartbeats do not count as conversational
activity. Owner polling must observe revocation without manufacturing worker
liveness or extending an absent worker's lease. Playback commitment additionally
needs server-issued reply evidence from MB-758 and connected media presence;
this story does not invent an inference reply or count an unproven spoken reply.

## Ordered commit slices

1. Official SDK/configuration boundary: pinned dependency, typed disabled-by-
   default configuration, scoped token/room/dispatch adapter and deterministic
   SDK/security tests. Check build, focused lint and provider tests.
2. Recoverable lifecycle: append-only media/command migration, bounded Mongo
   metadata, worker fencing, teardown/reconciliation and paired canonical/data
   documentation. Check real Mongo concurrency, crash/timeout and cleanup tests.
3. Contract publication and delivery: integrate public/worker handlers, promote
   only implemented frozen schemas/operations, route/provider contract tests,
   runbook, acceptance evidence and CI wiring. Run affected module gates, refresh
   dev, review the complete task-only diff, push/create PR and wait for green CI.

## Verification

Provider/configuration checkpoint:

- `npm run build`: PASS.
- Scoped ESLint and Prettier for provider, tests and configuration: PASS.
- `node --test dist/companion-voice/voice-media-provider.test.js`: 2 PASS,
  no failures/skips. The actual official SDK calls a local synthetic HTTP server;
  server JWT grants, explicit dispatch, opaque fields, strict token scopes,
  current-time revocation, no automatic dispatch/retry, teardown and disabled
  configuration are checked. Initial test-only TypeScript narrowing/lint errors
  were corrected before this passing checkpoint.

SDK reference evidence:
[token lifecycle/revocation](https://docs.livekit.io/frontends/reference/tokens-grants/),
[room API](https://docs.livekit.io/reference/other/roomservice-api/),
[explicit dispatch](https://docs.livekit.io/agents/server/agent-dispatch/).
The Cloud revocation cutoff is Unix **seconds** and must be within 60 seconds of
server time; a future session hard-stop timestamp is not a valid substitute.
The provider uses the current-time cutoff and never relies on token expiration
to remove a connected participant.

Lifecycle/public API implementation and final gates remain in progress. No cloud
deployment, recording, paid inference or real audio success is claimed.
