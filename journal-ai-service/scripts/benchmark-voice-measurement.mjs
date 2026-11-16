import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { createRequire } from "node:module";
import { performance } from "node:perf_hooks";
import { MongoClient } from "mongodb";
import { MongoVoiceLedger } from "../dist/companion-voice/voice-ledger.js";
import { voicePilotSchema } from "../dist/companion-voice/voice-pilot.js";
const uri = process.env.JOURNAL_INTEGRATION_MONGODB_URI;
if (!uri) throw new Error("DISPOSABLE_LOOPBACK_MONGO_REQUIRED");
const parsed = new URL(uri);
assert(
  ["127.0.0.1", "localhost"].includes(parsed.hostname) &&
    !parsed.username &&
    !parsed.password,
);
const require = createRequire(import.meta.url);
const migrations = [
  require("../migrations/013_companion_voice_quota.cjs"),
  require("../migrations/020_voice_measurement_campaigns.cjs"),
];
const client = new MongoClient(uri);
const timings = {
  baselineAdmissionMs: [],
  measurementAdmissionMs: [],
  ownerPreflightMs: [],
};
let calls = 0;
globalThis.fetch = () => {
  calls++;
  throw new Error("PROVIDER_NETWORK_FORBIDDEN");
};
try {
  await client.connect();
  for (let i = 0; i < 20; i++) {
    for (const mode of ["baseline", "measurement"]) {
      const dbName = `voice_measurement_benchmark_${Date.now()}_${i}_${mode}`;
      assert.match(
        dbName,
        /^voice_measurement_benchmark_\d+_\d+_(baseline|measurement)$/,
      );
      const db = client.db(dbName);
      try {
        for (const migration of migrations) await migration.up(db);
        const now = new Date();
        const ownerId = randomUUID();
        const pilot = voicePilotSchema.parse({
          startsAt: new Date(now.getTime() - 1000).toISOString(),
          endsAt: new Date(now.getTime() + 1_799_000).toISOString(),
          ownerIds: [ownerId],
          maxSessionSeconds: 240,
          maxTurns: 6,
          ...(mode === "measurement"
            ? {
                measurement: {
                  campaignId: randomUUID(),
                  maxSessions: 2,
                  maxConcurrentSessions: 1,
                  maxTurns: 12,
                  maxVoiceSeconds: 480,
                  maxTtsCharacters: 4000,
                  voiceId: "8e8f222d-c817-4cc5-822b-8bf76ca7e98d",
                },
              }
            : {}),
        });
        const ledger = new MongoVoiceLedger(client, dbName, pilot);
        if (mode === "measurement") await ledger.measurements.provision(now);
        const started = performance.now();
        const session = await ledger.reserve({
          voiceSessionId: randomUUID(),
          ownerId,
          keyHash: Buffer.from(randomUUID()).toString("base64url").slice(0, 43),
          fingerprint: "f".repeat(43),
          now,
          entitlement: {
            packageCode: "PLUS",
            source: "DEMO",
            entitlementVersion: "synthetic-v1",
            checkedAt: now,
            validUntil: new Date(now.getTime() + 3600000),
          },
        });
        timings[
          mode === "baseline" ? "baselineAdmissionMs" : "measurementAdmissionMs"
        ].push(performance.now() - started);
        assert.equal(session.admittedSeconds, 240);
        if (mode === "measurement") {
          const checked = performance.now();
          const evidence = await ledger.measurements.preflight(now);
          timings.ownerPreflightMs.push(performance.now() - checked);
          assert.equal(evidence.reservedSessions, 1);
          assert.equal(evidence.reservedTurns, 6);
          assert.equal(evidence.reservedVoiceSeconds, 240);
          assert.equal(evidence.reservedTtsCharacters, 2000);
        }
      } finally {
        await db.dropDatabase();
      }
    }
  }
} finally {
  await client.close();
}
assert.equal(calls, 0);
const distribution = (values) => {
  values.sort((a, b) => a - b);
  return {
    samples: values.length,
    p50Ms: values[Math.ceil(values.length * 0.5) - 1],
    p95Ms: values[Math.ceil(values.length * 0.95) - 1],
    worstMs: values.at(-1),
  };
};
console.log(
  JSON.stringify({
    scope: "DISPOSABLE_MONGO_ENFORCEMENT_OVERHEAD_ONLY_NOT_VOICE_LATENCY",
    quantileMethod: "NEAREST_RANK",
    ...Object.fromEntries(
      Object.entries(timings).map(([key, value]) => [key, distribution(value)]),
    ),
    liveProviderCalls: calls,
    liveAdmissions: 0,
    newLiveWindows: 0,
  }),
);
