import { readFileSync, statSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const own = (value, keys) =>
  value !== null &&
  typeof value === "object" &&
  !Array.isArray(value) &&
  Object.keys(value).length === keys.length &&
  keys.every((key) => Object.hasOwn(value, key));
const integer = (value, low, high) =>
  Number.isInteger(value) && value >= low && value <= high;
const instant = (value) => {
  if (
    typeof value !== "string" ||
    !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?Z$/.test(value) ||
    !Number.isFinite(Date.parse(value))
  )
    return false;
  const canonical = value.replace(
    /(?:\.(\d{1,3}))?Z$/,
    (_match, fraction) => `.${(fraction ?? "").padEnd(3, "0")}Z`,
  );
  return new Date(value).toISOString() === canonical;
};
const boolean = (value) => typeof value === "boolean";
const invalid = () => {
  throw new Error("INVALID_PREFLIGHT_INPUT");
};

function journalShape(value) {
  if (
    !own(value, [
      "schemaVersion",
      "component",
      "capturedAt",
      "voiceEnabled",
      "providerEnabled",
      "inputGuardEnabled",
      "inputTokenLimit",
      "outputTokenLimit",
      "pilot",
      "concurrencyLimit",
    ]) ||
    value.schemaVersion !== 1 ||
    value.component !== "JOURNAL" ||
    !instant(value.capturedAt) ||
    !["voiceEnabled", "providerEnabled", "inputGuardEnabled"].every((key) =>
      boolean(value[key]),
    ) ||
    value.inputTokenLimit !== (value.inputGuardEnabled ? 1900 : null) ||
    !integer(value.outputTokenLimit, 1, 2000) ||
    value.concurrencyLimit !== 2
  )
    invalid();
  const p = value.pilot;
  if (
    p !== null &&
    (!own(p, [
      "startsAt",
      "endsAt",
      "ownerCount",
      "maxSessionSeconds",
      "maxTurnsPerSession",
    ]) ||
      !instant(p.startsAt) ||
      !instant(p.endsAt) ||
      Date.parse(p.endsAt) <= Date.parse(p.startsAt) ||
      Date.parse(p.endsAt) - Date.parse(p.startsAt) > 86400000 ||
      !integer(p.ownerCount, 1, 2) ||
      !integer(p.maxSessionSeconds, 1, 600) ||
      !integer(p.maxTurnsPerSession, 1, 30))
  )
    invalid();
  if (
    (p !== null && !value.inputGuardEnabled) ||
    (p === null
      ? value.outputTokenLimit !== 2000
      : !integer(value.outputTokenLimit, 300, 600))
  )
    invalid();
  return value;
}

function agentShape(value) {
  if (
    !own(value, [
      "schemaVersion",
      "component",
      "capturedAt",
      "enabled",
      "pilotEnabled",
      "ttsReservationEnabled",
      "ttsCharactersPerAttempt",
      "configuredTtsCharactersPerAttempt",
      "finalAggregationMs",
      "sttModel",
      "ttsModel",
      "voiceConfigured",
    ]) ||
    value.schemaVersion !== 1 ||
    value.component !== "AGENT" ||
    !instant(value.capturedAt) ||
    ![
      "enabled",
      "pilotEnabled",
      "ttsReservationEnabled",
      "voiceConfigured",
    ].every((key) => boolean(value[key])) ||
    !integer(value.configuredTtsCharactersPerAttempt, 1, 8000) ||
    value.ttsReservationEnabled !== value.pilotEnabled ||
    value.ttsCharactersPerAttempt !==
      (value.pilotEnabled ? value.configuredTtsCharactersPerAttempt : null) ||
    !integer(value.finalAggregationMs, 500, 1500) ||
    !["deepgram/nova-3", "deepgram/nova-2"].includes(value.sttModel) ||
    value.ttsModel !== "cartesia/sonic-3-2026-01-12"
  )
    invalid();
  return value;
}

function approvalShape(value) {
  if (
    !own(value, [
      "schemaVersion",
      "environment",
      "startsAt",
      "endsAt",
      "caps",
    ]) ||
    value.schemaVersion !== 1 ||
    value.environment !== "staging" ||
    !instant(value.startsAt) ||
    !instant(value.endsAt) ||
    Date.parse(value.endsAt) <= Date.parse(value.startsAt) ||
    Date.parse(value.endsAt) - Date.parse(value.startsAt) > 1800000
  )
    invalid();
  const c = value.caps;
  if (
    !own(c, [
      "maxSessions",
      "maxConcurrentSessions",
      "maxTurns",
      "maxVoiceSeconds",
      "maxTtsCharacters",
      "maxInputTokens",
      "maxOutputTokens",
    ]) ||
    !integer(c.maxSessions, 1, 2) ||
    !integer(c.maxConcurrentSessions, 1, c.maxSessions) ||
    !integer(c.maxTurns, 1, 60) ||
    !integer(c.maxVoiceSeconds, 1, 1200) ||
    !integer(c.maxTtsCharacters, 1, 16000) ||
    !integer(c.maxInputTokens, 1, 1900) ||
    !integer(c.maxOutputTokens, 1, 600)
  )
    invalid();
  return value;
}

export function evaluateVoiceMeasurement(input, now = Date.now()) {
  if (!own(input, ["journal", "agent", "approval"]) || !Number.isFinite(now))
    invalid();
  const j = journalShape(input.journal),
    a = agentShape(input.agent),
    approval = approvalShape(input.approval);
  const codes = [];
  const fail = (condition, code) => {
    if (condition) codes.push(code);
  };
  for (const [value, name] of [
    [j, "JOURNAL"],
    [a, "AGENT"],
  ]) {
    const age = now - Date.parse(value.capturedAt);
    fail(age > 60000, `${name}_SNAPSHOT_STALE`);
    fail(age < -2000, `${name}_SNAPSHOT_IN_FUTURE`);
  }
  fail(now < Date.parse(approval.startsAt), "APPROVAL_NOT_STARTED");
  fail(now >= Date.parse(approval.endsAt), "APPROVAL_EXPIRED");
  const journalCodes = [];
  const jf = (condition, code) => {
    if (condition) journalCodes.push(code);
  };
  jf(!j.voiceEnabled, "VOICE_DISABLED");
  jf(!j.providerEnabled, "VOICE_PROVIDER_DISABLED");
  jf(!j.inputGuardEnabled, "INPUT_GUARD_DISABLED");
  jf(
    j.inputTokenLimit !== null &&
      j.inputTokenLimit > approval.caps.maxInputTokens,
    "INPUT_CAP_EXCEEDED",
  );
  jf(j.outputTokenLimit > approval.caps.maxOutputTokens, "OUTPUT_CAP_EXCEEDED");
  jf(j.pilot === null, "JOURNAL_PILOT_REQUIRED");
  if (j.pilot) {
    jf(
      Date.parse(j.pilot.startsAt) !== Date.parse(approval.startsAt) ||
        Date.parse(j.pilot.endsAt) !== Date.parse(approval.endsAt),
      "PILOT_APPROVAL_WINDOW_MISMATCH",
    );
    jf(
      j.pilot.maxTurnsPerSession > approval.caps.maxTurns,
      "PER_SESSION_TURN_CAP_EXCEEDED",
    );
    jf(
      j.pilot.maxSessionSeconds > approval.caps.maxVoiceSeconds,
      "PER_SESSION_DURATION_CAP_EXCEEDED",
    );
  }
  const agentCodes = [];
  const af = (condition, code) => {
    if (condition) agentCodes.push(code);
  };
  af(!a.enabled, "AGENT_DISABLED");
  af(!a.pilotEnabled || !a.ttsReservationEnabled, "TTS_RESERVATION_DISABLED");
  af(
    a.ttsCharactersPerAttempt !== null &&
      a.ttsCharactersPerAttempt > approval.caps.maxTtsCharacters,
    "TTS_ATTEMPT_CAP_EXCEEDED",
  );
  af(a.sttModel !== "deepgram/nova-3", "APPROVED_STT_PIN_REQUIRED");
  af(!a.voiceConfigured, "VOICE_NOT_CONFIGURED");
  codes.push(...journalCodes, ...agentCodes);
  fail(
    j.concurrencyLimit > approval.caps.maxConcurrentSessions,
    "CONCURRENCY_LIMIT_EXCEEDED",
  );
  codes.push(
    "AGGREGATE_ENFORCEMENT_UNVERIFIED",
    "CONCURRENCY_ENFORCEMENT_UNVERIFIED",
    "CURRENT_AUTHORITY_NOT_CHECKED",
    "CREDIT_AND_CLOUD_PRIVACY_NOT_CHECKED",
  );
  return {
    schemaVersion: 1,
    status: "BLOCKED",
    liveApproved: false,
    checks: {
      journalPerSessionLimits: journalCodes.length ? "BLOCKED" : "CONFIGURED",
      agentPerAttemptLimits: agentCodes.length ? "BLOCKED" : "CONFIGURED",
      aggregateCampaignEnforcement: "UNVERIFIED",
      currentConsentEntitlementQuota: "NOT_CHECKED",
      providerCreditAndCloudPrivacy: "NOT_CHECKED",
    },
    codes,
    limits: {
      inputTokens: j.inputTokenLimit,
      outputTokens: j.outputTokenLimit,
      turnsPerSession: j.pilot?.maxTurnsPerSession ?? null,
      secondsPerSession: j.pilot?.maxSessionSeconds ?? null,
      ttsCharactersPerAttempt: a.ttsCharactersPerAttempt,
      configuredConcurrency: j.concurrencyLimit,
    },
    actions: {
      admissionCreated: false,
      providerCalled: false,
      windowOpened: false,
    },
  };
}

function readJson(file) {
  if (statSync(file).size > 16384) invalid();
  return JSON.parse(readFileSync(file, "utf8").replace(/^\uFEFF/, ""));
}

export function runPreflightCli(args) {
  if (
    args.length !== 6 ||
    args[0] !== "--journal" ||
    args[2] !== "--agent" ||
    args[4] !== "--approval"
  )
    invalid();
  return evaluateVoiceMeasurement({
    journal: readJson(args[1]),
    agent: readJson(args[3]),
    approval: readJson(args[5]),
  });
}

if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  try {
    console.log(
      JSON.stringify(runPreflightCli(process.argv.slice(2)), null, 2),
    );
    process.exitCode = 2;
  } catch {
    console.error(
      JSON.stringify({
        schemaVersion: 1,
        status: "BLOCKED",
        liveApproved: false,
        codes: ["INVALID_PREFLIGHT_INPUT"],
      }),
    );
    process.exitCode = 1;
  }
}
