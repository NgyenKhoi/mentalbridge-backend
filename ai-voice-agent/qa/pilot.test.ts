import assert from "node:assert/strict";
import test from "node:test";
import {
  PilotBudget,
  operatorSnapshot,
  pilotLimits,
  readiness,
} from "./pilot.js";
import { BenchmarkFailure } from "./metrics.js";
import { mockPilot } from "./mock.js";
import { ttsModel } from "./corpus.js";

void test("a reservation crossing the campaign ceiling is denied before work without resetting prior usage", () => {
  const prior = {
    campaign: "MB-760-first-benchmark",
    creditMicroUsd: 499999,
    sttMs: 1000,
    ttsCharacters: 10,
  };
  const budget = new PilotBudget(prior);
  assert.throws(
    () =>
      budget.reserve({ kind: "STT", model: "deepgram/nova-3", audioMs: 1000 }),
    /BUDGET_EXCEEDED/u,
  );
  assert.deepEqual(budget.snapshot(), prior);
  assert.throws(
    () => new PilotBudget({ ...prior, campaign: "new-budget-for-each-rerun" }),
    BenchmarkFailure,
  );
});

void test("no usage, zero invoice and published plan credits never stand in for a verified remaining allowance", () => {
  const result = readiness(operatorSnapshot);
  assert.equal(result.status, "BLOCKED_ALLOWANCE");
  assert.ok(result.blockers.includes("BLOCKED_RECORDING"));
  assert.ok(result.blockers.includes("BLOCKED_EGRESS"));
  assert.equal(result.providerExecutionAvailable, false);
  assert.equal(
    readiness({ ...operatorSnapshot, remainingInferenceMicroUsd: 499999 })
      .status,
    "BLOCKED_INSUFFICIENT_ALLOWANCE",
  );
  const allOff = {
    ...operatorSnapshot,
    remainingInferenceMicroUsd: 2500000,
    recording: "VERIFIED_OFF",
    egress: "VERIFIED_OFF",
    externalContentExport: "VERIFIED_OFF",
  };
  assert.equal(readiness(allOff).status, "REQUIRES_OPERATOR_AUTHORIZATION");
  assert.equal(readiness(allOff).providerExecutionAvailable, false);
  assert.throws(
    () => readiness({ ...allOff, secret: "sensitive" }),
    BenchmarkFailure,
  );
});
void test("one campaign budget covers both models and TTS without resetting per request", () => {
  const budget = new PilotBudget();
  budget.reserve({ kind: "STT", model: "deepgram/nova-3", audioMs: 60000 });
  budget.reserve({ kind: "STT", model: "deepgram/nova-2", audioMs: 60000 });
  budget.reserve({ kind: "TTS", model: ttsModel, characters: 100 });
  assert.deepEqual(budget.snapshot(), {
    campaign: "MB-760-first-benchmark",
    creditMicroUsd: 15600,
    sttMs: 120000,
    ttsCharacters: 100,
  });
  const resumed = new PilotBudget(budget.snapshot());
  resumed.reserve({ kind: "STT", model: "deepgram/nova-3", audioMs: 1 });
  assert.equal(resumed.snapshot().sttMs, 121000);
  assert.equal(resumed.snapshot().creditMicroUsd, 15680);
});
void test("any exhausted cap halts all components; rejected reservations and mutated snapshots cannot restore allowance", () => {
  for (const prior of [
    {
      campaign: "MB-760-first-benchmark",
      creditMicroUsd: pilotLimits.creditMicroUsd,
      sttMs: 0,
      ttsCharacters: 0,
    },
    {
      campaign: "MB-760-first-benchmark",
      creditMicroUsd: 0,
      sttMs: pilotLimits.sttMs,
      ttsCharacters: 0,
    },
    {
      campaign: "MB-760-first-benchmark",
      creditMicroUsd: 0,
      sttMs: 0,
      ttsCharacters: pilotLimits.ttsCharacters,
    },
  ]) {
    const budget = new PilotBudget(prior);
    assert.throws(
      () =>
        budget.reserve({ kind: "STT", model: "deepgram/nova-3", audioMs: 1 }),
      /BUDGET_EXCEEDED/u,
    );
    assert.throws(
      () => budget.reserve({ kind: "TTS", model: ttsModel, characters: 1 }),
      /BUDGET_EXCEEDED/u,
    );
    budget.snapshot().sttMs = 0;
    assert.deepEqual(budget.snapshot(), prior);
  }
  const budget = new PilotBudget();
  assert.throws(
    () => budget.reserve({ kind: "TTS", model: ttsModel, characters: 3001 }),
    BenchmarkFailure,
  );
  assert.equal(budget.snapshot().ttsCharacters, 0);
  assert.throws(
    () => budget.reserve({ kind: "STT", model: "unapproved", audioMs: 100 }),
    BenchmarkFailure,
  );
});
void test("mock pilot reports no account spend/device/speaker evidence and never marks synthetic accuracy as a live result", async () => {
  const result = await mockPilot("b".repeat(40));
  assert.equal(result.providerCalls, 0);
  assert.equal(result.actualSpendMicroUsd, 0);
  assert.equal(result.readiness.status, "BLOCKED_ALLOWANCE");
  assert.equal(result.evaluation.simulatedOnly.trials, 26);
  assert.equal(result.evaluation.humanScripted.trials, 0);
  assert.equal(result.evaluation.coverage.missingCells.length, 48);
  assert.deepEqual(result.actualDevicesTested, []);
  assert.ok(result.simulatedReservations.sttMs <= pilotLimits.sttMs);
  assert.ok(
    result.simulatedReservations.ttsCharacters <= pilotLimits.ttsCharacters,
  );
});
void test("unknown provider failure is sanitized, never retried, and aborts a timed-out mock operation", async () => {
  let calls = 0;
  await assert.rejects(
    mockPilot("b".repeat(40), () => {
      calls++;
      return Promise.reject(new Error("private provider response"));
    }),
    (error) =>
      error instanceof BenchmarkFailure && error.message === "PROVIDER_FAILED",
  );
  assert.equal(calls, 1);
  let cancelled = false;
  await assert.rejects(
    mockPilot(
      "b".repeat(40),
      (_text, signal) =>
        new Promise((_resolve, reject) => {
          signal.addEventListener(
            "abort",
            () => {
              cancelled = true;
              reject(new Error("CANCELLED"));
            },
            { once: true },
          );
        }),
      10,
    ),
    BenchmarkFailure,
  );
  assert.equal(cancelled, true);
});
