import assert from "node:assert/strict";
import test from "node:test";
import { randomUUID } from "node:crypto";
import {
  VoiceProviderFailure,
  type VoiceProviderObservation,
} from "../llm-providers/voice-diagnostics.js";
import {
  RoutedChatProvider,
  selectCompanionProviderRoute,
  validatedAssistantMessage,
  type ChatRoute,
} from "../companion-chat/companion-chat.js";
import { loadConfiguration } from "../configuration/configuration.js";

const configuration = loadConfiguration({
  NODE_ENV: "test",
  IDENTITY_JWT_ISSUER: "https://identity.test",
  IDENTITY_JWT_AUDIENCE: "mentalbridge-api",
  IDENTITY_JWT_KEY_ID: "test",
  IDENTITY_JWT_PUBLIC_KEY: "synthetic-public-key",
});
const route: ChatRoute = {
  workload: "COMPANION_CHAT",
  servicePlan: "PLUS",
  entitlementSource: "PAID",
  entitlementPolicyVersion: "service-entitlement-v1",
  entitlementVersion: 1,
  ...selectCompanionProviderRoute(configuration, "PLUS"),
  provider: "BEDROCK",
  model: "global.anthropic.claude-sonnet-4-6",
};

void test("pilot phases identify counting rejection before inference and preserve real usage on invalid Bedrock output", async () => {
  const original = globalThis.fetch;
  const pilot = {
    startsAt: new Date(),
    endsAt: new Date(Date.now() + 60000),
    ownerIds: [randomUUID()],
  };
  const provider = new RoutedChatProvider({
    ...configuration,
    BEDROCK_API_KEY: "PRIVATE_KEY",
    VOICE_PILOT: pilot,
  });
  try {
    for (const [response, code] of [
      [new Response("PRIVATE_BODY", { status: 403 }), "HTTP_4XX"],
      [new Response("PRIVATE_BODY", { status: 503 }), "HTTP_5XX"],
      [Response.json({ inputTokens: 1901 }), "INPUT_BUDGET_EXCEEDED"],
      [Response.json({ inputTokens: "PRIVATE_BODY" }), "INVALID_RESPONSE"],
    ] as const) {
      const observations: VoiceProviderObservation[] = [];
      let calls = 0;
      globalThis.fetch = () => {
        calls++;
        return Promise.resolve(response);
      };
      await assert.rejects(
        provider.reply(
          "PRIVATE_SPEECH",
          { kinds: [], prompt: "PRIVATE_CONTEXT" },
          route,
          { voice: true, voiceObserver: (value) => observations.push(value) },
        ),
        (error) => error instanceof VoiceProviderFailure && error.code === code,
      );
      assert.equal(calls, 1);
      assert.deepEqual(observations, [{ phase: "token_count_requested" }]);
    }
    for (const mode of ["http", "invalid-json", "invalid-output", "valid"]) {
      const observations: VoiceProviderObservation[] = [];
      let calls = 0;
      globalThis.fetch = () => {
        calls++;
        if (calls === 1)
          return Promise.resolve(Response.json({ inputTokens: 1500 }));
        return Promise.resolve(
          mode === "http"
            ? new Response("PRIVATE_BODY", { status: 429 })
            : mode === "invalid-json"
              ? new Response("PRIVATE_BODY")
              : Response.json({
                  output: {
                    message: {
                      content: [
                        {
                          text:
                            mode === "valid"
                              ? JSON.stringify({
                                  message: "Mình đang lắng nghe.",
                                  language: "vi",
                                  authority: "SUPPORT_ONLY",
                                  clinicalAssessment: "NONE",
                                  safetyDecision: "NONE",
                                  eligibilityDecision: "NONE",
                                  businessAction: "NONE",
                                  thirdPartyAction: "NONE",
                                })
                              : "PRIVATE_BAD_OUTPUT",
                        },
                      ],
                    },
                  },
                  usage: { inputTokens: 1500, outputTokens: 30 },
                }),
        );
      };
      const work = provider.reply(
        "PRIVATE_SPEECH",
        { kinds: [], prompt: "PRIVATE_CONTEXT" },
        route,
        { voice: true, voiceObserver: (value) => observations.push(value) },
      );
      if (mode === "valid") await work;
      else await assert.rejects(work);
      assert.equal(calls, 2);
      assert.deepEqual(observations.slice(0, 3), [
        { phase: "token_count_requested" },
        { phase: "token_count_authorized", countedInputTokens: 1500 },
        { phase: "bedrock_requested" },
      ]);
      assert.equal(
        observations.some((v) => v.phase === "bedrock_response_validated"),
        mode === "valid",
      );
      const usage = observations.find((v) => v.phase === "provider_usage");
      if (mode === "valid" || mode === "invalid-output")
        assert.deepEqual(usage, {
          phase: "provider_usage",
          inputTokens: 1500,
          outputTokens: 30,
          usageSource: "PROVIDER",
        });
      else assert.equal(usage, undefined);
      assert.equal(JSON.stringify(observations).includes("PRIVATE"), false);
    }
  } finally {
    globalThis.fetch = original;
  }
});

