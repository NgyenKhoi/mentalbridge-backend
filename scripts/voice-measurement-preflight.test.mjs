import assert from "node:assert/strict";
import test from "node:test";
import { execFileSync, spawnSync } from "node:child_process";
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { evaluateVoiceMeasurement } from "./voice-measurement-preflight.mjs";

const now = Date.parse("2026-11-16T07:00:00.000Z");
const start = "2026-11-16T06:55:00.000Z",
  end = "2026-11-16T07:25:00.000Z";
export const fixture = () => ({
  journal: {
    schemaVersion: 1,
    component: "JOURNAL",
    capturedAt: new Date(now).toISOString(),
    voiceEnabled: true,
    providerEnabled: true,
    inputGuardEnabled: true,
    inputTokenLimit: 1900,
    outputTokenLimit: 300,
    pilot: {
      startsAt: start,
      endsAt: end,
      ownerCount: 1,
      maxSessionSeconds: 240,
      maxTurnsPerSession: 6,
    },
    concurrencyLimit: 2,
  },
  agent: {
    schemaVersion: 1,
    component: "AGENT",
    capturedAt: new Date(now).toISOString(),
    enabled: true,
    pilotEnabled: true,
    ttsReservationEnabled: true,
    ttsCharactersPerAttempt: 2000,
    configuredTtsCharactersPerAttempt: 2000,
    finalAggregationMs: 750,
    sttModel: "deepgram/nova-3",
    ttsModel: "cartesia/sonic-3-2026-01-12",
    voiceConfigured: true,
  },
  approval: {
    schemaVersion: 1,
    environment: "staging",
    startsAt: start,
    endsAt: end,
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
});

test("current staging nonpilot configuration is blocked with explicit effective limits", () => {
  const input = fixture();
  Object.assign(input.journal, {
    pilot: null,
    inputGuardEnabled: false,
    inputTokenLimit: null,
    outputTokenLimit: 2000,
  });
  Object.assign(input.agent, {
    pilotEnabled: false,
    ttsReservationEnabled: false,
    ttsCharactersPerAttempt: null,
  });
  const result = evaluateVoiceMeasurement(input, now);
  for (const code of [
    "INPUT_GUARD_DISABLED",
    "OUTPUT_CAP_EXCEEDED",
    "TTS_RESERVATION_DISABLED",
    "JOURNAL_PILOT_REQUIRED",
  ])
    assert(result.codes.includes(code));
  assert.equal(result.limits.ttsCharactersPerAttempt, null);
  assert.equal(result.liveApproved, false);
});

test("per-session configured limits never masquerade as shared campaign enforcement", () => {
  const result = evaluateVoiceMeasurement(fixture(), now);
  assert.equal(result.checks.journalPerSessionLimits, "CONFIGURED");
  assert.equal(result.checks.agentPerAttemptLimits, "CONFIGURED");
  for (const code of [
    "AGGREGATE_ENFORCEMENT_UNVERIFIED",
    "CONCURRENCY_LIMIT_EXCEEDED",
    "CONCURRENCY_ENFORCEMENT_UNVERIFIED",
    "CURRENT_AUTHORITY_NOT_CHECKED",
  ])
    assert(result.codes.includes(code));
  assert.equal(result.status, "BLOCKED");
  assert.deepEqual(result.actions, {
    admissionCreated: false,
    providerCalled: false,
    windowOpened: false,
  });
});

test("Friend-only input guard cannot hide its nonpilot 2000 output ceiling", () => {
  const input = fixture();
  input.journal.pilot = null;
  input.journal.outputTokenLimit = 2000;
  const result = evaluateVoiceMeasurement(input, now);
  assert(!result.codes.includes("INPUT_GUARD_DISABLED"));
  assert(result.codes.includes("OUTPUT_CAP_EXCEEDED"));
  assert(result.codes.includes("JOURNAL_PILOT_REQUIRED"));
});

test("expired, future and mismatched windows cannot start or extend approval", () => {
  for (const [clock, expected] of [
    [Date.parse(end), "APPROVAL_EXPIRED"],
    [Date.parse(start) - 1, "APPROVAL_NOT_STARTED"],
  ]) {
    assert(evaluateVoiceMeasurement(fixture(), clock).codes.includes(expected));
  }
  const input = fixture();
  input.journal.pilot.endsAt = "2026-11-16T07:26:00.000Z";
  assert(
    evaluateVoiceMeasurement(input, now).codes.includes(
      "PILOT_APPROVAL_WINDOW_MISMATCH",
    ),
  );
});

test("stale, future or inconsistent runtime snapshots fail closed", () => {
  const input = fixture();
  input.journal.capturedAt = new Date(now - 60001).toISOString();
  input.agent.capturedAt = new Date(now + 2001).toISOString();
  const codes = evaluateVoiceMeasurement(input, now).codes;
  assert(codes.includes("JOURNAL_SNAPSHOT_STALE"));
  assert(codes.includes("AGENT_SNAPSHOT_IN_FUTURE"));
  const contradictory = fixture();
  contradictory.agent.pilotEnabled = false;
  assert.throws(
    () => evaluateVoiceMeasurement(contradictory, now),
    /^Error: INVALID_PREFLIGHT_INPUT$/,
  );
});

test("oversized per-session limits and changed pins are rejected", () => {
  const input = fixture();
  input.journal.outputTokenLimit = 600;
  input.journal.pilot.maxTurnsPerSession = 30;
  input.journal.pilot.maxSessionSeconds = 600;
  input.agent.ttsCharactersPerAttempt = 8000;
  input.agent.configuredTtsCharactersPerAttempt = 8000;
  input.agent.sttModel = "deepgram/nova-2";
  for (const code of [
    "OUTPUT_CAP_EXCEEDED",
    "PER_SESSION_TURN_CAP_EXCEEDED",
    "PER_SESSION_DURATION_CAP_EXCEEDED",
    "TTS_ATTEMPT_CAP_EXCEEDED",
    "APPROVED_STT_PIN_REQUIRED",
  ])
    assert(evaluateVoiceMeasurement(input, now).codes.includes(code));
});

test("untrusted values and manual enforcement claims never leak or grant readiness", () => {
  for (const target of ["journal", "agent", "approval"]) {
    const input = fixture();
    input[target].privateCanary = "PRIVATE_CANARY_DO_NOT_PRINT";
    assert.throws(
      () => evaluateVoiceMeasurement(input, now),
      /^Error: INVALID_PREFLIGHT_INPUT$/,
    );
  }
  const input = fixture();
  input.approval.caps.aggregateEnforced = true;
  assert.throws(
    () => evaluateVoiceMeasurement(input, now),
    /^Error: INVALID_PREFLIGHT_INPUT$/,
  );
  const invalidDate = fixture();
  invalidDate.approval.startsAt = "2026-11-31T06:55:00Z";
  assert.throws(
    () => evaluateVoiceMeasurement(invalidDate, now),
    /^Error: INVALID_PREFLIGHT_INPUT$/,
  );
});

test("evaluation is pure and performs zero network calls or input mutation", () => {
  const original = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = () => {
    calls++;
    throw new Error("NETWORK_FORBIDDEN");
  };
  const input = fixture(),
    before = JSON.stringify(input);
  try {
    evaluateVoiceMeasurement(input, now);
    assert.equal(calls, 0);
    assert.equal(JSON.stringify(input), before);
  } finally {
    globalThis.fetch = original;
  }
});

test("CLI returns nonzero and sanitized JSON without exposing invalid file data or paths", () => {
  const dir = mkdtempSync(join(tmpdir(), "mb861-preflight-"));
  const script = fileURLToPath(
    new URL("./voice-measurement-preflight.mjs", import.meta.url),
  );
  try {
    const input = fixture();
    const paths = ["journal", "agent", "approval"].map((kind) => {
      const file = join(dir, kind + ".json");
      writeFileSync(file, JSON.stringify(input[kind]));
      return file;
    });
    const args = [
      script,
      "--journal",
      paths[0],
      "--agent",
      paths[1],
      "--approval",
      paths[2],
    ];
    const blocked = spawnSync(process.execPath, args, { encoding: "utf8" });
    assert.equal(blocked.status, 2);
    assert.equal(JSON.parse(blocked.stdout).status, "BLOCKED");
    writeFileSync(paths[0], "{PRIVATE_CANARY_DO_NOT_PRINT");
    const invalid = spawnSync(process.execPath, args, { encoding: "utf8" });
    assert.equal(invalid.status, 1);
    assert.equal(invalid.stdout, "");
    assert(!invalid.stderr.includes("PRIVATE_CANARY"));
    assert(!invalid.stderr.includes(dir));
    assert.deepEqual(JSON.parse(invalid.stderr).codes, [
      "INVALID_PREFLIGHT_INPUT",
    ]);
    assert.throws(() =>
      execFileSync(process.execPath, [script, "--journal", dir], {
        stdio: "pipe",
      }),
    );
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
