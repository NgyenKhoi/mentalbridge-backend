# MB-760 Vietnamese speech benchmark

[Jira MB-760](https://vunguyenkhoi47.atlassian.net/browse/MB-760), Epic MB-754.
Owner: Journal/AI, Thảo Nguyên Trần. Baseline `8b4e5b4e` contains merged MB-759
#235. Status: mock-only pilot tooling implemented; live benchmark is
**BLOCKED_ALLOWANCE**. No live results or approved voice are claimed.

## Brainstorm and implementation slices

The delivery is a reproducible speech measurement tool and an evidence-based
configuration recommendation. Existing LiveKit Inference credentials suffice;
no separate speech-provider integration, database, public contract, Bedrock call
or production enablement belongs here. Keep production Voice disabled.

1. Offline evaluation: scripted Vietnamese corpus, a bounded device/noise/speaker
   matrix, Unicode-aware WER/CER, turn/latency metrics and closed aggregate-only
   reporting. Verify deterministic metric cases, privacy and incomplete coverage.
2. Mock pilot and live-readiness report: exact candidate IDs, operator-reported
   account/privacy preflight, consented scripted input and one shared campaign
   credit/duration/character ceiling. The operator explicitly selected mock-only
   execution while actual allowance is unverified. Verify timeout/cancellation,
   cumulative budget simulation and content-free output with fakes.
3. Evidence and recommendation: measure only after operator prerequisites are
   confirmed, publish aggregate coverage/results and record untested conditions
   as NOT_RUN/BLOCKED. Integrate focused checks into the existing worker package,
   refresh dev, review overlap, push/create PR and wait for quality-gate PASS.

The owner approved the first-campaign ceiling on 2026-10-28: $0.50 free Inference
credit, 600 seconds STT total and 3,000 TTS input characters total. Stop all
components when any limit is reached; no Bedrock, paid billing or plan upgrade.
Planned devices are laptop and Android; verified consenting speakers are zero.
Published plan prices do not verify account
credit. No universal dialect/latency claim or final model/voice approval follows
from fixtures, synthesized input or a single speaker. Acoustic and full
Journal/Bedrock end-to-end latency must remain distinct from provider first-frame
measurements. Real user mental-health material is excluded from all test input.

## Offline checkpoint

The QA-only scorer in `ai-voice-agent/qa/` implements 13 scripted cases, baseline
coverage gaps, weighted WER/CER and separate operator observations. Eleven focused
tests, strict QA build, lint and formatting pass locally. Human transcripts and
speaker tokens are absent from output; missing observations remain null/NOT_RUN.
The existing worker CI lane picks up the QA build/tests through package scripts;
no workflow, runtime service or business contract change is needed.

No provider call or real-device observation has run. Browser/UI tools failed to
initialize with `failed to write kernel assets`. The owner subsequently reported
Build Free, next invoice $0.00, observability OFF, remaining credit NOT VERIFIED
and recording/egress/external export NOT VERIFIED. The tool labels this as
operator-reported evidence, not its own authenticated account inspection.

The mock-only runner processes both STT candidates and all scripts, separating
simulated metrics from human and synthesized-provider measurements. It reads no
credential or microphone and exposes no live execution command. Readiness
snapshot edits cannot bypass that boundary. Cumulative budget reservation tests
model both STT candidates/TTS and deny exceeding any ceiling without refunding
unknown work. This in-memory simulator does not replace the protected cumulative
ledger required before a future multi-run/live campaign.
