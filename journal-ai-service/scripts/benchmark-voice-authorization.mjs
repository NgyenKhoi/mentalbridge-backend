import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { createServer } from "node:http";
import { createRequire } from "node:module";
import { performance } from "node:perf_hooks";
import { setTimeout as pause } from "node:timers/promises";
import { fileURLToPath, pathToFileURL } from "node:url";
import { join } from "node:path";

const baselineSha = "a51edb8f580be326fbfd23ef0e1f6936e1ea4cc1";
const pkg = fileURLToPath(new URL("../", import.meta.url));
const backend = fileURLToPath(new URL("../../", import.meta.url));
const require = createRequire(join(pkg, "package.json"));
const ts = require("typescript");
const nativeFetch = globalThis.fetch;
globalThis.fetch = (input, init) => {
  const url = new URL(input instanceof Request ? input.url : String(input));
  if (url.protocol !== "http:" || url.hostname !== "127.0.0.1")
    throw new Error("BENCHMARK_NON_LOOPBACK_DENIED");
  return nativeFetch(input, init);
};
const git = (...args) =>
  execFileSync("git", ["-c", "safe.directory=" + backend, ...args], {
    cwd: backend,
    encoding: "utf8",
    windowsHide: true,
  }).trim();
async function baseline(file) {
  const source = git(
    "show",
    `${baselineSha}:journal-ai-service/src/companion-voice/${file}.ts`,
  );
  let js = ts.transpileModule(source, {
    compilerOptions: {
      target: ts.ScriptTarget.ES2023,
      module: ts.ModuleKind.ESNext,
    },
  }).outputText;
  for (const name of ["zod", "jose", "livekit-server-sdk"])
    js = js.replaceAll(
      `from "${name}"`,
      `from "${pathToFileURL(require.resolve(name)).href}"`,
    );
  return import(
    "data:text/javascript;base64," + Buffer.from(js).toString("base64")
  );
}
const stats = (values) => {
  const sorted = [...values].sort((a, b) => a - b);
  const quantile = (q) => sorted[Math.ceil(q * sorted.length) - 1];
  const rounded = (value) => Number(value.toFixed(4));
  assert.ok(sorted.length && sorted.every((v) => Number.isFinite(v) && v >= 0));
  return {
    samples: sorted.length,
    p50: rounded(quantile(0.5)),
    p95: rounded(quantile(0.95)),
    worst: rounded(sorted.at(-1)),
  };
};
let stage = "BASELINE_IMPORT";
let server;
try {
  const [oldMedia, oldOwner, currentMedia, currentOwner, profileModule] =
    await Promise.all([
      baseline("voice-media-provider"),
      baseline("voice-authorization"),
      import(
        pathToFileURL(join(pkg, "dist/companion-voice/voice-media-provider.js"))
      ),
      import(
        pathToFileURL(join(pkg, "dist/companion-voice/voice-authorization.js"))
      ),
      import(
        pathToFileURL(
          join(pkg, "dist/companion-voice/voice-authorization-profile.js"),
        )
      ),
    ]);
  const attemptId = randomUUID();
  const ref = {
    voiceSessionId: randomUUID(),
    attemptId,
    dispatchId: randomUUID(),
    roomName: "ai-voice-" + randomUUID(),
    participantIdentity: "vu-" + randomUUID(),
    agentIdentity: "va-" + attemptId,
    hardStopAt: new Date(Date.now() + 120000),
    providerDispatchId: "AD_synthetic",
  };
  let scenario = 0,
    requestCount = 0,
    serverFailures = 0;
  server = createServer((request, response) => {
    void (async () => {
      requestCount++;
      const buffers = [];
      for await (const part of request) buffers.push(part);
      const body = JSON.parse(Buffer.concat(buffers).toString("utf8"));
      const method = request.url.split("/").at(-1);
      const binding = {
        ownerId: body.ownerId,
        voiceSessionId: body.voiceSessionId,
        purpose: "AI_COMPANION_VOICE",
        checkedAt: new Date().toISOString(),
      };
      let value;
      if (method === "voice-consent-authorizations")
        value = {
          ...binding,
          aiProcessingGranted: true,
          voiceProcessingGranted: true,
          authorized: true,
          voiceConsentVersion: "ai-voice-processing-capstone-v2",
        };
      if (method === "voice-entitlement-authorizations")
        value = {
          ...binding,
          packageCode: "PLUS",
          source: "DEMO",
          validUntil: new Date(Date.now() + 60000).toISOString(),
          entitlementVersion: "synthetic",
        };
      if (method === "ListRooms") {
        assert.deepEqual(body.names, [ref.roomName]);
        value = {
          rooms: [
            {
              name: ref.roomName,
              metadata: ref.dispatchId,
              activeRecording: false,
            },
          ],
        };
      }
      if (method === "ListDispatch")
        value = {
          agentDispatches: [
            {
              id: ref.providerDispatchId,
              room: ref.roomName,
              agentName: "mentalbridge-ai-voice-v1",
              metadata: JSON.stringify({
                voiceSessionId: ref.voiceSessionId,
                attemptId,
                dispatchId: ref.dispatchId,
              }),
            },
          ],
        };
      if (method === "ListParticipants")
        value = {
          participants: [
            { identity: ref.participantIdentity, state: "ACTIVE" },
            {
              identity: ref.agentIdentity,
              state: "ACTIVE",
              tracks: [{ type: "AUDIO", muted: false }],
            },
          ],
        };
      assert.ok(value);
      await pause(method === "ListRooms" ? 45 + (scenario % 7) * 5 : 5);
      response.writeHead(200, {
        "Content-Type": "application/json",
        "Cache-Control": "no-store",
      });
      response.flushHeaders();
      if (method === "ListRooms") await pause(4);
      response.end(JSON.stringify(value));
    })().catch(() => {
      serverFailures++;
      response.writeHead(500);
      response.end();
    });
  });
  stage = "LOOPBACK_START";
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  assert.ok(address && typeof address === "object");
  const host = `http://127.0.0.1:${address.port}`;
  const config = {
    enabled: true,
    approvedVersion: "synthetic-no-live",
    serverUrl: host,
    apiKey: "synthetic",
    apiSecret: "synthetic-only-secret-not-a-provider-key",
    agentName: "mentalbridge-ai-voice-v1",
    workerToken: "synthetic-only",
  };
  const observations = [],
    profiles = [];
  const variants = {
    before: {
      media: new oldMedia.LiveKitVoiceMedia(config),
      owner: new oldOwner.CurrentVoiceOwnerAuthorizer({
        careBaseUrl: host,
        consultationBaseUrl: host,
        careToken: "synthetic-only",
        entitlementToken: "synthetic-only",
      }),
    },
    after: {
      media: new currentMedia.LiveKitVoiceMedia(config, undefined, (e) =>
        observations.push(e),
      ),
      owner: new currentOwner.CurrentVoiceOwnerAuthorizer({
        careBaseUrl: host,
        consultationBaseUrl: host,
        careToken: "synthetic-only",
        entitlementToken: "synthetic-only",
      }),
    },
  };
  const owner = randomUUID();
  const results = { before: [], after: [] };
  const run = async (name, sample) => {
    const turnId = randomUUID();
    const started = performance.now();
    for (const purpose of ["INITIAL", "PRE_INFERENCE", "PUBLICATION"]) {
      const trace =
        name === "after"
          ? new profileModule.VoiceAuthorizationProfile(
              { purpose, attemptId, turnId, turnSequence: sample + 2 },
              (e) => profiles.push(e),
            )
          : undefined;
      const read = () =>
        Promise.all([
          variants[name].owner.check(
            owner,
            ref.voiceSessionId,
            randomUUID(),
            trace,
          ),
          variants[name].media.inspect(ref, trace),
        ]);
      const [authority, media] = trace
        ? await trace.measure("TOTAL", read)
        : await read();
      assert.equal(authority.consent.authorized, true);
      assert.equal(authority.entitlement.packageCode, "PLUS");
      assert.equal(
        media.roomExists && media.ownerConnected && media.agentConnected,
        true,
      );
    }
    return performance.now() - started;
  };
  stage = "WARMUP";
  await run("before", 0);
  await run("after", 0);
  const warmupRequests = requestCount;
  observations.length = 0;
  profiles.length = 0;
  for (let sample = 0; sample < 20; sample++) {
    scenario = sample;
    for (const name of sample % 2 ? ["after", "before"] : ["before", "after"]) {
      stage = name === "before" ? "BEFORE_CALL" : "AFTER_CALL";
      results[name].push(await run(name, sample));
    }
  }
  stage = "VALIDATION";
  assert.equal(serverFailures, 0);
  assert.equal(requestCount - warmupRequests, 20 * 2 * 3 * 5);
  assert.equal(observations.length, 20 * 3 * 3);
  assert.equal(profiles.filter((e) => e.stage === "TOTAL").length, 20 * 3);
  assert.ok(
    observations.every(
      (e) =>
        e.nativeRequests === 1 &&
        e.httpObservation === "OBSERVED" &&
        e.httpResult === "COMPLETE",
    ),
  );
  assert.equal(new Set(observations.map((e) => e.inspectionId)).size, 20 * 3);
  const operationStats = {};
  for (const operation of ["ROOMS", "DISPATCHES", "PARTICIPANTS"]) {
    const values = observations.filter((e) => e.operation === operation);
    operationStats[operation] = {
      operationMs: stats(values.map((e) => e.elapsedMs)),
      sendToHeadersMs: stats(values.map((e) => e.sendToHeadersMs)),
      headersToBodyMs: stats(values.map((e) => e.headersToBodyMs)),
    };
  }
  const output = {
    task: "MB-863",
    baseline: baselineSha,
    profiledRuntimeCommit: git("rev-parse", "HEAD"),
    scope:
      "SIMULATED delays over real loopback HTTP/native fetch/pinned SDK; three owner+media bundles, not full Voice/session/LLM/audio E2E",
    order: "PAIRED_ALTERNATING_AFTER_WARMUP",
    quantileMethod: "NEAREST_RANK",
    workload: {
      samplesPerVariant: 20,
      roundsPerSample: 3,
      readsPerRound: 5,
      roomHeaderDelayMs: "45 + (sample % 7) * 5",
      roomBodyDelayMs: 4,
      otherReadDelayMs: 5,
      measuredLoopbackRequests: requestCount - warmupRequests,
      warmupLoopbackRequests: warmupRequests,
      sink: "RAM_ONLY",
    },
    threeRoundBundleMs: {
      before: stats(results.before),
      after: stats(results.after),
    },
    operationStats,
    correlations: {
      rootRounds: 60,
      uniqueInspections: 60,
      operationObservations: observations.length,
      observedNativeRequests: observations.reduce(
        (n, e) => n + e.nativeRequests,
        0,
      ),
      reusedObservedSockets: observations.filter(
        (e) => e.connection === "REUSED_OBSERVED_SOCKET",
      ).length,
    },
    interpretation:
      "Profiling overhead/control-path parity only. Slow ListRooms HTTP wait is fixture-injected, not a discovered Cloud root cause or runtime latency optimization.",
    dnsTlsOrCloudProcessing: "UNKNOWN",
    stagingAfter: "NOT_RUN",
    browserAudibleE2E: "NOT_MEASURED",
    modelTTFT: "UNKNOWN",
    liveProviderCalls: 0,
    liveAdmissions: 0,
    newLiveWindows: 0,
    creditUsed: 0,
  };
  const encoded = JSON.stringify(output);
  for (const value of [
    host,
    owner,
    ref.roomName,
    ref.participantIdentity,
    ref.dispatchId,
    ref.voiceSessionId,
    config.apiSecret,
    "Bearer",
  ])
    assert.equal(encoded.includes(value), false);
  stage = "RESULT";
  console.log(JSON.stringify(output, null, 2));
} catch {
  console.error(
    JSON.stringify({
      task: "MB-863",
      result: "BENCHMARK_FAILED",
      stage,
      liveApproved: false,
    }),
  );
  process.exitCode = 1;
} finally {
  globalThis.fetch = nativeFetch;
  if (server) {
    server.closeAllConnections();
    await new Promise((resolve) => server.close(resolve));
  }
}
