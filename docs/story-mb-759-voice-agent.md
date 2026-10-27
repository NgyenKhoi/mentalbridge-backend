# MB-759 Voice agent implementation

[MB-759](https://vunguyenkhoi47.atlassian.net/browse/MB-759), parent MB-754;
Journal/AI owner: Thảo Nguyên Trần. Baseline `34799522` is merged MB-758 #234.
Status: implementation in progress, no Cloud deployment or live speech evidence.

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

## Verification

Pending implementation; exact local and remote results will be recorded before
delivery. No live enablement, account mutation or paid provider call is run.
