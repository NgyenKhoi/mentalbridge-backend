# MB-759 Voice agent implementation

[MB-759](https://vunguyenkhoi47.atlassian.net/browse/MB-759), parent MB-754;
Journal/AI owner: Thảo Nguyên Trần. Baseline `34799522` is merged MB-758 #234.
Status: implemented and locally verified; remote head/PR/quality-gate results are
recorded in the PR and handoff after publication. No Cloud deployment or live
speech evidence. UC: MB-754 Voice conversation, accountable Journal/AI owner above;
web/mobile controls and `ui-ready` are downstream, no frontend owner is invented.

## Brainstorm and ordered slices

Use the existing LiveKit Cloud project and official pinned Node Agents SDK for
explicit named jobs only. The independently deployable `ai-voice-agent/` package
uses the frozen Journal claim/control/turn APIs; it never invokes a model or
owner API directly. A validated lease precedes audio processing. Stop/expiry/
failed renewal cancels speech and clears current-turn RAM. Journal alone owns
quota, context, media teardown proof and settlement. No new account or VPS is
needed. Actual account allowance/privacy and manual speech validation stay in
the opt-in QA story; MB-760 pins benchmarked models/voice rather than claiming
candidate quality from synthetic tests.

1. Package/configuration and contract-backed Journal client. Pin SDK/toolchain,
   validate server-only secrets/dispatch/lease/replies and no-store; test local
   HTTP failure, binding, retry and expiry without Cloud calls.
2. Worker execution and media adapter. Add lifecycle, final vi-VN STT, guarded
   Journal generation, approved-text-only TTS, cancellation/barge-in, reconnect,
   mute/echo/provider failure handling, no recording/transcript output and
   teardown. Verify fake media/providers plus pinned SDK configuration.
3. Deployable Docker/start/stop runbook, acceptance/traceability and minimal
   quality-gate coverage for the new package. Refresh dev, review task-only diff
   and overlap, push/create PR, wait for quality-gate PASS before handoff.

## Acceptance states

Display outcomes are a contract handoff to downstream clients, not a voice screen
implemented here. All rows follow [Voice policy v1](policies/companion-voice-policy-v1.md)
and [ADR 0046](adr/0046-ai-voice-companion-contract.md).

| State / trigger | Expected outcome | Must not show/permit | Executable evidence |
| --- | --- | --- | --- |
| CONNECTING / explicit AI job | Exact named dispatch, opaque IDs, correct room metadata and lease before audio | Consultation auto-join, USER JWT, forged dispatch, recording, third participant | Admission and HTTP consumer tests |
| LISTENING / TRANSCRIBING | Vietnamese final segments aggregate after a 1.5-second pause | Interim/echo/duplicate input generating a turn | Worker final/interim/dedup/echo tests |
| WAITING_FOR_REPLY | One ordered final Journal turn, cancellation signal and bounded response | Direct Bedrock, text quota/history, retries after unknown outcome | HTTP and in-flight cancellation tests |
| SPEAKING | Only approved short Journal text; first SDK audio frame acknowledges exact turn/reply once | Arbitrary TTS, history insertion, generation treated as playback proof | SDK event and worker publication tests |
| INTERRUPTED | Cut TTS immediately; same reply interruption, no resumed prefix | Duplicate generation or double playback count | Barge-in including acknowledgement race tests |
| MUTED | Disable input and clear pending speech; stop active playback | Stale pending input on unmute | Mute test |
| DISCONNECTED | Between turns, same owner may return within 30 seconds after fresh control | New quota, extended hard stop, late reconnect | Reconnect and late-deadline tests |
| ENDING / ENDED / revoked or expired | Stop locally, cancel provider/Journal work, close room input and drop RAM | Timer-based capacity release or late audio | Expiry, grant STOP, stale sequence, failure and teardown tests |
| Uncertain STT/provider/HTTP outage | Safe end, explicit client text/manual fallback | Fabricated speech, silent model switch or sensitive provider error | Low-confidence/language/budget, timeout/refusal/malformed/oversize tests |

## Verification and delivery

Authored tests:
`ai-voice-agent/src/journal-client.test.ts`,
`ai-voice-agent/src/voice-worker.test.ts`,
`ai-voice-agent/src/livekit-media.test.ts`.

- `npm test` in `ai-voice-agent/`: **19 passed**, no skipped tests; local HTTP plus
  fake LiveKit/STT/TTS/Journal boundaries, no account calls.
- `npm run format:check`, `lint`, `typecheck`, `build`, `contract:check` and
  `migration:check`: passed locally. Consumer bounds/authentication track the
  frozen proposal; no persistence/migration/owner schema change is introduced.
- Docker build, packaged Silero VAD with networking disabled and default-disabled
  startup: passed on the Linux Node 22 image. After pinning patched Zod/ONNX
  dependencies, production-image `npm audit --omit=dev` reports zero advisories.
- `scripts/verify-repository.ps1 -BaseSha 34799522f9690b25e0d3c66529b0000ce20768c8`
  and `node scripts/documentation-policy.mjs`: passed, including local links and
  preservation/paired-change rules. Exact remote results are recorded in the PR.
- The worker is added to the existing quality-gate Node matrix; its image build
  also runs without credentials. Existing test/gate behavior is retained.
- Integrator approved MB-759 before overlapping #233. Its owner must refresh dev
  and preserve both independent additions to `quality-gate.yml` before merge.

No Cloud deployment, account quota/privacy inspection, paid provider call,
Vietnamese noise benchmark or real-device speech result was run. MB-760 and
authorized opt-in QA remain enablement gates. Existing LiveKit/Bedrock accounts
suffice; exact configuration and operator steps are in the
[worker README](../ai-voice-agent/README.md) and
[runbook checkpoint](ai-voice-demo-runbook.md#mb-759-worker-checkpoint).
