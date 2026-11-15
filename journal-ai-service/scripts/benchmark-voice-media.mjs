import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { execFileSync } from "node:child_process";
import { randomUUID, createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL, fileURLToPath } from "node:url";
globalThis.fetch = () => {
  throw new Error("NETWORK_DISABLED_FOR_CONTROLLED_BENCHMARK");
};
const pkg = fileURLToPath(new URL("../", import.meta.url)),
  backend = fileURLToPath(new URL("../../", import.meta.url));
const require = createRequire(pathToFileURL(join(pkg, "package.json"))),
  ts = require("typescript");
const sdk = await import(pathToFileURL(require.resolve("livekit-server-sdk")));
const oldSource = execFileSync(
  "git",
  [
    "-c",
    "safe.directory=" + backend,
    "show",
    "81ee08bd5ced530cc461459793b5917b48f59a06:journal-ai-service/src/companion-voice/voice-media-provider.ts",
  ],
  { cwd: backend, encoding: "utf8", windowsHide: true },
);
let baselineJs = ts.transpileModule(oldSource, {
  compilerOptions: {
    target: ts.ScriptTarget.ES2023,
    module: ts.ModuleKind.ESNext,
  },
}).outputText;
for (const name of ["jose", "livekit-server-sdk"])
  baselineJs = baselineJs.replaceAll(
    'from "' + name + '"',
    'from "' + pathToFileURL(require.resolve(name)).href + '"',
  );
const baseline = await import(
  "data:text/javascript;base64," + Buffer.from(baselineJs).toString("base64")
);
const current = await import(
  pathToFileURL(join(pkg, "dist/companion-voice/voice-media-provider.js"))
);
const config = {
  enabled: true,
  approvedVersion: "synthetic",
  serverUrl: "wss://synthetic.livekit.cloud",
  apiKey: "synthetic",
  apiSecret: "synthetic-secret-not-a-real-key",
  agentName: "mentalbridge-ai-voice-v1",
  workerToken: "synthetic-worker-not-a-real-key",
};
async function replay(Constructor, delays) {
  let now = 0,
    done = false,
    error,
    result;
  const jobs = [],
    calls = [];
  const deferred = (name, value) => {
    calls.push({ name, atMs: now });
    return new Promise((resolve) =>
      jobs.push({ at: now + delays[name], resolve: () => resolve(value) }),
    );
  };
  const ref = {
    voiceSessionId: randomUUID(),
    attemptId: randomUUID(),
    dispatchId: randomUUID(),
    roomName: "ai-voice-" + randomUUID(),
    participantIdentity: "vu-" + randomUUID(),
    agentIdentity: "",
    hardStopAt: new Date(Date.now() + 60000),
    providerDispatchId: null,
  };
  ref.agentIdentity = "va-" + ref.attemptId;
  const room = new sdk.Room({
    name: ref.roomName,
    metadata: ref.dispatchId,
    activeRecording: false,
  });
  const dispatch = new sdk.AgentDispatch({
    id: "synthetic-dispatch",
    room: ref.roomName,
    agentName: config.agentName,
    metadata: JSON.stringify({
      voiceSessionId: ref.voiceSessionId,
      attemptId: ref.attemptId,
      dispatchId: ref.dispatchId,
    }),
  });
  const participants = [
    new sdk.ParticipantInfo({
      identity: ref.participantIdentity,
      state: sdk.ParticipantInfo_State.ACTIVE,
    }),
    new sdk.ParticipantInfo({
      identity: ref.agentIdentity,
      state: sdk.ParticipantInfo_State.ACTIVE,
      tracks: [new sdk.TrackInfo({ type: sdk.TrackType.AUDIO, muted: false })],
    }),
  ];
  const provider = new Constructor(config, {
    rooms: {
      listRooms: () => deferred("rooms", [room]),
      listParticipants: () => deferred("participants", participants),
    },
    agents: { listDispatch: () => deferred("dispatches", [dispatch]) },
  });
  const work = provider.inspect(ref).then(
    (value) => {
      result = value;
      done = true;
    },
    (value) => {
      error = value;
      done = true;
    },
  );
  for (let n = 0; !done && n < 12; n++) {
    await new Promise((resolve) => setImmediate(resolve));
    if (done) break;
    assert.ok(
      jobs.length > 0,
      "pending actual inspection requires scheduled provider reads",
    );
    now = Math.min(...jobs.map((job) => job.at));
    const due = jobs.filter((job) => job.at === now);
    for (const job of due) {
      jobs.splice(jobs.indexOf(job), 1);
      job.resolve();
    }
  }
  await work;
  assert.equal(error, undefined);
  assert.equal(result.roomExists, true);
  assert.equal(result.ownerConnected, true);
  assert.equal(result.agentConnected, true);
  assert.equal(calls.length, 3);
  return { elapsedMs: now, calls };
}
const values = { before: [], after: [] };
for (let sample = 0; sample < 200; sample++) {
  const delays = {
    rooms: 100 + (sample % 100),
    dispatches: 100 + ((sample * 7) % 100),
    participants: 100 + ((sample * 13) % 100),
  };
  values.before.push(
    (await replay(baseline.LiveKitVoiceMedia, delays)).elapsedMs,
  );
  values.after.push(
    (await replay(current.LiveKitVoiceMedia, delays)).elapsedMs,
  );
}
const stats = (rows) => {
  const sorted = [...rows].sort((a, b) => a - b),
    q = (p) => sorted[Math.ceil(rows.length * p) - 1];
  return {
    n: rows.length,
    p50Ms: q(0.5),
    p95Ms: q(0.95),
    worstMs: sorted.at(-1),
  };
};
const out = {
  kind: "CONTROLLED_ACTUAL_MEDIA_INSPECTION_VIRTUAL_RPC_BENCHMARK",
  createdAt: new Date().toISOString(),
  before: stats(values.before),
  after: stats(values.after),
  method: {
    samples: 200,
    rpcDelayRangeMs: [100, 199],
    clock: "virtual deterministic provider-read completions",
    baselineSource: "81ee08bd5ced530cc461459793b5917b48f59a06",
    providerCalls: 0,
    stagingCalls: 0,
    mutations: 0,
    assertedChecks:
      "same successful bound room/dispatch/participants; exactly three reads per sample",
  },
  scope:
    "Measures actual LiveKitVoiceMedia.inspect with identical synthetic RPC delays before/after. Does not claim actual staging RPC decomposition or end-to-end saving.",
  sourceFingerprint: {
    beforeSha256: createHash("sha256").update(oldSource).digest("hex"),
    afterSha256: createHash("sha256")
      .update(
        readFileSync(join(pkg, "src/companion-voice/voice-media-provider.ts")),
      )
      .digest("hex"),
  },
};
console.log(JSON.stringify(out));
