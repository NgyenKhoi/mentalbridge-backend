# MB-760 Vietnamese speech QA

This directory is development tooling, excluded from the production Docker
source copy. Existing worker/runtime behavior and service contracts are unchanged.
It shares the locked SDK/toolchain and is typechecked/linted/tested by the
existing worker CI lane. No live provider is called by tests or the default plan.

```sh
npm run speech:plan
npm run qa:build
node dist-qa/score.js score < /secure/temporary-measurements.json
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

Only `human_scripted` trials establish human coverage. `tts_loopback` is a
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

Live measurements, actual remaining allowance, costs and a justified choice of
model/voice/denoise are still pending operator prerequisites. This scorer and its
fixtures are not evidence of speech quality. See
[the implementation record](../../docs/story-mb-760-vietnamese-speech-benchmark.md).
