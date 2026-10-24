import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const require = createRequire(
  new URL("../../journal-ai-service/package.json", import.meta.url),
);
const parserPath = require.resolve("@apidevtools/swagger-parser");
const SwaggerParser = require(parserPath);
const Ajv2020 = createRequire(parserPath)("ajv/dist/2020.js").default;
const load = async (file) =>
  JSON.parse(await readFile(new URL(file, import.meta.url), "utf8"));
const api = await SwaggerParser.validate(
  fileURLToPath(new URL("companion-voice-v1.openapi.json", import.meta.url)),
  { resolve: { external: false } },
);
const workerSchema = await load("companion-voice-worker-v1.schema.json");
const policySchema = await load("companion-voice-policy-v1.schema.json");
const policy = await load("../../docs/policies/companion-voice-policy-v1.json");
const lifecycle = await load("companion-voice-lifecycle-v1.json");
const examples = await load("companion-voice-v1.examples.json");
const states = [
  "REQUESTED",
  "AUTHORIZED",
  "CONNECTING",
  "ACTIVE",
  "RECONNECTING",
  "ENDING",
  "ENDED",
  "FAILED",
  "EXPIRED",
];
const terminalStates = ["ENDED", "FAILED", "EXPIRED"];

function validDateTime(value) {
  const parts =
    /^(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(?:[Zz]|[+-](\d{2}):(\d{2}))$/.exec(
      value,
    );
  if (!parts) return false;
  const [year, month, day, hour, minute, second, offsetHour, offsetMinute] =
    parts.slice(1).map((part) => Number(part ?? 0));
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  return (
    month >= 1 &&
    month <= 12 &&
    day >= 1 &&
    day <= days[month - 1] &&
    hour <= 23 &&
    minute <= 59 &&
    second <= 59 &&
    offsetHour <= 23 &&
    offsetMinute <= 59
  );
}

function compile(schema) {
  const ajv = new Ajv2020({ strict: true, allErrors: true });
  ajv.addFormat("uuid", /^[\da-f]{8}-(?:[\da-f]{4}-){3}[\da-f]{12}$/i);
  ajv.addFormat("date-time", validDateTime);
  ajv.addFormat(
    "date",
    (value) =>
      /^\d{4}-\d{2}-\d{2}$/.test(value) && validDateTime(`${value}T00:00:00Z`),
  );
  ajv.addFormat("uri", (value) => {
    try {
      return Boolean(new URL(value).protocol);
    } catch {
      return false;
    }
  });
  return ajv.compile(schema);
}

function component(name) {
  assert.ok(api.components.schemas[name], `Missing OpenAPI component ${name}`);
  return compile(api.components.schemas[name]);
}

function assertValid(validate, value, label) {
  assert.equal(
    validate(value),
    true,
    `${label}: ${JSON.stringify(validate.errors)}`,
  );
}

function positiveExample(schema) {
  const example = examples.openapiExamples.find(
    (item) => item.schema === schema && item.valid,
  );
  assert.ok(example, `Missing positive ${schema} example`);
  return structuredClone(example.value);
}

test("Voice proposal resolves OpenAPI 3.1 and strict-compiles every component", () => {
  assert.equal(api.openapi, "3.1.0");
  for (const name of Object.keys(api.components.schemas))
    assert.equal(typeof component(name), "function");
});

test("worker and policy schemas strict-compile and the machine-readable policy validates", () => {
  assert.equal(typeof compile(workerSchema), "function");
  assertValid(compile(policySchema), policy, "Companion Voice policy");
});

test("all Voice operations remain planned, owner-labelled and authenticated", () => {
  const operationIds = new Set();
  for (const [route, item] of Object.entries(api.paths)) {
    for (const method of [
      "get",
      "post",
      "put",
      "patch",
      "delete",
      "head",
      "options",
    ]) {
      const operation = item[method];
      if (!operation) continue;
      assert.equal(
        operation["x-mentalbridge-status"] ?? item["x-mentalbridge-status"],
        "planned",
        `${method} ${route}`,
      );
      assert.ok(
        operation["x-mentalbridge-owner"] ?? item["x-mentalbridge-owner"],
        `${method} ${route} needs an owner`,
      );
      const security = operation.security ?? api.security;
      assert.ok(
        Array.isArray(security) && security.length > 0,
        `${method} ${route} needs authentication`,
      );
      assert.ok(
        security.every((requirement) => Object.keys(requirement).length > 0),
        `${method} ${route} allows anonymous access`,
      );
      assert.ok(
        operation.operationId && !operationIds.has(operation.operationId),
        `${method} ${route} needs a unique operationId`,
      );
      operationIds.add(operation.operationId);
    }
  }
  assert.ok(operationIds.size > 0);
});

for (const [group, validator] of [
  ["policyExamples", () => compile(policySchema)],
  ["workerExamples", () => compile(workerSchema)],
  ["openapiExamples", (example) => component(example.schema)],
]) {
  assert.ok(
    Array.isArray(examples[group]) && examples[group].length > 0,
    `Missing ${group}`,
  );
  for (const example of examples[group]) {
    test(`${group}: ${example.name}`, () => {
      assert.equal(typeof example.valid, "boolean");
      const validate = validator(example);
      assert.equal(
        validate(example.value),
        example.valid,
        JSON.stringify(validate.errors),
      );
    });
  }
}

test("policy fixes finite independent package quotas and the Vietnamese quota day", () => {
  assert.equal(policy.status, "APPROVED_TARGET_NOT_IMPLEMENTED");
  assert.equal(policy.timeZone, "Asia/Ho_Chi_Minh");
  assert.deepEqual(policy.packages, {
    FREE: {
      voiceEligible: false,
      sessionsPerDay: 0,
      dailySeconds: 0,
      perSessionSeconds: 0,
    },
    PLUS: {
      voiceEligible: true,
      sessionsPerDay: 2,
      dailySeconds: 600,
      perSessionSeconds: 300,
    },
    PREMIUM: {
      voiceEligible: true,
      sessionsPerDay: 3,
      dailySeconds: 1800,
      perSessionSeconds: 600,
    },
  });
  assert.equal(policy.limits.globalSessions, 2);
  assert.equal(policy.limits.userSessions, 1);
  assert.equal(policy.limits.idleTimeoutSeconds, 60);
  assert.equal(policy.limits.reconnectGraceSeconds, 30);
  assert.equal(policy.limits.authorizationLeaseSeconds, 10);
  assert.equal(policy.limits.leaseRenewalSeconds, 5);
  assert.equal(policy.usage.dayAssignment, "AUTHORIZED_LOCAL_DAY");
  assert.equal(policy.usage.reset, "LOCAL_MIDNIGHT_NO_ROLLOVER");
  assert.equal(
    policy.usage.hardDeadline,
    "AUTHORIZED_AT_PLUS_ADMITTED_SECONDS_NEVER_EXTENDED",
  );
  assert.equal(
    policy.usage.sessionConsumedOn,
    "FIRST_TRUSTED_VALIDATED_AI_AUDIO_PUBLICATION_WHILE_OWNER_CONNECTED",
  );
  assert.equal(
    policy.usage.timeChargedFrom,
    "FIRST_REPLY_PUBLICATION_ACTIVE_INTERVALS_ONLY",
  );
  assert.equal(policy.usage.noDeliveredReply, "RELEASE_ALL_RESERVED_QUOTA");
  assert.equal(
    policy.usage.upgrade,
    "RAISE_CURRENT_DAY_CAP_WITHOUT_RESET_OR_SESSION_EXTENSION",
  );
});

test("privacy requires two independent Care consents, RAM-only context and content-free retention", () => {
  for (const option of [
    "recordAudio",
    "persistTranscripts",
    "agentObservability",
    "reuseJournal",
    "reuseTextHistory",
  ]) {
    assert.equal(policy.privacy[option], false, option);
    const changed = structuredClone(policy);
    changed.privacy[option] = true;
    assert.equal(compile(policySchema)(changed), false, option);
  }
  assert.equal(policy.privacy.contextStorage, "SESSION_RAM_ONLY");
  assert.equal(policy.privacy.metadataRetentionDays, 30);
  assert.deepEqual(policy.privacy.requiredConsents, [
    "AI_PROCESSING",
    "AI_VOICE_PROCESSING",
  ]);
  assert.equal(
    policy.privacy.voiceConsentVersion,
    "ai-voice-processing-capstone-v1",
  );
  assert.equal(policy.privacy.consentOwner, "CARE");
  assert.equal(policy.privacy.entitlementOwner, "CONSULTATION");
  const originalPolicy = approvedPolicyExample();
  assert.deepEqual(originalPolicy, policy);
});

function approvedPolicyExample() {
  const example = examples.policyExamples.find((item) => item.valid);
  assert.ok(example, "Missing approved policy fixture");
  return example.value;
}

test("lifecycle preserves exactly nine states, bounded cleanup and terminal immutability", () => {
  assert.deepEqual(
    Object.keys(lifecycle).sort(),
    [
      "schemaVersion",
      "policyVersion",
      "states",
      "initialState",
      "terminalStates",
      "capacityHeldStates",
      "transitions",
      "timers",
      "invariants",
    ].sort(),
  );
  assert.equal(lifecycle.schemaVersion, 1);
  assert.equal(lifecycle.policyVersion, policy.policyVersion);
  assert.deepEqual(lifecycle.states, states);
  assert.deepEqual(
    api.components.schemas.Session.properties.state.enum,
    states,
  );
  assert.equal(lifecycle.initialState, "REQUESTED");
  assert.deepEqual(lifecycle.terminalStates, terminalStates);
  assert.deepEqual(lifecycle.capacityHeldStates, [
    "AUTHORIZED",
    "CONNECTING",
    "ACTIVE",
    "RECONNECTING",
    "ENDING",
  ]);
  const expectedEdges = [
    "REQUESTED:AUTHORIZED",
    "REQUESTED:FAILED",
    "REQUESTED:EXPIRED",
    "AUTHORIZED:CONNECTING",
    "AUTHORIZED:ENDING",
    "CONNECTING:ACTIVE",
    "CONNECTING:ENDING",
    "ACTIVE:RECONNECTING",
    "ACTIVE:ENDING",
    "RECONNECTING:ACTIVE",
    "RECONNECTING:ENDING",
    "ENDING:ENDED",
    "ENDING:FAILED",
    "ENDING:EXPIRED",
  ];
  const edges = lifecycle.transitions.map(({ from, to }) => `${from}:${to}`);
  assert.deepEqual(edges.toSorted(), expectedEdges.toSorted());
  for (const transition of lifecycle.transitions) {
    assert.deepEqual(Object.keys(transition).sort(), ["from", "to", "trigger"]);
    assert.ok(
      states.includes(transition.from) && states.includes(transition.to),
    );
    assert.equal(terminalStates.includes(transition.from), false);
    assert.ok(transition.trigger.length > 0);
  }
  assert.deepEqual(lifecycle.timers, {
    requestSeconds: policy.limits.requestTimeoutSeconds,
    connectSeconds: policy.limits.connectTimeoutSeconds,
    idleSeconds: policy.limits.idleTimeoutSeconds,
    reconnectSeconds: policy.limits.reconnectGraceSeconds,
    authorizationLeaseSeconds: policy.limits.authorizationLeaseSeconds,
    renewEverySeconds: policy.limits.leaseRenewalSeconds,
  });
});

test("worker controls are closed, fenced and contain no audio, transcript, owner or provider credentials", () => {
  const validate = compile(workerSchema);
  const positive = examples.workerExamples.filter((item) => item.valid);
  assert.deepEqual(
    [...new Set(positive.map((item) => item.value.kind))].sort(),
    [
      "DISPATCH",
      "LEASE_RENEWED",
      "STOP",
      "MEDIA_READY",
      "PLAYBACK_STARTED",
      "INTERRUPTED",
      "HEARTBEAT",
      "FINISHED",
    ].sort(),
  );
  for (const { name, value } of positive) {
    assertValid(validate, value, name);
    for (const key of [
      "audio",
      "transcript",
      "finalTranscript",
      "spokenText",
      "userId",
      "ownerId",
      "bearer",
      "providerKey",
    ]) {
      assert.equal(
        validate({ ...value, [key]: "injected" }),
        false,
        `${name} outer ${key}`,
      );
      assert.equal(
        validate({
          ...value,
          payload: { ...value.payload, [key]: "injected" },
        }),
        false,
        `${name} payload ${key}`,
      );
    }
    for (const key of ["commandId", "voiceSessionId", "attemptId"]) {
      assert.equal(
        validate({ ...value, [key]: "not-a-uuid" }),
        false,
        `${name} ${key}`,
      );
    }
    assert.equal(
      validate({ ...value, sequence: 0 }),
      false,
      `${name} zero sequence`,
    );
    assert.equal(
      validate({ ...value, schemaVersion: 2 }),
      false,
      `${name} future schema`,
    );
  }
  const heartbeat = positive.find(
    (item) => item.value.kind === "HEARTBEAT",
  ).value;
  for (const state of states) {
    assert.equal(
      validate({
        ...heartbeat,
        payload: { ...heartbeat.payload, observedState: state },
      }),
      ["CONNECTING", "ACTIVE", "RECONNECTING", "ENDING"].includes(state),
      state,
    );
  }
});

test("public owner commands cannot select actor, package, models, history or provider permissions", () => {
  const validate = component("EmptyCommand");
  assertValid(validate, {}, "Empty command");
  for (const key of [
    "userId",
    "ownerId",
    "packageCode",
    "provider",
    "model",
    "recordAudio",
    "transcript",
    "journalId",
    "conversationId",
    "agentName",
    "roomName",
  ]) {
    assert.equal(validate({ [key]: "injected" }), false, key);
  }
});

test("model turns accept only bounded final Vietnamese text and current opaque worker scopes", () => {
  const validate = component("VoiceTurn");
  const value = positiveExample("VoiceTurn");
  assertValid(validate, value, "Final Vietnamese turn");
  for (const text of [
    "",
    " \n\t ",
    "a".repeat(policy.limits.maxFinalTranscriptCharacters + 1),
  ]) {
    assert.equal(
      validate({ ...value, finalTranscript: text }),
      false,
      "Invalid final transcript",
    );
  }
  for (const field of ["attemptId", "leaseId", "turnId"]) {
    assert.equal(validate({ ...value, [field]: "invalid-uuid" }), false, field);
  }
  assert.equal(validate({ ...value, isFinal: false }), false);
  assert.equal(validate({ ...value, language: "en-US" }), false);
  assert.equal(validate({ ...value, sequence: 0 }), false);
  for (const field of [
    "ownerId",
    "provider",
    "model",
    "quota",
    "history",
    "bearer",
    "audio",
  ]) {
    assert.equal(validate({ ...value, [field]: "injected" }), false, field);
  }
});

test("public and internal operations require distinct owner-specific authentication", () => {
  for (const [route, item] of Object.entries(api.paths)) {
    for (const method of ["get", "post"]) {
      const operation = item[method];
      if (!operation) continue;
      const scheme = route.startsWith("/api/")
        ? "UserBearer"
        : route.endsWith("/voice-consent-authorizations")
          ? "JournalVoiceCareToken"
          : route.endsWith("/voice-entitlement-authorizations")
            ? "JournalVoiceEntitlementToken"
            : "VoiceWorkerToken";
      assert.deepEqual(
        operation.security,
        [{ [scheme]: [] }],
        `${method} ${route}`,
      );
      for (const response of Object.values(operation.responses)) {
        assert.equal(
          response.headers["Cache-Control"].schema.const,
          "no-store",
          `${method} ${route}`,
        );
      }
    }
  }
  const headers = [
    "VoiceWorkerToken",
    "JournalVoiceCareToken",
    "JournalVoiceEntitlementToken",
  ].map((name) => api.components.securitySchemes[name].name);
  assert.equal(new Set(headers).size, 3);
  for (const name of [
    "VoiceWorkerToken",
    "JournalVoiceCareToken",
    "JournalVoiceEntitlementToken",
  ]) {
    assert.equal(api.components.securitySchemes[name].type, "apiKey");
    assert.equal(api.components.securitySchemes[name].in, "header");
  }
});

test("retryable owner commands and model turns require UUID idempotency keys", () => {
  for (const route of [
    "/api/v1/ai-companion/voice-sessions",
    "/api/v1/ai-companion/voice-sessions/{voiceSessionId}/connection",
    "/api/v1/ai-companion/voice-sessions/{voiceSessionId}/end",
    "/api/v1/consents/ai-voice-processing",
    "/internal/v1/ai-companion/voice-sessions/{voiceSessionId}/turns",
  ]) {
    const parameter = api.paths[route].post.parameters.find(
      (item) => item.in === "header" && item.name === "Idempotency-Key",
    );
    assert.ok(parameter, route);
    assert.equal(parameter.required, true, route);
    const validate = compile(parameter.schema);
    assertValid(validate, "11111111-1111-4111-8111-111111111111", route);
    assert.equal(validate("not-an-idempotency-uuid"), false, route);
  }
});

test("worker reporting endpoint rejects owner-to-worker dispatch, stop and lease commands", () => {
  const operation =
    api.paths[
      "/internal/v1/ai-companion/voice-sessions/{voiceSessionId}/agent-control"
    ].post;
  const validate = compile(
    operation.requestBody.content["application/json"].schema,
  );
  for (const { name, value } of examples.workerExamples.filter(
    (item) => item.valid,
  )) {
    assert.equal(
      validate(value),
      [
        "MEDIA_READY",
        "PLAYBACK_STARTED",
        "INTERRUPTED",
        "HEARTBEAT",
        "FINISHED",
      ].includes(value.kind),
      name,
    );
  }
});

test("connection tokens are output-only and require exact audio-only room access", () => {
  const properties = api.components.schemas.Connection.properties;
  assert.equal(properties.participantToken.readOnly, true);
  assert.notEqual(properties.participantToken.writeOnly, true);
  assert.equal(properties.audioOnly.const, true);
  assert.equal(properties.transport.const, "LIVEKIT_CLOUD");
  assert.equal(properties.serverUrl.pattern, "^wss://");
});

test("Care cannot report successful voice authorization with either required consent absent", () => {
  const validate = component("DualConsentDecision");
  const value = {
    ownerId: "11111111-1111-4111-8111-111111111111",
    voiceSessionId: "22222222-2222-4222-8222-222222222222",
    purpose: "AI_COMPANION_VOICE",
    aiProcessingGranted: true,
    voiceProcessingGranted: true,
    voiceConsentVersion: policy.privacy.voiceConsentVersion,
    authorized: true,
    checkedAt: "2026-10-23T09:00:00Z",
  };
  assertValid(validate, value, "Dual consent granted");
  assert.equal(validate({ ...value, aiProcessingGranted: false }), false);
  assert.equal(validate({ ...value, voiceProcessingGranted: false }), false);
  assert.equal(
    validate({ ...value, voiceConsentVersion: "old-disclosure" }),
    false,
  );
});

test("acceptance scenarios are distinct pending runtime obligations, not concurrency or persistence evidence", () => {
  const ids = examples.acceptanceScenarios.map((scenario) => scenario.id);
  assert.equal(new Set(ids).size, ids.length);
  for (const required of [
    "TEXT_CONSENT_ONLY",
    "VOICE_CONSENT_ONLY",
    "THIRD_GLOBAL_CALL",
    "SECOND_OWNER_CALL",
    "EXACT_START_RETRY",
    "MIDNIGHT_RECONNECT",
    "PROVIDER_FAILED_BEFORE_REPLY",
    "FAILURE_AFTER_REPLY",
    "CONSENT_WITHDRAWN",
    "LEASE_NOT_RENEWED",
  ]) {
    assert.ok(ids.includes(required), required);
  }
  for (const scenario of examples.acceptanceScenarios) {
    assert.equal(
      scenario.proofStatus,
      "RUNTIME_ACCEPTANCE_PENDING_DOWNSTREAM_STORY",
      scenario.id,
    );
    assert.ok(
      scenario.expected.length > 0 && scenario.invariant.length > 0,
      scenario.id,
    );
  }
});
