# MB-760 Vietnamese speech benchmark

[Jira MB-760](https://vunguyenkhoi47.atlassian.net/browse/MB-760), Epic MB-754.
Owner: Journal/AI, Thảo Nguyên Trần. Baseline `8b4e5b4e` contains merged MB-759
#235. Status: implementation in progress; no live results or chosen voice claimed.

## Brainstorm and implementation slices

The delivery is a reproducible speech measurement tool and an evidence-based
configuration recommendation. Existing LiveKit Inference credentials suffice;
no separate speech-provider integration, database, public contract, Bedrock call
or production enablement belongs here. Keep production Voice disabled.

1. Offline evaluation: scripted Vietnamese corpus, a bounded device/noise/speaker
   matrix, Unicode-aware WER/CER, turn/latency metrics and closed aggregate-only
   reporting. Verify deterministic metric cases, privacy and incomplete coverage.
2. Opt-in provider measurements: exact approved candidate IDs, actual account
   allowance/privacy preflight, consented scripted input and a hard per-run free
   credit/duration/character ceiling. Never run live providers in CI. Verify
   timeouts, budget accounting and no content persistence with fakes.
3. Evidence and recommendation: measure only after operator prerequisites are
   confirmed, publish aggregate coverage/results and record untested conditions
   as NOT_RUN/BLOCKED. Integrate focused checks into the existing worker package,
   refresh dev, review overlap, push/create PR and wait for quality-gate PASS.

Live budget, available human speakers/devices and actual remaining Cloud
allowance are being confirmed. Published plan prices do not verify account
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
initialize with `failed to write kernel assets`, so actual project credit/privacy
status must be confirmed by the operator. Pending questions cover the proposed
free-credit pilot ceiling and available consenting speakers/laptop/Android.
