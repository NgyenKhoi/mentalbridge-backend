# MB-760 Vietnamese speech QA

This directory is development tooling, excluded from the production Docker
source copy. Existing worker/runtime behavior and service contracts are unchanged.
It shares the locked SDK/toolchain and is typechecked/linted/tested by the
existing worker CI lane. No live provider is called by tests or the default plan.

```sh
npm run speech:plan
npm run qa:build
node dist-qa/score.js score < /secure/temporary-measurements.json
node dist-qa/mock.js --source-sha <exact-40-character-source-sha>
```

The scorer reads bounded JSON from stdin and emits aggregate-only JSON to stdout.
Avoid shell history, log redirection or committed input files containing real
measurements. Collect only the provided synthetic scripts, with each participant's
explicit permission. Do not read a Journal, real screening answer or real mental
health conversation. Temporary input/transcription stays in the measurement
process; discard it after scoring. The operator must delete any independently
created temporary source file after use; the tool does not delete user files.

`corpus.ts` contains 13 scripted cases, including a silence/noise-only control,
hesitation/long pause, soft speech, English loanwords, abbreviations, numbers and
high-impact negation/end-call contrasts. They are synthetic QA material, not a
clinical intervention. Read the scripts naturally; do not ask a participant to
disclose personal symptoms. Test north/central/south speakers honestly, including
Huế/Quảng Nam only where available; one speaker never establishes dialect coverage.

## Measurement input and matrix

The closed input schema is in `report.ts`. One run contains exact source SHA,
measurement time, reviewed default voice UUID (or null), and a bounded trial list.
Each trial contains its script ID, exact candidate STT model, device, noise,
region, source and denoise mode. `speakerToken` is a random per-participant UUID
used only in RAM to count unique speakers; it is never in the report. No names,
account IDs, API keys, real assessment answers or private URLs are accepted.

Only `human_scripted` trials establish human coverage. `mock` fixtures are
reported only under `simulatedOnly`, with zero actual device/provider evidence.
`tts_loopback` is a
separate provider smoke result and cannot establish pronunciation/accent quality
or real device latency. Keep both STT models, laptop/Android, quiet/fan-AC/road/
surrounding-speech and north/central/south separate. The 48 baseline cells are a
coverage checklist, not an instruction to spend all credits in one run. Missing
cells/scripts remain `NOT_RUN`; agent isolation cannot substitute for the WebRTC
baseline. Compare optional agent isolation only when actual access/allowance is
verified; do not stack aggressive filters by default.

Every non-text observation is explicitly nullable: premature end, high-impact
phrase correctness, barge-in outcome, naturalness/intelligibility (1–5), STT final
latency, TTS first-frame latency and device first-audible latency. Null means
**not measured**, never zero or success. TTS first frame is not an audible reply;
real TTS latency/listening ratings require the exact voice UUID in the report.
full Journal/Bedrock/WebRTC latency needs a real device measurement. No benchmark
LLM substitutes for Journal authorization or safety decisions.

## Scoring and safe publication

Normalize NFC, lowercase in Vietnamese, replace punctuation with spaces and
collapse whitespace. WER uses whitespace-delimited units (Vietnamese syllable
units, not a claim of linguistic word segmentation). CER uses Unicode graphemes
after NFC and excludes whitespace. Diacritics remain significant. Numbers and
phonetic abbreviations are not silently aliased to improve a score. Manually
review high-impact phrases separately from WER/CER; the scorer is not a safety
authority.

WER/CER are sum(S+D+I)/sum(reference units), weighted by reference length and
may exceed 1. Silence/noise-only references have no error-rate denominator and
instead report false-activation counts. Latency p50/p95 use nearest rank and
include the sample count. Group results by model/device/noise/region/source/
denoise before interpreting pooled values; a small pilot cannot establish
representative dialect accuracy or a guaranteed latency.

The report contains counts, aggregate rates/ratings, percentiles, public model/
voice IDs, source SHA and missing coverage. Raw transcript, reference text,
audio and speaker tokens are excluded. Unknown fields or invalid measurements
fail with a fixed safe error; input/provider payloads are never echoed. The tool
never auto-enables Voice or approves a configuration. Operator review and the
account/privacy/downstream device gates remain required.

## Approved first-pilot status — 2026-10-28

The operator reports Build Free, next invoice $0.00 and project Agent observability
OFF. Remaining Inference credit is **NOT VERIFIED**; "No model usage yet" is not
an allowance balance. Recording, egress and external content export are separately
NOT VERIFIED. `pilot.ts` preserves this as `BLOCKED_ALLOWANCE`, with the privacy
blockers also listed. The current CLI has **no live execution command**, reads no
credentials and contains no network/provider adapter. Changing a status snapshot
cannot enable a provider. No account registration, upgrade or new key is needed
for this mock delivery.

The approved ceiling is shared by the **whole first benchmark**, across all
models, devices, failures and reruns: $0.50 free credit, 600 seconds STT and 3,000
TTS input characters. Stop all components when any ceiling is reached. No
Bedrock/Claude, paid billing or plan upgrade. `PilotBudget` tests cumulative
reservations, second rounding, conservative integer-microdollar estimates and
failures without refunds. The mock report labels these as simulated reservations;
actual calls and spend are zero. This in-memory simulator is not a durable
multi-process spending authority. A future authorized live executor must carry
forward one protected campaign ledger, reserve before calls, include failed/
unknown attempts and stop on restart if prior usage is unknown; a fresh CLI run
must never reset the real approved allowance.

The checked public Build/Ship rate snapshot is Nova-3 monolingual $0.0048/min,
Nova-2 $0.0058/min and pinned Sonic 3 $50/million input characters, from
[LiveKit pricing](https://livekit.com/pricing/inference). Full ceilings at the
higher STT rate imply about $0.058 STT + $0.15 TTS = $0.208, excluding hosting,
WebRTC and optional isolation. This is an indicative upper input-cost estimate,
not an invoice or proof that free credit remains. Recheck rates/billing units and
allowances immediately before any future live run. Optional isolation remains
off/unpriced, not a zero-cost assumption.

Planned devices are laptop and Android. Actually measured devices: **0**; verified
consenting readers: **0**; measured dialects: **0/3**. No microphone is opened by
these commands. Live measurements, exact voice selection and a justified final
configuration remain gated. This scorer and its fixtures are not evidence of
speech quality. See
[the implementation record](../../docs/story-mb-760-vietnamese-speech-benchmark.md).
