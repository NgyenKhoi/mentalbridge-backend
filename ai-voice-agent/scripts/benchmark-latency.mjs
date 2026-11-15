import assert from "node:assert/strict";
import { createHash, randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const argumentsList = process.argv.slice(2);
let packageDirectory = resolve(dirname(fileURLToPath(import.meta.url)), "..");
let label = "candidate";
let allowPrematureInterim = false;
for (let index = 0; index < argumentsList.length; index += 1) {
  const argument = argumentsList[index];
  if (argument === "--allow-premature-interim") {
    allowPrematureInterim = true;
  } else if (argument === "--package-dir" || argument === "--label") {
    const value = argumentsList[++index];
    assert.ok(value && !value.startsWith("--"), `${argument} requires a value`);
    if (argument === "--package-dir") packageDirectory = resolve(value);
    else label = value;
  } else {
    throw new Error(`Unsupported benchmark argument: ${argument}`);
  }
}
assert.match(label, /^[a-z0-9-]{1,64}$/);
globalThis.fetch = async () => {
  throw new Error("Network requests are disabled in the scheduling benchmark");
};

const workerModule = await import(
  pathToFileURL(join(packageDirectory, "dist/voice-worker.js"))
);
const { VoiceWorker } = workerModule;
const { loadConfiguration } = await import(
  pathToFileURL(join(packageDirectory, "dist/configuration.js"))
);
assert.equal(typeof VoiceWorker, "function");
const aggregationMs = loadConfiguration({}).finalAggregationMs;
const tickIntervalMs = workerModule.workerTickIntervalMs ?? 250;
assert.ok(Number.isInteger(aggregationMs) && aggregationMs >= 500);
assert.ok(Number.isInteger(tickIntervalMs) && tickIntervalMs > 0);
const base = Date.parse("2026-01-01T00:00:00.000Z");
const statistics = (values) => {
  const sorted = [...values].sort((left, right) => left - right);
  const quantile = (percentile) =>
    sorted[Math.max(0, Math.ceil(sorted.length * percentile) - 1)];
  return {
    n: sorted.length,
    p50Ms: quantile(0.5),
    p95Ms: quantile(0.95),
    worstMs: sorted.at(-1),
    minimumMs: sorted[0],
  };
};

async function replay(phaseMs, scenario) {
  let now = base;
  let leaseExpiresAt = base + 10000;
  let backendSequence = 0;
  let callbacks;
  const hardStopAt = new Date(base + 300000).toISOString();
  const leaseId = randomUUID();
  const dispatch = {
    voiceSessionId: randomUUID(),
    attemptId: randomUUID(),
    dispatchId: randomUUID(),
  };
  const turns = [];
  let heartbeatRequests = 0;
  const journal = {
    claim: async () => ({
      ...dispatch,
      leaseId,
      validUntil: new Date(leaseExpiresAt).toISOString(),
      hardStopAt,
      policyVersion: "companion-voice-policy-v1",
    }),
    control: async (_dispatch, signal) => {
      if (signal.kind === "HEARTBEAT") heartbeatRequests += 1;
      leaseExpiresAt = Math.min(now + 10000, Date.parse(hardStopAt));
      return {
        action: "CONTINUE",
        control: {
          schemaVersion: 1,
          kind: "LEASE_RENEWED",
          commandId: randomUUID(),
          voiceSessionId: dispatch.voiceSessionId,
          attemptId: dispatch.attemptId,
          sequence: ++backendSequence,
          issuedAt: new Date(now).toISOString(),
          expiresAt: new Date(leaseExpiresAt).toISOString(),
          payload: {
            leaseId,
            validUntil: new Date(leaseExpiresAt).toISOString(),
            hardStopAt,
            admittedSeconds: 300,
          },
        },
      };
    },
    turn: async (_dispatch, turn) => {
      turns.push({ atMs: now - base, text: turn.finalTranscript });
      return {
        voiceSessionId: dispatch.voiceSessionId,
        turnId: turn.turnId,
        replyId: randomUUID(),
        language: "vi-VN",
        spokenText: "Synthetic safe reply.",
        expiresAt: new Date(leaseExpiresAt).toISOString(),
        policyVersion: "companion-voice-policy-v1",
      };
    },
  };
  let publications = 0;
  const media = {
    state: () => {},
    start: async (value) => {
      callbacks = value;
    },
    input: () => {},
    speak: async (_text, started) => {
      publications += 1;
      await started();
    },
    interrupt: () => {},
    close: async () => {},
  };
  const worker = new VoiceWorker(dispatch, journal, media, () => now);
  await worker.start();
  assert.ok(callbacks, "worker must initialize its media callbacks");
  const finalAt = 1000 + phaseMs;
  const event = (atMs, text, final = true, segmentId = randomUUID()) => ({
    atMs,
    text,
    final,
    segmentId,
  });
  const firstSegmentId = randomUUID();
  const events = {
    single: [event(finalAt, "Synthetic first question.", true, firstSegmentId)],
    "adjacent-finals": [
      event(finalAt, "Synthetic first segment.", true, firstSegmentId),
      event(finalAt + 400, "Synthetic second segment."),
    ],
    "duplicate-final": [
      event(finalAt, "Synthetic first question.", true, firstSegmentId),
      event(finalAt + 400, "Synthetic first question.", true, firstSegmentId),
    ],
    "interim-resumption": [
      event(finalAt, "Synthetic first segment.", true, firstSegmentId),
      event(finalAt + 600, "Synthetic continued speech.", false),
      event(finalAt + 1800, "Synthetic final continuation."),
    ],
  }[scenario];
  const ticks = new Set(
    Array.from(
      { length: Math.ceil((finalAt + 5000) / tickIntervalMs) },
      (_, index) => (index + 1) * tickIntervalMs,
    ),
  );
  const schedule = [
    ...new Set([...ticks, ...events.map((value) => value.atMs)]),
  ].sort((left, right) => left - right);
  let position = 0;
  for (const atMs of schedule) {
    now = base + atMs;
    while (events[position]?.atMs === atMs) {
      const value = events[position++];
      callbacks.transcript({
        text: value.text,
        final: value.final,
        language: "vi",
        confidence: 0.95,
        segmentId: value.segmentId,
        durationMs: 1200,
      });
    }
    if (ticks.has(atMs)) await worker.tick();
  }
  assert.ok(turns.length >= 1, "accepted final must request a Journal turn");
  assert.equal(publications, turns.length);
  if (scenario === "single" || scenario === "duplicate-final") {
    assert.equal(turns.length, 1);
    assert.equal(turns[0].text, "Synthetic first question.");
  }
  if (scenario === "adjacent-finals") {
    assert.equal(turns.length, 1);
    assert.equal(
      turns[0].text,
      "Synthetic first segment. Synthetic second segment.",
    );
  }
  const lastFinalAt = events.filter((value) => value.final).at(-1).atMs;
  const acceptedFinalAt =
    scenario === "duplicate-final" ? finalAt : lastFinalAt;
  const turnBeforeLastFinal = turns[0].atMs < lastFinalAt;
  if (scenario === "interim-resumption" && !allowPrematureInterim) {
    assert.equal(turns.length, 1, "continued speech must remain one turn");
    assert.equal(turnBeforeLastFinal, false, "do not dispatch resumed speech");
    assert.equal(
      turns[0].text,
      "Synthetic first segment. Synthetic final continuation.",
    );
  }
  await worker.stop("USER_ENDED");
  return {
    turnCount: turns.length,
    firstFinalToFirstTurnMs: turns[0].atMs - finalAt,
    lastDistinctFinalToFirstTurnMs: turns[0].atMs - acceptedFinalAt,
    turnBeforeLastFinal,
    heartbeatRequests,
  };
}

const scenarios = {};
for (const scenario of [
  "single",
  "adjacent-finals",
  "duplicate-final",
  "interim-resumption",
]) {
  const samples = [];
  for (let phase = 0; phase < 250; phase += 1) {
    samples.push(await replay(phase, scenario));
  }
  scenarios[scenario] = {
    firstFinalToTurn: statistics(
      samples.map((value) => value.firstFinalToFirstTurnMs),
    ),
    lastDistinctFinalToTurn: statistics(
      samples.map((value) => value.lastDistinctFinalToFirstTurnMs),
    ),
    turnCountRange: [
      Math.min(...samples.map((value) => value.turnCount)),
      Math.max(...samples.map((value) => value.turnCount)),
    ],
    turnsBeforeLastFinal: samples.filter((value) => value.turnBeforeLastFinal)
      .length,
    syntheticDownstreamLatencyMs: 0,
  };
}
const fingerprint = (file) =>
  createHash("sha256")
    .update(readFileSync(join(packageDirectory, file)))
    .digest("hex");
console.log(
  JSON.stringify({
    kind: "CONTROLLED_ACTUAL_WORKER_SCHEDULING_BENCHMARK",
    label,
    createdAt: new Date().toISOString(),
    configuration: {
      finalAggregationMs: aggregationMs,
      timerPeriodMs: tickIntervalMs,
    },
    method: {
      samplesPerScenario: 250,
      quantiles: "nearest-rank",
      clock: "injected virtual wall clock",
      phaseRangeMs: [0, 249],
      journalAndMedia: "synthetic in-process, immediate successful replies",
      providerCalls: 0,
      microphone: false,
      stagingCalls: 0,
      rawConversationSaved: false,
      interimResumptionAssertion: !allowPrematureInterim,
    },
    scenarios,
    scope:
      "Measures final transcript reception to Journal dispatch using actual compiled VoiceWorker. Does not measure STT endpointing, provider latency, TTFT/TTFA, browser playback, or T6-T0 perceived latency.",
    sourceFingerprint: {
      voiceWorkerSha256: fingerprint("src/voice-worker.ts"),
      configurationSha256: fingerprint("src/configuration.ts"),
      compiledVoiceWorkerSha256: fingerprint("dist/voice-worker.js"),
      compiledConfigurationSha256: fingerprint("dist/configuration.js"),
    },
  }),
);
