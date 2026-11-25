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

## Pilot execution and acceptance states

The [aggregate evidence](evidence/mb-760-mock-pilot-summary.json) was generated
using `node ai-voice-agent/dist-qa/mock.js --source-sha
386b42ff71bddd96738fd5252c4e5d575cff2507` after `npm run qa:build` at that exact
code checkpoint. It contains 26 simulated trials, no actual provider invocation,
no actual cost and no measured human speech metrics. Simulated reservations are
260,000 ms STT + 1,114 TTS characters, an indicative 78,671 micro-USD ($0.078671),
not actual spend or allowance use. The source checkpoint is an ancestor of the
documentation delivery head; the PR records the final head and CI run.

| State / trigger | Expected QA/client handoff | Must not show or permit | Evidence |
| --- | --- | --- | --- |
| Unverified remaining credit | `BLOCKED_ALLOWANCE`, mock-only | Inferring balance from no usage or $0 invoice; live spend | Readiness test and operator snapshot |
| Recording/egress/export unverified | Additional explicit blockers | Treating observability OFF as all privacy settings OFF | Readiness test; runbook preflight |
| Mock/synthesized input | Separate result groups, 0 human coverage | PASS for real devices or three dialects | Report tests and generated pilot summary |
| Unknown/unsafe report shape | Fixed safe error, no echoed content | Credentials, identifiers or transcript in output/error | Schema, privacy and CLI tests |
| TTS timing/rating without exact voice | Reject real measurement | Invented voice provenance | Voice provenance regression |
| Budget ceiling reached/crossed | Deny reservation across all components | New allowance for another model/rerun, refund after unknown work | Campaign rounding/cap/resume tests |
| Dependency failure/timeout | Abort mock operation, no retry, safe code | Provider payload or a fabricated result | Mock failure/cancellation tests |
| Missing turn/latency observation | null with sample count | Missing observation converted to success or zero latency | Metric/report tests |
| Future human input | Explicit permission + synthetic script only | Journal/history context or real health disclosure | Device checklist; not executed |

No UI is changed here. UC owner is Journal/AI for MB-754 Voice conversation;
web/mobile owners and MB-762/764 controls are downstream, not labelled `ui-ready`.
All states follow [Voice policy v1](policies/companion-voice-policy-v1.md).

## Recommended comparison configuration

These are **provisional test settings**, not a benchmarked winner or permission
to enable runtime. Keep the existing worker configuration unchanged.

| Component | Recommendation and reason | Status |
| --- | --- | --- |
| STT | Compare exact `deepgram/nova-3` against `deepgram/nova-2`, explicit `vi`; avoid `multi` when only Vietnamese is intended | Both documented as supporting `vi`; actual account/model access and accuracy NOT_RUN |
| TTS | Start with pinned `cartesia/sonic-3-2026-01-12`, explicit `vi`; compare two default Cartesia voices once reviewed IDs are available | Voice ID null; human naturalness/intelligibility NOT_RUN |
| VAD/turn | Existing Silero CPU, min speech 0.2 s, min silence 1.5 s, 1.5 s final aggregation; compare hesitant 1/2/3-second pauses | No tuning change without measured premature-end/latency tradeoff; provider endpoint plus aggregation may add sequential delay |
| Barge-in | Existing confident interim speech of at least 500 ms/two words interrupts, final segments alone call Journal | Software behavior tested in MB-759; acoustic/device success NOT_RUN |
| Denoise | WebRTC acoustic echo cancellation and device noise suppression baseline, no stacked aggressive filter | Agent-side isolation remains NOT_RUN/access/cost unverified; do not add an unpriced provider |
| Fallback | Explicit text or manual push-to-talk when recognition is uncertain/unavailable | Client permission/control delivery belongs to MB-762/764; no silent model/provider switch |

