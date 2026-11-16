import assert from "node:assert/strict";
import test from "node:test";
import { voicePilotSchema } from "./voice-pilot.js";
import { measurementPolicy } from "./voice-measurement-policy.js";

const config = () => ({
  startsAt: "2030-01-01T00:00:00Z",
  endsAt: "2030-01-01T00:30:00Z",
  ownerIds: [
    "00000000-0000-4000-8000-000000000001",
    "00000000-0000-4000-8000-000000000002",
  ],
  maxTurns: 30,
  maxSessionSeconds: 600,
  measurement: {
    campaignId: "00000000-0000-4000-8000-000000000003",
    maxSessions: 2,
    maxConcurrentSessions: 1,
    maxTurns: 12,
    maxVoiceSeconds: 480,
    maxTtsCharacters: 4000,
    voiceId: "8e8f222d-c817-4cc5-822b-8bf76ca7e98d",
  },
});
void test("measurement partitions full conservative allocations without expanding legacy pilot bounds", () => {
  const p = measurementPolicy(voicePilotSchema.parse(config()));
  assert.ok(p);
  assert.deepEqual(p.allocation, {
    turns: 6,
    voiceSeconds: 240,
    ttsCharacters: 2000,
  });
  assert.equal(p.caps.concurrent, 1);
  const small = measurementPolicy(
    voicePilotSchema.parse({ ...config(), maxTurns: 2, maxSessionSeconds: 60 }),
  );
  assert.deepEqual(small?.allocation, {
    turns: 2,
    voiceSeconds: 60,
    ttsCharacters: 2000,
  });
  assert.equal(measurementPolicy(), undefined);
  assert.equal(
    measurementPolicy(
      voicePilotSchema.parse({
        startsAt: config().startsAt,
        endsAt: config().endsAt,
        ownerIds: config().ownerIds,
      }),
    ),
    undefined,
  );
});
void test("measurement hash is stable for owner ordering but binds every approved cap, voice and time", () => {
  const original = config();
  const a = measurementPolicy(voicePilotSchema.parse(original));
  assert.ok(a);
  const reversed = measurementPolicy(
    voicePilotSchema.parse({
      ...original,
      ownerIds: [...original.ownerIds].reverse(),
    }),
  );
  assert.equal(a.configHash, reversed?.configHash);
  for (const changed of [
    { ...original, endsAt: "2030-01-01T00:29:00Z" },
    { ...original, measurement: { ...original.measurement, maxTurns: 10 } },
    {
      ...original,
      measurement: {
        ...original.measurement,
        voiceId: "b8cd71e3-bc14-4538-a530-d6314731c036",
      },
    },
    { ...original, replyStyle: "SUPPORTIVE" },
  ])
    assert.notEqual(
      a.configHash,
      measurementPolicy(voicePilotSchema.parse(changed))?.configHash,
    );
});
void test("measurement rejects excessive scope, duplicate owners, schema bypass and non-300 output without altering legacy pilot", () => {
  const value = config();
  for (const changed of [
    { ...value, maxOutputTokens: 600 },
    { ...value, endsAt: "2030-01-01T01:00:00Z" },
    { ...value, ownerIds: [value.ownerIds[0], value.ownerIds[0]] },
    {
      ...value,
      measurement: { ...value.measurement, maxConcurrentSessions: 2 },
    },
    { ...value, measurement: { ...value.measurement, maxTurns: 13 } },
    { ...value, measurement: { ...value.measurement, maxVoiceSeconds: 481 } },
    { ...value, measurement: { ...value.measurement, maxTtsCharacters: 4001 } },
    { ...value, measurement: { ...value.measurement, enforced: true } },
  ])
    assert.equal(voicePilotSchema.safeParse(changed).success, false);
  const { measurement: _unused, ...legacy } = value;
  void _unused;
  assert.equal(
    voicePilotSchema.safeParse({
      ...legacy,
      endsAt: "2030-01-01T01:00:00Z",
      maxOutputTokens: 600,
    }).success,
    true,
  );
});
