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