LiveKit's [Inference catalog](https://docs.livekit.io/agents/models/inference/)
lists Vietnamese for the candidate models. The [2026-10-05 Deepgram release](https://developers.deepgram.com/changelog/2026/10/5)
also reports updated Vietnamese Nova-3, so an alias is not an immutable provider
build: record the exact test time and provider-reported model revision if exposed.
The pinned Sonic date avoids silently choosing `latest`. Default voices can be
selected through the [Cartesia voice library](https://docs.livekit.io/agents/models/tts/cartesia/#voices);
a free Cartesia account is only needed to browse that library, not to obtain a
separate inference API key. No registration is needed to run this mock pilot.
No account creation or default voice selection was performed.

The [current pricing snapshot](https://livekit.com/pricing/inference), checked
2026-10-28, lists Build/Ship Nova-3 monolingual $0.0048/min, Nova-2 $0.0058/min and
the pinned Sonic 3 at $50/million characters. Full approved input ceilings would
cost approximately $0.208 at the higher STT rate, excluding hosting, media and
optional isolation. The authorized cap remains $0.50 free credit for the whole
campaign; actual credit and billing units must be checked before any live work.
Published Build allowance ($2.50/month) is not actual remaining account credit.

## Laptop/Android checklist for the later authorized pilot

1. Record exact source SHA, test time, client/OS/browser version and public
   model/voice IDs. Keep names/account IDs/private URLs out of evidence. Record
   the actual number of consenting readers and their self-reported region; use
   random temporary tokens only for scoring. Currently all actual counts are 0.
2. Verify remaining free credit and independent recording/egress/export settings;
   select one campaign operator and protect cumulative usage across reruns.
   Stop if allowance, price or prior usage is unknown. Do not reuse the mock
   simulator as a real spending ledger or pass keys to a browser/client.
3. On HTTPS or trusted localhost, the participant grants microphone access
   themselves. Check the chosen mic and output device, actual AEC/noise-suppression
   settings and headphone/speaker mode. Denial/unavailable mic must offer text or
   an explicit retry; do not change OS privacy permissions automatically.
4. Capture the 13 synthetic scripts per selected baseline cell: quiet, fan/AC,
   road background and surrounding speech; soft/hesitant speech, planned pauses,
   English loanwords and abbreviations. Use consented or synthetic background
   sound; do not record bystanders. Do not claim calibrated SNR without measuring it.
5. Record STT-final latency from the last voiced input to final text on the same
   monotonic clock. Record TTS request-to-first-frame separately. Device audible
   onset and speech-end timings require the same measured clock/device; otherwise
   leave first-audible latency null. This round does not call Claude or establish
   Journal/Claude end-to-end latency.
6. The observer labels premature endings, high-impact negation/end-call mistakes,
   false activations during noise-only controls and interruption success. Check
   stop/mute, uncertain recognition, disconnect and unavailable provider handling.
   WER/CER alone cannot approve a high-impact phrase or a safety response.
7. Compare baseline and optional isolation as distinct modes, only if isolation
   access/cost/privacy is verified. Keep unsampled Huế/Quảng Nam and any other
   missing speaker/device/noise cells NOT_RUN. More readers are required before
   representative dialect claims; one reader per region would still be a pilot.
8. Process script audio/transcription temporarily in RAM, disable recording,
   content logging/export and crash-content capture; discard after scoring.
   Only the content-free aggregate report belongs in Jira/repository evidence.
   Publish denominators, failure counts and missing observations with the rates.

## Verification and limits

- QA-only source/build stays outside the production Docker source copy. Existing
  worker scripts run QA format/lint/typecheck/tests on the current CI lane; no
  workflow, dependency, public API, owner storage or runtime worker change.
- Local commands in `ai-voice-agent/`: `npm run format:check`, `lint`, `typecheck`,
  `test`, `contract:check`, `migration:check`, `build`: PASS, with **40 tests**
  (22 existing worker + 18 QA, 0 skipped). Repository/paired-change policy and
  documentation links also PASS against `8b4e5b4e`. Exact final CI status is
  recorded in the PR. The added QA tests cover Unicode edit scoring,
  weighting, missing data, provenance, privacy, campaign caps and cancellation.
- No LiveKit/AWS/provider call, microphone access, recording, deployment or account
  mutation. Measured devices 0; consenting speakers 0; dialects 0/3; real STT/TTS
  quality and component latency NOT_RUN. Live benchmark remains BLOCKED_ALLOWANCE
  plus recording/egress/export and participant prerequisites.
