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

void test("Voice classifies stopped and invalid output without leaking content or retrying", async () => {
  const original = globalThis.fetch;
  const valid = {
    message:
      "Bạn có thể chọn một việc nhỏ vừa sức, rồi nghỉ một chút. Mình cùng xem điều nào phù hợp với bạn nhé.",
    language: "vi",
    authority: "SUPPORT_ONLY",
    clinicalAssessment: "NONE",
    safetyDecision: "NONE",
    eligibilityDecision: "NONE",
    businessAction: "NONE",
    thirdPartyAction: "NONE",
  };
  const cases = [
    { stopReason: "end_turn", text: JSON.stringify(valid), code: null },
    {
      stopReason: undefined,
      text: JSON.stringify(valid),
      code: "UNEXPECTED_STOP_REASON",
    },
    {
      stopReason: "stop_sequence",
      text: JSON.stringify(valid),
      code: "UNEXPECTED_STOP_REASON",
    },
    {
      stopReason: "max_tokens",
      text: '{"message":"PRIVATE_PARTIAL',
      code: "OUTPUT_TOKEN_LIMIT",
    },
    {
      stopReason: "max_tokens",
      text: JSON.stringify(valid),
      code: "OUTPUT_TOKEN_LIMIT",
    },
    {
      stopReason: "PRIVATE_STOP_REASON",
      text: JSON.stringify(valid),
      code: "UNEXPECTED_STOP_REASON",
    },
    {
      stopReason: "end_turn",
      text: "PRIVATE_INVALID_JSON",
      code: "OUTPUT_JSON_INVALID",
    },
    {
      stopReason: "end_turn",
      text: JSON.stringify({ ...valid, authority: "PRIVATE_AUTHORITY" }),
      code: "OUTPUT_SCHEMA_INVALID",
    },
    {
      stopReason: "end_turn",
      text: JSON.stringify({
        ...valid,
        message: "Tôi chẩn đoán bạn đang mắc trầm cảm",
      }),
      code: "OUTPUT_SAFETY_REJECTED",
    },
    {
      stopReason: "end_turn",
      text: undefined,
      code: "RESPONSE_ENVELOPE_INVALID",
    },
  ];
  try {
    for (const item of cases) {
      let calls = 0;
      const observations: VoiceProviderObservation[] = [];
      globalThis.fetch = () => {
        calls++;
        return Promise.resolve(
          Response.json({
            stopReason: item.stopReason,
            output: { message: { content: [{ text: item.text }] } },
            usage: {
              inputTokens: 1620,
              outputTokens: item.stopReason === "max_tokens" ? 300 : 144,
            },
          }),
        );
      };
      const work = new RoutedChatProvider({
        ...configuration,
        BEDROCK_API_KEY: "PRIVATE_KEY",
      }).reply(
        "PRIVATE_SPEECH",
        { kinds: [], prompt: "PRIVATE_CONTEXT" },
        route,
        { voice: true, voiceObserver: (value) => observations.push(value) },
      );
      if (item.code === null) assert.equal((await work).message, valid.message);
      else
        await assert.rejects(
          work,
          (error) =>
            error instanceof VoiceProviderFailure && error.code === item.code,
        );
      assert.equal(calls, 1);
      assert.equal(
        observations.filter((value) => value.phase === "provider_usage").length,
        1,
      );
      assert.equal(
        observations.some(
          (value) => value.phase === "bedrock_output_validation",
        ),
        true,
      );
      if (
        item.stopReason === undefined ||
        item.stopReason === "stop_sequence"
      ) {
        assert.deepEqual(
          observations.find(
            (value) => value.phase === "bedrock_output_validation",
          ),
          {
            phase: "bedrock_output_validation",
            stopReason:
              item.stopReason === undefined ? "MISSING" : "STOP_SEQUENCE",
          },
        );
      }
      assert.equal(
        observations.some(
          (value) => value.phase === "bedrock_response_validated",
        ),
        item.code === null,
      );
      assert.equal(JSON.stringify(observations).includes("PRIVATE"), false);
      assert.equal(JSON.stringify(observations).includes(valid.message), false);
    }
  } finally {
    globalThis.fetch = original;
  }
});

