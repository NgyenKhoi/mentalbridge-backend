import assert from "node:assert/strict";
import test from "node:test";
import { loadConfiguration } from "../configuration/configuration.js";
import { selectCompanionProviderRoute } from "../companion-chat/companion-chat.js";
import {
  createVoiceProvider,
  selectVoiceProviderRoute,
} from "./voice-provider.js";

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

void test("Voice friend tone defaults off and accepts only its explicit boolean flag", () => {
  assert.equal(loadConfiguration(identity).VOICE_FRIEND_ENABLED, false);
  const configuration = loadConfiguration({
    ...identity,
    JOURNAL_AI_VOICE_FRIEND_ENABLED: "true",
  });
  assert.equal(configuration.VOICE_FRIEND_ENABLED, true);
  assert.equal(configuration.COMPANION_GUIDANCE_V2_ENABLED, false);
  assert.equal(configuration.VOICE_MEDIA?.enabled, false);
  assert.throws(() =>
    loadConfiguration({
      ...identity,
      JOURNAL_AI_VOICE_FRIEND_ENABLED: "yes",
    }),
  );
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
      promptVersion: "companion-chat-v1",
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
    calls++;
    const endpoint = url instanceof Request ? new URL(url.url) : new URL(url);
    assert.equal(
      endpoint.origin,
      "https://bedrock-runtime.ap-southeast-1.amazonaws.com",
    );
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
    assert.equal(body.inferenceConfig?.maxTokens, 300);
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
      const supportive = replyStyle === "SUPPORTIVE" && voice;
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
          assert.equal(body.inferenceConfig?.maxTokens, 300);
          assert.equal(Object.hasOwn(body, "outputConfig"), false);
          assert.match(system, /"maxLength":500/);
        } else {
          assert.equal(body.inferenceConfig?.maxTokens, 2000);
          assert.ok(Object.hasOwn(body, "outputConfig"));
          assert.doesNotMatch(
            system,
            /For this pilot|spoken Vietnamese conversation/,
          );
        }
        if (supportive) {
          assert.match(system, /2–3 natural Vietnamese sentences/);
          assert.match(system, /180–280 characters/);
          assert.match(system, /Reflect what the user actually shared/);
          assert.match(system, /at most one open question/);
          assert.match(
            system,
            /Never fabricate feelings, events, causes or personal facts/,
          );
          assert.match(system, /prioritize a short clarification over length/);
          assert.doesNotMatch(
            system,
            /one short Vietnamese sentence|brief spoken/,
          );
        } else if (voice) {
          assert.match(
            system,
            /one short Vietnamese sentence of at most 160 characters/,
          );
          assert.doesNotMatch(system, /2–3 natural Vietnamese sentences/);
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
      ["ắ".repeat(501), false],
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
