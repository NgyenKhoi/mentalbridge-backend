import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { loadConfiguration } from "./configuration.js";
import { agentMeasurementSnapshot } from "./measurement-preflight.js";

const clock = new Date("2026-11-16T06:00:00.000Z");
const command = fileURLToPath(
  new URL("./measurement-preflight.js", import.meta.url),
);
const environment = {
  NODE_ENV: "production",
  AI_VOICE_AGENT_ENABLED: "true",
  AI_VOICE_AGENT_APPROVAL_VERSION: "synthetic-preflight-v1",
  LIVEKIT_URL: "wss://synthetic.livekit.cloud",
  LIVEKIT_API_KEY: "synthetic-key-canary",
  LIVEKIT_API_SECRET: "livekit-secret-canary-0123456789abcdef",
  AI_VOICE_JOURNAL_BASE_URL: "https://journal.synthetic.invalid",
  AI_VOICE_JOURNAL_SERVICE_TOKEN: "journal-token-canary-0123456789abcdef",
  AI_VOICE_TTS_VOICE_ID: "8e8f222d-c817-4cc5-822b-8bf76ca7e98d",
};

void test("Agent snapshot distinguishes a configured TTS cap from an enforced reservation", () => {
  const snapshot = agentMeasurementSnapshot(
    loadConfiguration({
      ...environment,
      AI_VOICE_PILOT_TTS_MAX_CHARACTERS: "2400",
    }),
    clock,
  );
  assert.equal(snapshot.enabled, true);
  assert.equal(snapshot.pilotEnabled, false);
  assert.equal(snapshot.ttsReservationEnabled, false);
  assert.equal(snapshot.configuredTtsCharactersPerAttempt, 2400);
  assert.equal(snapshot.ttsCharactersPerAttempt, null);
  assert.equal(snapshot.finalAggregationMs, 750);
  assert.equal(snapshot.capturedAt, clock.toISOString());
});

void test("Agent snapshot follows validated pilot TTS and aggregation configuration", () => {
  const snapshot = agentMeasurementSnapshot(
    loadConfiguration({
      ...environment,
      AI_VOICE_PILOT: "true",
      AI_VOICE_PILOT_TTS_MAX_CHARACTERS: "2000",
      AI_VOICE_FINAL_AGGREGATION_MS: "500",
    }),
    clock,
  );
  assert.equal(snapshot.pilotEnabled, true);
  assert.equal(snapshot.ttsReservationEnabled, true);
  assert.equal(snapshot.ttsCharactersPerAttempt, 2000);
  assert.equal(snapshot.configuredTtsCharactersPerAttempt, 2000);
  assert.equal(snapshot.finalAggregationMs, 500);
  assert.equal(snapshot.sttModel, "deepgram/nova-3");
  assert.equal(snapshot.ttsModel, "cartesia/sonic-3-2026-01-12");
  assert.equal(snapshot.voiceConfigured, true);
});

void test("Agent snapshot exposes only the fixed metadata schema, without secrets, identity or URLs", () => {
  const snapshot = agentMeasurementSnapshot(
    loadConfiguration({
      ...environment,
      PRIVATE_EXTRA: "private-extra-canary",
    }),
    clock,
  );
  assert.deepEqual(Object.keys(snapshot).sort(), [
    "capturedAt",
    "component",
    "configuredTtsCharactersPerAttempt",
    "enabled",
    "finalAggregationMs",
    "pilotEnabled",
    "schemaVersion",
    "sttModel",
    "ttsCharactersPerAttempt",
    "ttsModel",
    "ttsReservationEnabled",
    "voiceConfigured",
  ]);
  assert.equal(snapshot.schemaVersion, 1);
  assert.equal(snapshot.component, "AGENT");
  const serialized = JSON.stringify(snapshot);
  for (const value of [
    environment.LIVEKIT_URL,
    environment.LIVEKIT_API_KEY,
    environment.LIVEKIT_API_SECRET,
    environment.AI_VOICE_JOURNAL_BASE_URL,
    environment.AI_VOICE_JOURNAL_SERVICE_TOKEN,
    environment.AI_VOICE_TTS_VOICE_ID,
    "private-extra-canary",
  ])
    assert.equal(serialized.includes(value), false);
});

void test("disabled Agent snapshot does not manufacture voice configuration or readiness", () => {
  const snapshot = agentMeasurementSnapshot(loadConfiguration({}), clock);
  assert.equal(snapshot.enabled, false);
  assert.equal(snapshot.voiceConfigured, false);
  assert.equal(snapshot.pilotEnabled, false);
  assert.equal(snapshot.ttsCharactersPerAttempt, null);
  assert.equal(Object.hasOwn(snapshot, "ready"), false);
});

void test("Agent snapshot CLI runs without network, activation or SDK startup", () => {
  const script = `
    import net from 'node:net';
    import tls from 'node:tls';
    import dns from 'node:dns';
    import http from 'node:http';
    import https from 'node:https';
    import { syncBuiltinESMExports } from 'node:module';
    const forbidden = () => { throw new Error('NETWORK_FORBIDDEN'); };
    globalThis.fetch = forbidden;
    net.connect = net.createConnection = forbidden;
    tls.connect = forbidden;
    dns.lookup = dns.resolve = forbidden;
    dns.promises.lookup = dns.promises.resolve = forbidden;
    http.request = http.get = https.request = https.get = forbidden;
    syncBuiltinESMExports();
    process.argv[1] = ${JSON.stringify(command)};
    await import(${JSON.stringify(new URL("./measurement-preflight.js", import.meta.url).href)});
  `;
  const result = spawnSync(
    process.execPath,
    ["--input-type=module", "--eval", script],
    { env: environment, encoding: "utf8", timeout: 10000, windowsHide: true },
  );
  assert.equal(result.status, 0);
  assert.equal(result.stderr, "");
  const snapshot = JSON.parse(result.stdout) as Record<string, unknown>;
  assert.equal(snapshot.component, "AGENT");
  assert.equal(snapshot.enabled, true);
  assert.equal(snapshot.ttsReservationEnabled, false);
  assert.equal(Number.isFinite(Date.parse(String(snapshot.capturedAt))), true);
  assert.equal(result.stdout.includes("canary"), false);
});

void test("invalid Agent configuration fails CLI closed without leaking input or Zod details", () => {
  const result = spawnSync(process.execPath, [command], {
    env: {
      ...environment,
      AI_VOICE_FINAL_AGGREGATION_MS: "private-invalid-canary",
    },
    encoding: "utf8",
    timeout: 10000,
    windowsHide: true,
  });
  assert.equal(result.status, 1);
  assert.equal(result.stdout, "");
  assert.equal(
    result.stderr,
    "VOICE_MEASUREMENT_PREFLIGHT_CONFIGURATION_INVALID\n",
  );
  assert.equal(result.stderr.includes("canary"), false);
  assert.equal(result.stderr.includes("Zod"), false);
});
