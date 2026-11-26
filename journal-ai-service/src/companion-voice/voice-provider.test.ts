import assert from "node:assert/strict";
import test from "node:test";
import { spawnSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import SwaggerParser from "@apidevtools/swagger-parser";
import { z } from "zod";
import { loadConfiguration } from "../configuration/configuration.js";
import { selectCompanionProviderRoute } from "../companion-chat/companion-chat.js";
import {
  createVoiceProvider,
  selectVoiceProviderRoute,
} from "./voice-provider.js";
import {
  journalVoiceMeasurementSnapshot,
  runJournalVoiceMeasurementSnapshot,
} from "./voice-measurement-preflight.js";
import { effectiveBedrockRequestLimits } from "../llm-providers/bedrock-request-limits.js";

const identity = {
  NODE_ENV: "test",
  IDENTITY_JWT_ISSUER: "https://identity.test",
  IDENTITY_JWT_AUDIENCE: "mentalbridge-api",
  IDENTITY_JWT_KEY_ID: "test",
  IDENTITY_JWT_PUBLIC_KEY: "synthetic-public-key",
};
const approved = {
  ...identity,
  JOURNAL_AI_PROVIDER_MODE: "DETERMINISTIC_FAKE",
  JOURNAL_AI_FREE_PLUS_PROVIDER: "GEMINI",
  JOURNAL_AI_FREE_PLUS_MODEL: "gemini-3.8-flash",
  JOURNAL_AI_VOICE_PROVIDER_MODE: "APPROVED_BEDROCK",
  JOURNAL_AI_VOICE_PROVIDER_APPROVAL_VERSION: "mb762-po-20261029-sonnet",
  AWS_BEARER_TOKEN_BEDROCK: "synthetic-bedrock-credential",
};

const pilotWindow = {
  startsAt: "2026-11-16T00:00:00.000Z",
  endsAt: "2026-11-16T00:30:00.000Z",
  ownerIds: ["00000000-0000-4000-8000-000000000001"],
};
void test("independent speech contract and its configuration gate are reviewable and fail closed", async () => {
  const contract = await SwaggerParser.validate(
    fileURLToPath(
      new URL(
        "../../../contracts/openapi/journal-ai-voice-speech-units-v1.json",
        import.meta.url,
      ),
    ),
  );
  assert.deepEqual(contract.security, [{ VoiceWorkerToken: [] }]);
  const paths = z
    .record(
      z.string(),
      z
        .object({
          "x-mentalbridge-status": z.literal("implemented"),
          post: z
            .object({ "x-mentalbridge-status": z.literal("implemented") })
            .loose(),
        })
        .loose(),
    )
    .parse(contract.paths);
  for (const route of Object.values(paths)) {
    assert.equal(route["x-mentalbridge-status"], "implemented");
    assert.equal(route.post["x-mentalbridge-status"], "implemented");
  }
  assert.ok(
    loadConfiguration({
      ...approved,
      JOURNAL_AI_VOICE_PILOT: JSON.stringify(pilotWindow),
    }).VOICE_PILOT,
  );
  assert.ok(
    loadConfiguration({
      ...approved,
      JOURNAL_AI_VOICE_PILOT: JSON.stringify({
        ...pilotWindow,
        measurement: {
          campaignId: "00000000-0000-4000-8000-000000000009",
          maxSessions: 2,
          maxConcurrentSessions: 1,
          maxTurns: 12,
          maxVoiceSeconds: 480,
          maxTtsCharacters: 4000,
          voiceId: "8e8f222d-c817-4cc5-822b-8bf76ca7e98d",
        },
      }),
    }).VOICE_PILOT?.measurement,
  );
  for (const value of [
    {
      ...pilotWindow,
      ownerIds: [pilotWindow.ownerIds[0], pilotWindow.ownerIds[0]],
    },
    { ...pilotWindow, endsAt: "2026-11-17T00:00:01.000Z" },
    { ...pilotWindow, endsAt: pilotWindow.startsAt },
  ])
    assert.throws(() =>
      loadConfiguration({
        ...approved,
        JOURNAL_AI_VOICE_PILOT: JSON.stringify(value),
      }),
    );
});
void test("measurement snapshot reports effective fixed allocations instead of the larger pilot values", () => {
  const config = loadConfiguration({
    ...approved,
    JOURNAL_AI_VOICE_PILOT: JSON.stringify({
      ...pilotWindow,
      maxSessionSeconds: 600,
      maxTurns: 30,
      measurement: {
        campaignId: "00000000-0000-4000-8000-000000000003",
        maxSessions: 2,
        maxConcurrentSessions: 1,
        maxTurns: 12,
        maxVoiceSeconds: 480,
        maxTtsCharacters: 4000,
        voiceId: "8e8f222d-c817-4cc5-822b-8bf76ca7e98d",
      },
    }),
  });
  const snapshot = journalVoiceMeasurementSnapshot(config);
  assert.equal(snapshot.pilot?.maxSessionSeconds, 240);
  assert.equal(snapshot.pilot.maxTurnsPerSession, 6);
  assert.equal(snapshot.outputTokenLimit, 600);
});

void test("Journal measurement snapshots report actual default, Friend and pilot limits without normalizing them", () => {
  const now = new Date("2026-11-16T00:10:00.000Z");
  const configuration = loadConfiguration(approved);
  assert.deepEqual(journalVoiceMeasurementSnapshot(configuration, now), {
    schemaVersion: 1,
    component: "JOURNAL",
    capturedAt: now.toISOString(),
    voiceEnabled: false,
    providerEnabled: true,
    inputGuardEnabled: true,
    inputTokenLimit: 1900,
    outputTokenLimit: 2000,
    pilot: null,
    concurrencyLimit: 2,
  });
  const pilot = loadConfiguration({
    ...approved,
    JOURNAL_AI_VOICE_PILOT: JSON.stringify(pilotWindow),
  });
  assert.deepEqual(journalVoiceMeasurementSnapshot(pilot, now), {
    schemaVersion: 1,
    component: "JOURNAL",
    capturedAt: now.toISOString(),
    voiceEnabled: false,
    providerEnabled: true,
    inputGuardEnabled: true,
    inputTokenLimit: 1900,
    outputTokenLimit: 600,
    pilot: {
      startsAt: pilotWindow.startsAt,
      endsAt: pilotWindow.endsAt,
      ownerCount: 1,
      maxSessionSeconds: 90,
      maxTurnsPerSession: 4,
    },
    concurrencyLimit: 2,
  });
  assert.equal(
    journalVoiceMeasurementSnapshot(loadConfiguration(identity), now)
      .providerEnabled,
    false,
  );
});

void test("effective Bedrock limits preserve Text, pilot overrides and scoped request counting", () => {
  for (const maxOutputTokens of [undefined, 300, 450, 600]) {
    const configuration = loadConfiguration({
      ...approved,
      JOURNAL_AI_VOICE_PILOT: JSON.stringify({
        ...pilotWindow,
        maxOutputTokens,
      }),
    });
    assert.deepEqual(effectiveBedrockRequestLimits(configuration, false), {
      inputGuardEnabled: false,
      inputTokenLimit: null,
      outputTokenLimit: 2000,
    });
    assert.deepEqual(effectiveBedrockRequestLimits(configuration, true), {
      inputGuardEnabled: true,
      inputTokenLimit: 1900,
      outputTokenLimit: maxOutputTokens ?? 600,
    });
    assert.equal(
      journalVoiceMeasurementSnapshot(configuration).outputTokenLimit,
      maxOutputTokens ?? 600,
    );
  }
});

void test("measurement snapshot is allowlisted and makes no provider or activation call", () => {
  const original = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = () => {
    calls++;
    throw new Error("UNEXPECTED_NETWORK_REQUEST");
  };
  try {
    const configuration = loadConfiguration({
      ...approved,
      AWS_BEARER_TOKEN_BEDROCK: "private-canary-bedrock-credential",
      JOURNAL_AI_CARE_BASE_URL: "https://private-canary.invalid",
      JOURNAL_AI_VOICE_PILOT: JSON.stringify({
        ...pilotWindow,
        ownerIds: [
          ...pilotWindow.ownerIds,
          "00000000-0000-4000-8000-000000000002",
        ],
        maxSessionSeconds: 240,
        maxTurns: 6,
      }),
    });
    const snapshot = journalVoiceMeasurementSnapshot(configuration);
    assert.ok(snapshot.pilot);
    assert.ok(configuration.VOICE_PILOT);
    assert.equal(snapshot.pilot.ownerCount, 2);
    assert.equal(snapshot.pilot.maxSessionSeconds, 240);
    assert.equal(snapshot.pilot.maxTurnsPerSession, 6);
    const serialized = JSON.stringify(snapshot);
    for (const privateValue of [
      "private-canary",
      ...configuration.VOICE_PILOT.ownerIds,
      "apiKey",
      "serverUrl",
      "approvedVersion",
      "LIVE_APPROVED",
    ])
      assert.equal(serialized.includes(privateValue), false);
    assert.equal(calls, 0);
    assert.equal(configuration.VOICE_MEDIA?.enabled, false);
  } finally {
    globalThis.fetch = original;
  }
});

void test("snapshot command rejects non-production before loading dotenv and redacts invalid production configuration", () => {
  const output: object[] = [];
  for (const environment of [
    {},
    { NODE_ENV: "test" },
    { NODE_ENV: "development" },
  ]) {
    assert.equal(
      runJournalVoiceMeasurementSnapshot(environment, (value) =>
        output.push(value),
      ),
      1,
    );
  }
  assert.deepEqual(output, [
    { code: "PRODUCTION_ENV_REQUIRED" },
    { code: "PRODUCTION_ENV_REQUIRED" },
    { code: "PRODUCTION_ENV_REQUIRED" },
  ]);
  const invalidOutput: object[] = [];
  assert.equal(
    runJournalVoiceMeasurementSnapshot(
      {
        NODE_ENV: "production",
        JOURNAL_AI_VOICE_PILOT: "private-canary-invalid-json",
        AWS_BEARER_TOKEN_BEDROCK: "private-canary-credential",
      },
      (value) => invalidOutput.push(value),
    ),
    1,
  );
  assert.deepEqual(invalidOutput, [{ code: "CONFIGURATION_INVALID" }]);
  assert.doesNotMatch(JSON.stringify(invalidOutput), /private-canary/);
});

void test("snapshot command reads valid production configuration without starting Voice or contacting dependencies", () => {
  const original = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = () => {
    calls++;
    throw new Error("UNEXPECTED_NETWORK_REQUEST");
  };
  try {
    const output: object[] = [];
    assert.equal(
      runJournalVoiceMeasurementSnapshot(
        {
          ...approved,
          NODE_ENV: "production",
          JOURNAL_AI_MONGODB_URI: "mongodb://private-canary.invalid:27017",
          JOURNAL_AI_MONGODB_DATABASE: "synthetic_mb861",
          JOURNAL_AI_ENCRYPTION_KEY: Buffer.alloc(32, 1).toString("base64"),
          JOURNAL_AI_IDEMPOTENCY_HMAC_KEY: Buffer.alloc(32, 2).toString(
            "base64",
          ),
          JOURNAL_AI_CARE_BASE_URL: "https://private-canary-care.invalid",
          JOURNAL_AI_CONTENT_BASE_URL: "https://private-canary-content.invalid",
          JOURNAL_AI_CONSULTATION_BASE_URL:
            "https://private-canary-consultation.invalid",
          JOURNAL_AI_VOICE_ENABLED: "true",
          JOURNAL_AI_VOICE_APPROVAL_VERSION: "mb861-synthetic-config-only",
          JOURNAL_AI_VOICE_CARE_SERVICE_TOKEN: "synthetic-care-".padEnd(
            40,
            "a",
          ),
          JOURNAL_AI_VOICE_ENTITLEMENT_SERVICE_TOKEN:
            "synthetic-entitlement-".padEnd(40, "b"),
          JOURNAL_AI_VOICE_WORKER_SERVICE_TOKEN: "synthetic-worker-".padEnd(
            40,
            "c",
          ),
          JOURNAL_AI_VOICE_LIVEKIT_URL: "wss://synthetic.livekit.cloud",
          JOURNAL_AI_VOICE_LIVEKIT_API_KEY: "synthetic-livekit-key",
          JOURNAL_AI_VOICE_LIVEKIT_API_SECRET:
            "synthetic-livekit-secret-".padEnd(40, "d"),
        },
        (value) => output.push(value),
      ),
      0,
    );
    assert.equal(output.length, 1);
    const snapshot = output[0] as ReturnType<
      typeof journalVoiceMeasurementSnapshot
    >;
    assert.equal(snapshot.voiceEnabled, true);
    assert.equal(snapshot.providerEnabled, true);
    assert.equal(snapshot.inputGuardEnabled, true);
    assert.equal(snapshot.outputTokenLimit, 2000);
    assert.equal(snapshot.pilot, null);
    assert.equal(calls, 0);
    assert.doesNotMatch(JSON.stringify(output), /private-canary|synthetic-/);
  } finally {
    globalThis.fetch = original;
  }
});

void test("standalone snapshot CLI refuses dotenv-based activation without reading the local file", () => {
  const directory = mkdtempSync(join(tmpdir(), "mb861-journal-preflight-"));
  try {
    writeFileSync(
      join(directory, ".env"),
      "NODE_ENV=production\nJOURNAL_AI_VOICE_ENABLED=true\nAWS_BEARER_TOKEN_BEDROCK=private-canary\n",
    );
    const cli = fileURLToPath(
      new URL("./voice-measurement-preflight.js", import.meta.url),
    );
    const result = spawnSync(process.execPath, [cli], {
      cwd: directory,
      env: { NODE_ENV: "development" },
      encoding: "utf8",
      timeout: 10000,
      windowsHide: true,
    });
    assert.equal(result.status, 1);
    assert.deepEqual(JSON.parse(result.stdout), {
      code: "PRODUCTION_ENV_REQUIRED",
    });
    assert.equal(result.stderr, "");
    assert.doesNotMatch(result.stdout, /private-canary/);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

void test("Voice pins Sonnet independently of fake/Gemini Text while admission remains disabled", () => {
  const configuration = loadConfiguration(approved);
  assert.equal(configuration.VOICE_MEDIA?.enabled, false);
  assert.equal(configuration.PROVIDER_MODE, "DETERMINISTIC_FAKE");
  for (const plan of ["PLUS", "PREMIUM"] as const) {
    assert.equal(
      selectCompanionProviderRoute(configuration, plan).provider,
      "DETERMINISTIC_FAKE",
    );
    assert.deepEqual(selectVoiceProviderRoute(configuration, plan), {
      provider: "BEDROCK",
      model: "global.anthropic.claude-sonnet-4-6",
      providerApprovalVersion: "mb762-po-20261029-sonnet",
      routingPolicyVersion: "companion-voice-sonnet-v1",
      promptVersion: "companion-voice-friend-v3",
    });
  }
});

void test("Voice config rejects missing approval/credential, other models and other regions", () => {
  for (const override of [
    { AWS_BEARER_TOKEN_BEDROCK: "" },
    { JOURNAL_AI_VOICE_PROVIDER_APPROVAL_VERSION: "" },
    { JOURNAL_AI_VOICE_BEDROCK_MODEL: "global.anthropic.claude-opus-4-6-v1" },
    { JOURNAL_AI_VOICE_BEDROCK_MODEL: "gemini-3.8-flash" },
    { JOURNAL_AI_VOICE_BEDROCK_REGION: "us-east-1" },
  ])
    assert.throws(() => loadConfiguration({ ...approved, ...override }));
});

void test("Voice pilot environment rejects unsupported reply styles", () => {
  for (const replyStyle of ["VERBOSE", "supportive", true])
    assert.throws(() =>
      loadConfiguration({
        ...approved,
        JOURNAL_AI_VOICE_PILOT: JSON.stringify({
          startsAt: "2026-11-01T00:00:00.000Z",
          endsAt: "2026-11-01T01:00:00.000Z",
          ownerIds: ["00000000-0000-4000-8000-000000000001"],
          replyStyle,
        }),
      }),
    );
});

void test("Voice pilot output ceiling requires an explicit bounded integer", () => {
  const pilot = {
    startsAt: "2026-11-01T00:00:00.000Z",
    endsAt: "2026-11-01T01:00:00.000Z",
    ownerIds: ["00000000-0000-4000-8000-000000000001"],
  };
  const load = (value: unknown) =>
    loadConfiguration({
      ...approved,
      JOURNAL_AI_VOICE_PILOT: JSON.stringify(value),
    });
  assert.equal(load(pilot).VOICE_PILOT?.maxOutputTokens, undefined);
  for (const maxOutputTokens of [300, 450, 600])
    assert.equal(
      load({ ...pilot, maxOutputTokens }).VOICE_PILOT?.maxOutputTokens,
      maxOutputTokens,
    );
  for (const maxOutputTokens of [299, 601, 450.5, "600", true, null])
    assert.throws(() => load({ ...pilot, maxOutputTokens }));
});

void test("Voice transport targets the actual Singapore Sonnet Converse endpoint with existing bearer auth", async () => {
  const original = globalThis.fetch;
  const configuration = loadConfiguration({
    ...approved,
    JOURNAL_AI_BEDROCK_REGION: "us-east-1",
  });
  let calls = 0;
  globalThis.fetch = (url, init) => {
    const endpoint = url instanceof Request ? new URL(url.url) : new URL(url);
    assert.equal(
      endpoint.origin,
      "https://bedrock-runtime.ap-southeast-1.amazonaws.com",
    );
    // MB-846d: every Voice request counts its exact input first (1,900-token cap).
    if (endpoint.pathname.endsWith("/count-tokens"))
      return Promise.resolve(Response.json({ inputTokens: 900 }));
    calls++;
    assert.equal(
      decodeURIComponent(endpoint.pathname),
      "/model/global.anthropic.claude-sonnet-4-6/converse",
    );
    assert.equal(
      new Headers(init?.headers).get("authorization"),
      "Bearer synthetic-bedrock-credential",
    );
    return Promise.resolve(
      Response.json({
        stopReason: "end_turn",
        output: {
          message: {
            content: [
              {
                text: JSON.stringify({
                  message: "Mình đang lắng nghe.",
                  language: "vi",
                  authority: "SUPPORT_ONLY",
                  clinicalAssessment: "NONE",
                  safetyDecision: "NONE",
                  eligibilityDecision: "NONE",
                  businessAction: "NONE",
                  thirdPartyAction: "NONE",
                }),
              },
            ],
          },
        },
        usage: { inputTokens: 100, outputTokens: 20 },
      }),
    );
  };
  try {
    await createVoiceProvider(configuration).reply(
      "Mình muốn tập nói chậm.",
      { kinds: [], prompt: "" },
      selectVoiceProviderRoute(configuration, "PLUS"),
      { voice: true },
    );
    assert.equal(calls, 1);
    assert.equal(configuration.BEDROCK_REGION, "us-east-1");
  } finally {
    globalThis.fetch = original;
  }
});

void test("A production Voice route never falls back to fake or the Text provider", () => {
  const configuration = loadConfiguration(identity);
  const media = configuration.VOICE_MEDIA;
  assert.ok(media);
  assert.throws(() =>
    selectVoiceProviderRoute(
      { ...configuration, NODE_ENV: "production" },
      "PLUS",
    ),
  );
  assert.throws(() =>
    selectVoiceProviderRoute(
      {
        ...configuration,
        VOICE_MEDIA: { ...media, enabled: true },
      },
      "PREMIUM",
    ),
  );
});

void test("pilot counts the exact prompt before Converse, caps output and never invokes after count denial", async () => {
  const original = globalThis.fetch;
  const config = loadConfiguration({
    ...approved,
    JOURNAL_AI_VOICE_PILOT: JSON.stringify({
      startsAt: "2026-10-29T00:00:00.000Z",
      endsAt: "2026-10-30T00:00:00.000Z",
      ownerIds: ["00000000-0000-4000-8000-000000000001"],
    }),
  });
  let countSystem = "";
  let invocations = 0;
  let deny = false;
  globalThis.fetch = (url, options) => {
    const endpoint = url instanceof Request ? new URL(url.url) : new URL(url);
    assert.ok(typeof options?.body === "string");
    const body = JSON.parse(options.body) as {
      system: string | { text: string }[];
      inferenceConfig?: { maxTokens: number };
      input?: {
        converse: {
          system: { text: string }[];
          messages: { role: string; content: { text: string }[] }[];
        };
      };
    };
    if (endpoint.pathname.endsWith("/count-tokens")) {
      assert.equal(
        endpoint.origin,
        "https://bedrock-runtime.ap-southeast-1.amazonaws.com",
      );
      assert.equal(
        endpoint.pathname,
        "/model/anthropic.claude-sonnet-4-6/count-tokens",
      );
      assert.ok(body.input?.converse.system[0]?.text);
      countSystem = body.input.converse.system[0].text;
      return Promise.resolve(Response.json({ inputTokens: deny ? 1901 : 800 }));
    }
    assert.equal(
      endpoint.pathname,
      "/model/global.anthropic.claude-sonnet-4-6/converse",
    );
    invocations++;
    assert.ok(Array.isArray(body.system));
    assert.equal(body.system[0]?.text, countSystem);
    assert.equal(body.inferenceConfig?.maxTokens, 600);
    assert.equal(Object.hasOwn(body, "outputConfig"), false);
    assert.match(countSystem, /Required JSON schema/);
    return Promise.resolve(new Response(null, { status: 503 }));
  };
  try {
    const provider = createVoiceProvider(config);
    const route = selectVoiceProviderRoute(config, "PLUS");
    await assert.rejects(
      provider.reply("synthetic", { kinds: [], prompt: "" }, route, {
        voice: true,
      }),
    );
    assert.equal(invocations, 1);
    deny = true;
    await assert.rejects(
      provider.reply("synthetic", { kinds: [], prompt: "" }, route, {
        voice: true,
      }),
    );
    assert.equal(invocations, 1);
  } finally {
    globalThis.fetch = original;
  }
});

void test("supportive pilot counts its exact grounded spoken prompt and accepts a safe longer reply without changing Text or legacy brevity", async () => {
  const original = globalThis.fetch;
  try {
    for (const [replyStyle, voice] of [
      [undefined, true],
      ["BRIEF", true],
      ["SUPPORTIVE", true],
      ["SUPPORTIVE", false],
    ] as const) {
      const config = loadConfiguration({
        ...approved,
        JOURNAL_AI_VOICE_PILOT: JSON.stringify({
          startsAt: "2026-11-01T00:00:00.000Z",
          endsAt: "2026-11-01T01:00:00.000Z",
          ownerIds: ["00000000-0000-4000-8000-000000000001"],
          replyStyle,
        }),
      });
      const supportive = replyStyle !== "BRIEF" && voice;
      const message = supportive
        ? "Mình nghe bạn chia sẻ rằng việc học đang khiến bạn mệt mỏi và muốn có người lắng nghe. Nếu thấy phù hợp, bạn có thể dành một chút thời gian nghỉ trước khi nhìn lại phần đang vướng. Điều gì trong chuyện này khiến bạn muốn chia sẻ nhất?"
        : "Mình đang lắng nghe bạn.";
      let countedSystem = "";
      let counts = 0;
      let converses = 0;
      globalThis.fetch = (url, options) => {
        const endpoint =
          url instanceof Request ? new URL(url.url) : new URL(url);
        assert.equal(
          endpoint.origin,
          "https://bedrock-runtime.ap-southeast-1.amazonaws.com",
        );
        assert.ok(typeof options?.body === "string");
        const body = JSON.parse(options.body) as {
          system?: { text: string }[];
          inferenceConfig?: { maxTokens: number };
          input?: { converse: { system: { text: string }[] } };
        };
        if (endpoint.pathname.endsWith("/count-tokens")) {
          counts++;
          assert.ok(body.input?.converse.system[0]?.text);
          countedSystem = body.input.converse.system[0].text;
          return Promise.resolve(Response.json({ inputTokens: 850 }));
        }
        converses++;
        assert.equal(
          endpoint.pathname,
          "/model/global.anthropic.claude-sonnet-4-6/converse",
        );
        const system = body.system?.[0]?.text;
        assert.ok(system);
        if (voice) {
          assert.equal(system, countedSystem);
          assert.equal(body.inferenceConfig?.maxTokens, 600);
          assert.equal(Object.hasOwn(body, "outputConfig"), false);
          assert.match(system, /"maxLength":800/);
        } else {
          assert.equal(body.inferenceConfig?.maxTokens, 2000);
          assert.ok(Object.hasOwn(body, "outputConfig"));
          assert.doesNotMatch(
            system,
            /For this pilot|spoken Vietnamese conversation/,
          );
        }
        if (supportive) {
          assert.match(system, /Length follows the moment/);
          assert.match(system, /400–650, never more than 700/);
          assert.match(system, /grounded only in what the user said/);
          assert.match(system, /Ask at most ONE question per reply/);
          assert.match(
            system,
            /never invent feelings, causes, events, personal facts/,
          );
          assert.match(system, /calls for a short clarification/);
          assert.doesNotMatch(system, /one short natural sentence/);
        } else if (voice) {
          assert.match(
            system,
            /one short natural sentence, at most 160 characters/,
          );
          assert.doesNotMatch(system, /Length follows the moment/);
        }
        return Promise.resolve(
          Response.json({
            stopReason: "end_turn",
            output: {
              message: {
                content: [
                  {
                    text: JSON.stringify({
                      message,
                      language: "vi",
                      authority: "SUPPORT_ONLY",
                      clinicalAssessment: "NONE",
                      safetyDecision: "NONE",
                      eligibilityDecision: "NONE",
                      businessAction: "NONE",
                      thirdPartyAction: "NONE",
                    }),
                  },
                ],
              },
            },
            usage: { inputTokens: 850, outputTokens: 100 },
          }),
        );
      };
      const response = await createVoiceProvider(config).reply(
        "Mình thấy mệt vì chuyện học và muốn chia sẻ.",
        { kinds: [], prompt: "" },
        selectVoiceProviderRoute(config, "PREMIUM"),
        { voice },
      );
      assert.equal(response.message, message);
      assert.equal(counts, voice ? 1 : 0);
      assert.equal(converses, 1);
      if (supportive) assert.ok(message.length >= 180 && message.length <= 280);
    }
  } finally {
    globalThis.fetch = original;
  }
});

void test("supportive pilot allows short clarification and preserves strict response safety", async () => {
  const original = globalThis.fetch;
  const config = loadConfiguration({
    ...approved,
    JOURNAL_AI_VOICE_PILOT: JSON.stringify({
      startsAt: "2026-11-01T00:00:00.000Z",
      endsAt: "2026-11-01T01:00:00.000Z",
      ownerIds: ["00000000-0000-4000-8000-000000000001"],
      replyStyle: "SUPPORTIVE",
    }),
  });
  try {
    for (const [message, accepted] of [
      ["Mình chưa nghe rõ, bạn có thể nói lại ý vừa rồi không?", true],
      ["Tôi chẩn đoán bạn đang mắc trầm cảm", false],
      ["ắ".repeat(800), true],
      ["ắ".repeat(801), false],
    ] as const) {
      let counts = 0;
      let converses = 0;
      globalThis.fetch = (url) => {
        const endpoint =
          url instanceof Request ? new URL(url.url) : new URL(url);
        if (endpoint.pathname.endsWith("/count-tokens")) {
          counts++;
          return Promise.resolve(Response.json({ inputTokens: 850 }));
        }
        converses++;
        return Promise.resolve(
          Response.json({
            stopReason: "end_turn",
            output: {
              message: {
                content: [
                  {
                    text: JSON.stringify({
                      message,
                      language: "vi",
                      authority: "SUPPORT_ONLY",
                      clinicalAssessment: "NONE",
                      safetyDecision: "NONE",
                      eligibilityDecision: "NONE",
                      businessAction: "NONE",
                      thirdPartyAction: "NONE",
                    }),
                  },
                ],
              },
            },
            usage: { inputTokens: 850, outputTokens: 100 },
          }),
        );
      };
      const result = createVoiceProvider(config).reply(
        "synthetic unclear speech",
        { kinds: [], prompt: "" },
        selectVoiceProviderRoute(config, "PREMIUM"),
        { voice: true },
      );
      if (accepted) assert.equal((await result).message, message);
      else await assert.rejects(result);
      assert.equal(counts, 1);
      assert.equal(converses, 1);
    }
  } finally {
    globalThis.fetch = original;
  }
});