void test("Voice output allowance stays 300 unless an explicit bounded pilot opts into 600", async () => {
  const original = globalThis.fetch;
  try {
    for (const maxOutputTokens of [undefined, 600] as const) {
      let calls = 0;
      globalThis.fetch = (_input, init) => {
        calls++;
        if (calls === 1)
          return Promise.resolve(Response.json({ inputTokens: 1637 }));
        if (typeof init?.body !== "string")
          throw new Error("EXPECTED_STRING_BODY");
        const body = JSON.parse(init.body) as {
          inferenceConfig: { maxTokens: number };
        };
        assert.equal(body.inferenceConfig.maxTokens, maxOutputTokens ?? 300);
        assert.equal(
          Object.hasOwn(body.inferenceConfig, "stopSequences"),
          false,
        );
        return Promise.resolve(
          Response.json({
            stopReason: "end_turn",
            output: {
              message: {
                content: [
                  {
                    text: JSON.stringify({
                      message: "Mình đang lắng nghe bạn.",
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
            usage: {
              inputTokens: 1620,
              outputTokens: maxOutputTokens === 600 ? 400 : 200,
            },
          }),
        );
      };
      const result = await new RoutedChatProvider({
        ...configuration,
        BEDROCK_API_KEY: "PRIVATE_KEY",
        VOICE_PILOT: {
          startsAt: new Date(),
          endsAt: new Date(Date.now() + 60000),
          ownerIds: [randomUUID()],
          ...(maxOutputTokens === undefined ? {} : { maxOutputTokens }),
        },
      }).reply("PRIVATE_SPEECH", { kinds: [], prompt: "" }, route, {
        voice: true,
      });
      assert.equal(calls, 2);
      assert.equal(result.outputTokens, maxOutputTokens === 600 ? 400 : 200);
    }
  } finally {
    globalThis.fetch = original;
  }
});

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
                  stopReason: "end_turn",
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
        stopReason: "end_turn",
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

void test("voice decodes one fenced Bedrock JSON object without relaxing schema or message safety", async () => {
  const original = globalThis.fetch;
  const valid = JSON.stringify({
    message: "Xin chào, mình đang lắng nghe bạn.",
    language: "vi",
    authority: "SUPPORT_ONLY",
    clinicalAssessment: "NONE",
    safetyDecision: "NONE",
    eligibilityDecision: "NONE",
    businessAction: "NONE",
    thirdPartyAction: "NONE",
  });
  try {
    for (const [text, accepted] of [
      [valid, true],
      ["```json\n" + valid + "\n```", true],
      ["```\r\n" + valid + "\r\n```", true],
      ["Prose\n```json\n" + valid + "\n```", false],
      ["```json\n" + valid + "\n```\nProse", false],
      ["```json\n" + valid, false],
      ["```json\n" + valid + "{}\n```", false],
      ["```json\n" + valid.replace("SUPPORT_ONLY", "NONE") + "\n```", false],
      [
        "```json\n" +
          valid.replace('"language":"vi"', '"language":"en"') +
          "\n```",
        false,
      ],
      [
        "```json\n" +
          valid.replace(
            "Xin chào, mình đang lắng nghe bạn.",
            "Tôi chẩn đoán bạn đang mắc trầm cảm",
          ) +
          "\n```",
        false,
      ],
    ] as const) {
      let calls = 0;
      globalThis.fetch = () => {
        calls++;
        return Promise.resolve(
          Response.json({
            stopReason: "end_turn",
            output: { message: { content: [{ text }] } },
            usage: { inputTokens: 120, outputTokens: 30 },
          }),
        );
      };
      const result = new RoutedChatProvider({
        ...configuration,
        BEDROCK_API_KEY: "synthetic",
      }).reply("Xin chào", { kinds: [], prompt: "" }, route, { voice: true });
      if (accepted)
        assert.equal(
          (await result).message,
          "Xin chào, mình đang lắng nghe bạn.",
        );
      else await assert.rejects(result);
      assert.equal(calls, 1);
    }
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
