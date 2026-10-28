import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import test from "node:test";
import { aggregate, type Trial } from "./report.js";
import { main } from "./score.js";
import { BenchmarkFailure } from "./metrics.js";
const trial = (extra: Partial<Trial> = {}): Trial => ({
  scriptId: "vi01",
  model: "deepgram/nova-3",
  device: "laptop",
  noise: "quiet",
  region: "north",
  speakerToken: randomUUID(),
  consented: true,
  source: "human_scripted",
  denoise: "webrtc_baseline",
  finalTranscript: "Hôm nay mình muốn chia sẻ một chuyện nhỏ.",
  sttFinalMs: 200,
  ttsFirstFrameMs: 400,
  firstAudibleMs: null,
  prematureEnd: null,
  highImpactCorrect: null,
  bargeInSuccess: null,
  naturalness: null,
  intelligibility: null,
  ...extra,
});
const input = (trials: Trial[]) => ({
  sourceSha: "a".repeat(40),
  measuredAt: "2026-10-28T08:00:00Z",
  voiceId: null,
  trials,
});
void test("aggregate report excludes all human transcript and speaker identity while preserving weighted error counts", () => {
  const a = trial();
  const b = trial({
    finalTranscript: "private_sensitive_marker",
    speakerToken: a.speakerToken,
  });
  const report = aggregate(input([a, b]));
  const encoded = JSON.stringify(report);
  assert.ok(!encoded.includes(a.finalTranscript));
  assert.ok(!encoded.includes(b.finalTranscript));
  assert.ok(!encoded.includes(a.speakerToken));
  assert.equal(report.coverage.speakers, 1);
  assert.equal(report.humanScripted.scoredTrials, 2);
  assert.equal(report.recommendation.status, "PROVISIONAL_REQUIRES_REVIEW");
  assert.equal(report.recommendation.productionEnabled, false);
});
void test("silence false activation has its own denominator and missing observations are not successes", () => {
  const report = aggregate(
    input([
      trial({ scriptId: "vi00", finalTranscript: "noise" }),
      trial({ scriptId: "vi00", finalTranscript: "" }),
    ]),
  );
  assert.equal(report.humanScripted.noiseOnlyTrials, 2);
  assert.equal(report.humanScripted.falseActivations, 1);
  assert.equal(report.humanScripted.wer.rate, null);
  assert.equal(report.humanScripted.bargeInSuccess.measured, 0);
  assert.equal(report.humanScripted.deviceFirstAudibleLatency.p50Ms, null);
});
void test("synthesized loopback cannot count as a human dialect or a real device-audible measurement", () => {
  const report = aggregate(input([trial({ source: "tts_loopback" })]));
  assert.equal(report.coverage.speakers, 0);
  assert.equal(report.recommendation.status, "BLOCKED");
  assert.equal(report.coverage.missingCells.length, 48);
  assert.throws(
    () =>
      aggregate(
        input([trial({ source: "tts_loopback", firstAudibleMs: 500 })]),
      ),
    BenchmarkFailure,
  );
});
void test("agent isolation alone cannot silently stand in for the WebRTC baseline", () => {
  const report = aggregate(input([trial({ denoise: "agent_isolation_only" })]));
  assert.equal(report.coverage.missingCells.length, 48);
  assert.ok(report.coverage.missingScripts.length > 0);
});
void test("unknown fields, oversized text, missing consent and a speaker re-labelled across dialects are rejected", () => {
  const a = trial();
  for (const invalid of [
    { ...input([a]), apiKey: "secret" },
    input([{ ...a, finalTranscript: "x".repeat(2001) }]),
    { ...input([]), trials: [{ ...a, consented: false }] },
    input([a, trial({ speakerToken: a.speakerToken, region: "south" })]),
    input([trial({ highImpactCorrect: true })]),
  ])
    assert.throws(() => aggregate(invalid), BenchmarkFailure);
});
void test("high impact mistakes flag review and groups preserve source/device/noise instead of hiding pooled differences", () => {
  const report = aggregate(
    input([
      trial({ scriptId: "vi09", highImpactCorrect: false }),
      trial({ model: "deepgram/nova-2", noise: "road", device: "android" }),
    ]),
  );
  assert.equal(report.groups.length, 2);
  assert.equal(report.recommendation.highImpactMishearing, true);
});
void test("CLI emits only aggregate JSON; malformed input yields a safe code and no echoed payload", async () => {
  const output: string[] = [];
  const a = trial();
  await main(["score"], [Buffer.from(JSON.stringify(input([a])))], (text) => {
    output.push(text);
  });
  assert.equal(output.length, 1);
  assert.ok(!output.join().includes(a.finalTranscript));
  await assert.rejects(
    main(["score"], [Buffer.from("private input not JSON")], () =>
      assert.fail(),
    ),
    (error) =>
      error instanceof BenchmarkFailure && error.message === "INVALID_INPUT",
  );
});
