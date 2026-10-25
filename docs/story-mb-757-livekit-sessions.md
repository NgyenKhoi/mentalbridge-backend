# MB-757 implementation plan and evidence

Story: [MB-757](https://vunguyenkhoi47.atlassian.net/browse/MB-757).
Owner: Journal/AI. Baseline: `7e900060` (merged MB-756 PR #231).
Status: implemented and locally verified; pushed-head CI results and PR URL are
recorded in the PR and delivery message after remote verification.

Feature owner: Thảo Nguyên Trần, Journal/AI backend. The use case is the MB-754
AI Voice Companion session; no additional UC identifier is invented. Web/mobile
callers and hosted worker are downstream owners, not implemented in this PR.
Merge order is merged MB-755/756, this provider, then MB-758/759 and client/speech
integration. Only the API/provider state contract is ready for those consumers;
this evidence does not label a frontend story `ui-ready`.

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

## Acceptance states and authored tests

The client descriptions below are the required API handoff, not screenshots or
evidence of a delivered voice UI. Every row follows
[policy v1](policies/companion-voice-policy-v1.md) and the frozen MB-755 contract.

| State / trigger | Client-facing result | Must not show or permit | Backend evidence |
| --- | --- | --- | --- |
| Unauthenticated / wrong role | 401 / 403, content-free Problem | Room, identity, token or owner information | Nest HTTP guard/filter tests |
| Missing consent, FREE, unavailable owner | Denied admission/join with documented code | A stale positive grant or new provider work | Existing owner/access tests plus lifecycle revoke tests |
| Concurrent or duplicate start | One accepted reservation/room/dispatch for the same intent; separate intents obey two global/one owner | Another room, quota allocation or client-selected owner | Two independent clients/services on real MongoDB |
| CONNECTING | 202 session; connection only after provider readiness and fresh owner checks | Camera, screen, data, record, admin or agent grant; expired token | SDK JWT scopes and HTTP response-schema tests |
| Worker claim/control | Dedicated authenticated attempt/dispatch claim; exact lease and ordered sequence | USER bearer forwarding, forged session/reply, replay extending liveness | Worker HTTP and Mongo fencing/replay tests |
| ACTIVE | Server-observed owner/agent presence; first reply requires registered proof | Charging a worker-invented reply or treating heartbeat as user activity | Mongo proof/60-second idle tests |
| RECONNECTING | Same session and hard stop within 30 seconds | Extra quota allocation or deadline extension | Reconnect, key conflict and late-token tests |
| Consent or entitlement lost / worker crash / deadline | No further joins/work; STOP or expired authorization | Continued use from cached CONTINUE or polling-created lease | Revocation, setup/lease/reconnect/deadline tests |
| ENDING with uncertain effect/cleanup | Truthful pending state and occupied capacity | Successful termination claim or permit release from a callback/timeout | Delayed create, unknown dispatch and failed-stop reconciliation tests |
| ENDED / FAILED / EXPIRED | Confirmed cutoff and one settlement; full quota refund without a first reply | Duplicate settlement or restored media | Repeat end and real Mongo ledger tests |
| Original creation + 30 days | Identifying metadata purged; unknown media keeps anonymous quarantine | Retention extension, audio/transcript persistence or automatic permit reuse | Closed validator, rollback and retention tests |

Authored executable evidence:

- [Official-SDK local transport and configuration](../journal-ai-service/src/companion-voice/voice-media-provider.test.ts):
  restricted grants, explicit dispatch, no automatic retry, recording rejection,
  namespace isolation and strict revoke/delete verification.
- [Nest provider REST boundary](../journal-ai-service/src/companion-voice/voice-sessions.http.test.ts):
  USER/worker credential separation, closed commands and safe errors, all new
  successful response types validated against active OpenAPI, malformed JSON and
  legacy Journal authentication regression.
- [Real MongoDB lifecycle](../journal-ai-service/src/companion-voice/voice-sessions.mongo.integration.test.ts):
  fourteen scenarios plus parent, two separate clients/service instances,
  actual 013/014 validators/indexes and rollback guard. No shared database is used.

The media provider and lifecycle use no Consultation appointment/video handlers,
webhooks, attendance, credit or financial APIs. Only the existing dedicated
current-entitlement read is used. Text quota collections and rules are unchanged.
The actual Nest assembly delegates all new routes to the same lifecycle service;
tests split the HTTP contract, Mongo consistency and SDK transport boundaries.

## Final local delivery checkpoint

| Command (Journal working directory unless noted) | Result |
| --- | --- |
| `npm run format:check`, `npm run lint`, `npm run typecheck` | PASS after correcting README formatting and test-only void-arrow lint findings |
| `npm test` (includes TypeScript build) | PASS, 187 tests, no failures/skips |
| `npm run test:integration` with a disposable local MongoDB replica set | PASS, 59 tests, no failures/skips |
| `npm run contract:check`, `npm run migration:check` | PASS, additive OpenAPI v1.14.0 and migrations 001–014 |
| `node --test ../contracts/proposals/companion-voice-contract.test.mjs` | PASS, all 55 frozen contract/policy tests |
| Root repository/paired-change policy | PASS; final publication result checked again on the complete diff |
| GitHub quality-gate and overlap report | Remote head/run result recorded in PR and final delivery after push |
| LiveKit account allowance/deployment, hosted worker, real media/STT/TTS/Bedrock and real devices | Not run; gated downstream in the [runbook](ai-voice-demo-runbook.md#mb-757-control-plane-checkpoint) |

Initial fixture-only failures (JWT missing `jti`, TypeScript narrowing, lint and
README formatting) were corrected before passing checks. Windows initially
blocked the repository verification script by execution policy; it passed when
run explicitly with process-local `-ExecutionPolicy Bypass`, without changing
machine policy. No Cloud deployment, recording, paid inference or real audio
success is claimed. The required remote CI must pass before handoff.
