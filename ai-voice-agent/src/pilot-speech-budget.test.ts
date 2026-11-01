import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import test from "node:test";
import { PilotSpeechBudget } from "./pilot-speech-budget.js";
import { loadConfiguration } from "./configuration.js";

void test("pilot reserves TTS before provider work and does not refund interrupted or failed speech", () => {
  const budget = new PilotSpeechBudget();
  budget.reserve("a".repeat(500));
  budget.reserve("b".repeat(500));
  assert.throws(() => {
    budget.reserve("c".repeat(201));
  });
  budget.reserve("d".repeat(200));
  assert.throws(() => {
    budget.reserve("x");
  });
});

void test("an approved pilot can reserve up to its configured cap without refunding interrupted or unknown speech", () => {
  const budget = new PilotSpeechBudget(8000);
  budget.reserve("a".repeat(3000));
  budget.reserve("b".repeat(3000));
  assert.throws(() => {
    budget.reserve("c".repeat(2001));
  });
  budget.reserve("d".repeat(2000));
  assert.throws(() => {
    budget.reserve("x");
  });
});

void test("pilot TTS configuration defaults safely and accepts only bounded integer budgets", () => {
  assert.equal(loadConfiguration({}).pilotTtsMaxCharacters, 1200);
  for (const maximum of [1, 3000, 8000]) {
    assert.equal(
      loadConfiguration({
        AI_VOICE_PILOT_TTS_MAX_CHARACTERS: String(maximum),
      }).pilotTtsMaxCharacters,
      maximum,
    );
  }
  for (const invalid of [0, -1, 1.5, 8001, Number.NaN, Infinity]) {
    assert.throws(() => new PilotSpeechBudget(invalid));
    assert.throws(() =>
      loadConfiguration({
        AI_VOICE_PILOT_TTS_MAX_CHARACTERS: String(invalid),
      }),
    );
  }
});

void test("final aggregation configuration preserves the default and bounds a shorter speech pause", () => {
  assert.equal(loadConfiguration({}).finalAggregationMs, 1500);
  for (const milliseconds of [500, 750, 1500]) {
    assert.equal(
      loadConfiguration({
        AI_VOICE_FINAL_AGGREGATION_MS: String(milliseconds),
      }).finalAggregationMs,
      milliseconds,
    );
  }
  for (const invalid of [0, 499, 750.5, 1501, Number.NaN, Infinity]) {
    assert.throws(() =>
      loadConfiguration({ AI_VOICE_FINAL_AGGREGATION_MS: String(invalid) }),
    );
  }
});

void test("pilot pins Nova-3 and Sonic 3 and explicitly accepts approved Lien or historical Xia", () => {
  const base = {
    AI_VOICE_PILOT: "true",
    AI_VOICE_TTS_VOICE_ID: "b8cd71e3-bc14-4538-a530-d6314731c036",
  };
  const historical = loadConfiguration(base);
  assert.equal(historical.pilot, true);
  assert.equal(historical.voiceId, base.AI_VOICE_TTS_VOICE_ID);
  const current = loadConfiguration({
    ...base,
    AI_VOICE_TTS_VOICE_ID: "8e8f222d-c817-4cc5-822b-8bf76ca7e98d",
  });
  assert.equal(current.voiceId, "8e8f222d-c817-4cc5-822b-8bf76ca7e98d");
  assert.equal(current.sttModel, "deepgram/nova-3");
  assert.equal(current.ttsModel, "cartesia/sonic-3-2026-01-12");
  assert.throws(() =>
    loadConfiguration({ ...base, AI_VOICE_STT_MODEL: "deepgram/nova-2" }),
  );
  assert.throws(() =>
    loadConfiguration({
      ...base,
      AI_VOICE_TTS_MODEL: "cartesia/sonic-3",
    }),
  );
  for (const voice of [undefined, "", randomUUID(), "unknown"])
    assert.throws(() =>
      loadConfiguration({ ...base, AI_VOICE_TTS_VOICE_ID: voice }),
    );
});
