import assert from "node:assert/strict";
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

void test("pilot pins Nova-3 and Xia rather than silently changing approved providers", () => {
  const base = {
    AI_VOICE_PILOT: "true",
    AI_VOICE_TTS_VOICE_ID: "b8cd71e3-bc14-4538-a530-d6314731c036",
  };
  assert.equal(loadConfiguration(base).pilot, true);
  assert.throws(() =>
    loadConfiguration({ ...base, AI_VOICE_STT_MODEL: "deepgram/nova-2" }),
  );
  assert.throws(() =>
    loadConfiguration({
      ...base,
      AI_VOICE_TTS_VOICE_ID: "8e8f222d-c817-4cc5-822b-8bf76ca7e98d",
    }),
  );
});
