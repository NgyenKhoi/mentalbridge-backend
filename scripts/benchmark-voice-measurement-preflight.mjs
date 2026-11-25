import assert from "node:assert/strict";
import { performance } from "node:perf_hooks";
import { evaluateVoiceMeasurement } from "./voice-measurement-preflight.mjs";

const now = Date.parse("2026-11-16T07:00:00.000Z");
const input = {
  journal: {
    schemaVersion: 1,
    component: "JOURNAL",
    capturedAt: new Date(now).toISOString(),
    voiceEnabled: true,
    providerEnabled: true,
    inputGuardEnabled: false,
    inputTokenLimit: null,
    outputTokenLimit: 2000,
    pilot: null,
    concurrencyLimit: 2,
  },
  agent: {
    schemaVersion: 1,
    component: "AGENT",
    capturedAt: new Date(now).toISOString(),
    enabled: true,
    pilotEnabled: false,
    ttsReservationEnabled: false,
    ttsCharactersPerAttempt: null,
    configuredTtsCharactersPerAttempt: 1200,
    finalAggregationMs: 750,
    sttModel: "deepgram/nova-3",
    ttsModel: "cartesia/sonic-3-2026-01-12",
    voiceConfigured: true,
  },
  approval: {
    schemaVersion: 1,
    environment: "staging",
    startsAt: "2026-11-15T12:18:00.000Z",
    endsAt: "2026-11-15T12:48:00.000Z",
    caps: {
      maxSessions: 2,
      maxConcurrentSessions: 1,
      maxTurns: 12,
      maxVoiceSeconds: 480,
      maxTtsCharacters: 4000,
      maxInputTokens: 1900,
      maxOutputTokens: 300,
    },
  },
};
let calls = 0;
globalThis.fetch = () => {
  calls++;
  throw new Error("NETWORK_DISABLED_FOR_BENCHMARK");
};
const durations = [];
for (let index = 0; index < 10000; index++) {
  const started = performance.now();
  const result = evaluateVoiceMeasurement(input, now);
  durations.push(performance.now() - started);
  assert.equal(result.status, "BLOCKED");
  assert.equal(result.liveApproved, false);
  for (const code of [
    "INPUT_GUARD_DISABLED",
    "OUTPUT_CAP_EXCEEDED",
    "TTS_RESERVATION_DISABLED",
    "APPROVAL_EXPIRED",
    "AGGREGATE_ENFORCEMENT_UNVERIFIED",
  ])
    assert(result.codes.includes(code));
}
assert.equal(calls, 0);
durations.sort((a, b) => a - b);
console.log(
  JSON.stringify({
    kind: "SYNTHETIC_PREFLIGHT_EVALUATOR_ONLY",
    samples: durations.length,
    quantileMethod: "NEAREST_RANK",
    p50Ms: durations[Math.ceil(durations.length * 0.5) - 1],
    p95Ms: durations[Math.ceil(durations.length * 0.95) - 1],
    worstMs: durations.at(-1),
    blocked: 10000,
    networkCalls: calls,
    liveCalls: 0,
    admissions: 0,
    scope:
      "Local evaluator cost and rejection correctness; not AI Voice latency, provider or browser E2E.",
  }),
);