void test("voice reuses the Companion Bedrock schema/prompt/validator without tools or prompt cache", async () => {
  const original = globalThis.fetch;
  let body: Record<string, unknown> = {};
  globalThis.fetch = (_input, init) => {
    assert.equal(typeof init?.body, "string");
    body = JSON.parse(init?.body as string) as Record<string, unknown>;
    return Promise.resolve(
      Response.json({
        output: {
          message: {
            content: [
              {
                text: JSON.stringify({
                  message:
                    "Mình đang lắng nghe. Bạn có thể nói rõ hơn một chút không?",
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
        usage: { inputTokens: 120, outputTokens: 30 },
      }),
    );
  };
  try {
    const result = await new RoutedChatProvider({
      ...configuration,
      BEDROCK_API_KEY: "synthetic-bedrock-credential",
    }).reply(
      "Tôi nghe không rõ",
      { kinds: [], prompt: '[{"user":"Tôi đang lo"}]' },
      route,
      { voice: true },
    );
    assert.equal(result.inputTokens, 120);
    assert.equal(result.outputTokens, 30);
    assert.match(JSON.stringify(body.system), /MentalBridge AI Companion/);
    assert.match(JSON.stringify(body.system), /ask a short clarification/);
    assert.equal(Object.hasOwn(body, "toolConfig"), false);
    assert.equal(JSON.stringify(body).includes("cachePoint"), false);
    assert.equal(validatedAssistantMessage(result.message), result.message);
    assert.throws(() =>
      validatedAssistantMessage("Tôi chẩn đoán bạn đang mắc trầm cảm"),
    );
  } finally {
    globalThis.fetch = original;
  }
});

void test("worker cancellation reaches the existing Bedrock fetch and blocks late publication", async () => {
  const original = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = (_input, init) =>
    new Promise((_resolve, reject) => {
      calls++;
      init?.signal?.addEventListener(
        "abort",
        () => {
          reject(new Error("synthetic private provider payload"));
        },
        { once: true },
      );
    });
  try {
    const controller = new AbortController();
    const work = new RoutedChatProvider({
      ...configuration,
      BEDROCK_API_KEY: "synthetic",
    }).reply("Tôi muốn chia sẻ", { kinds: [], prompt: "" }, route, {
      voice: true,
      signal: controller.signal,
    });
    controller.abort();
    await assert.rejects(
      work,
      (error) => error instanceof Error && !error.message.includes("private"),
    );
    assert.equal(calls, 1);
    await assert.rejects(
      new RoutedChatProvider(configuration).reply(
        "Tôi muốn chia sẻ",
        { kinds: [], prompt: "" },
        route,
        { signal: controller.signal },
      ),
    );
    assert.equal(calls, 1);
  } finally {
    globalThis.fetch = original;
  }
});
