import assert from "node:assert/strict";
import test from "node:test";
import { loadConfiguration } from "../configuration/configuration.js";
import { RoutedChatProvider } from "../companion-chat/companion-chat.js";
import { companionGuidanceSystem } from "../companion-chat/companion-guidance-policy.js";
import { VOICE_FRIEND_GUIDANCE_VERSION } from "../companion-chat/companion-voice-guidance.js";
import {
  VoiceProviderFailure,
  type VoiceProviderObservation,
} from "../llm-providers/voice-diagnostics.js";
import { selectVoiceProviderRoute } from "./voice-provider.js";

const configuration = loadConfiguration({
  NODE_ENV: "test",
  IDENTITY_JWT_ISSUER: "https://identity.test",
  IDENTITY_JWT_AUDIENCE: "mentalbridge-api",
  IDENTITY_JWT_KEY_ID: "test",
  IDENTITY_JWT_PUBLIC_KEY: "synthetic-public-key",
  JOURNAL_AI_VOICE_PROVIDER_MODE: "APPROVED_BEDROCK",
  JOURNAL_AI_VOICE_PROVIDER_APPROVAL_VERSION: "synthetic-voice-approved-v1",
  AWS_BEARER_TOKEN_BEDROCK: "PRIVATE_KEY",
});
const route = {
  provider: "BEDROCK",
  model: "global.anthropic.claude-sonnet-4-6",
} as const;
const output = (message = "Mình ở đây nghe bạn chia sẻ nè.") => ({
  message,
  language: "vi",
  authority: "SUPPORT_ONLY",
  clinicalAssessment: "NONE",
  safetyDecision: "NONE",
  eligibilityDecision: "NONE",
  businessAction: "NONE",
  thirdPartyAction: "NONE",
});
const response = (value = output()) =>
  Response.json({
    stopReason: "end_turn",
    output: { message: { content: [{ text: JSON.stringify(value) }] } },
    usage: { inputTokens: 1200, outputTokens: 180 },
  });

void test("friend Voice is compact, speech-only and separate from every Text/legacy variant", () => {
  for (const textHandoff of [false, true])
    for (const replyStyle of [undefined, "BRIEF", "SUPPORTIVE"] as const) {
      const delivery = {
        channel: "VOICE" as const,
        textHandoff,
        ...(replyStyle ? { replyStyle } : {}),
      };
      const legacy = companionGuidanceSystem(delivery);
      assert.equal(
        companionGuidanceSystem({ ...delivery, voiceFriend: false }),
        legacy,
      );
      const friend = companionGuidanceSystem({
        ...delivery,
        voiceFriend: true,
      });
      assert.match(friend, /Comfort first/u);
      assert.match(friend, /only when asked for advice/u);
      assert.match(friend, /Most replies end with a statement/u);
      assert.match(friend, /at most 500 characters/u);
      assert.match(friend, /No suggestion field/u);
      assert.match(friend, /Cần trợ giúp ngay/u);
      assert.match(friend, /Never diagnose/u);
      assert.match(friend, /No humour, playful particles/u);
      assert.match(friend, /no emoji, markup/u);
      assert.equal(
        Buffer.byteLength(friend, "utf8") <= Buffer.byteLength(legacy, "utf8"),
        true,
      );
      assert.equal(friend.includes("selectedText"), textHandoff);
    }
  for (const friend of [false, true])
    assert.equal(
      companionGuidanceSystem({ channel: "TEXT", friend, voiceFriend: true }),
      companionGuidanceSystem({ channel: "TEXT", friend }),
    );
  assert.equal(
    selectVoiceProviderRoute(configuration, "PLUS").promptVersion,
    "companion-chat-v1",
  );
  assert.equal(
    selectVoiceProviderRoute(
      { ...configuration, VOICE_FRIEND_ENABLED: true },
      "PLUS",
    ).promptVersion,
    VOICE_FRIEND_GUIDANCE_VERSION,
  );
});

void test("friend Voice counts the exact prompt before inference with or without a pilot and blocks overflow", async () => {
  const original = globalThis.fetch;
  try {
    for (const pilot of [false, true])
      for (const counted of [1900, 1901]) {
        const observations: VoiceProviderObservation[] = [];
        const requests: Record<string, unknown>[] = [];
        let countedInput: unknown;
        globalThis.fetch = (url, init) => {
          assert.equal(
            new URL(url instanceof Request ? url.url : url).hostname,
            "bedrock-runtime.ap-southeast-1.amazonaws.com",
          );
          assert.equal(typeof init?.body, "string");
          if (typeof init?.body !== "string") throw Error("EXPECTED_BODY");
          const body = JSON.parse(init.body) as Record<string, unknown>;
          requests.push(body);
          if (requests.length === 1) {
            countedInput = (body.input as { converse: unknown }).converse;
            return Promise.resolve(Response.json({ inputTokens: counted }));
          }
          assert.deepEqual(countedInput, {
            system: body.system,
            messages: body.messages,
          });
          assert.deepEqual(body.inferenceConfig, {
            temperature: 0.2,
            maxTokens: pilot ? 600 : 2000,
          });
          assert.equal(Object.hasOwn(body, "outputConfig"), false);
          assert.equal(Object.hasOwn(body, "toolConfig"), false);
          assert.equal(
            JSON.stringify(body.system).includes(VOICE_FRIEND_GUIDANCE_VERSION),
            true,
          );
          assert.equal(
            JSON.stringify(body.system).includes("PRIVATE_SPEECH"),
            false,
          );
          return Promise.resolve(response());
        };
        const provider = new RoutedChatProvider({
          ...configuration,
          VOICE_FRIEND_ENABLED: true,
          ...(pilot
            ? {
                VOICE_PILOT: {
                  startsAt: new Date(),
                  endsAt: new Date(Date.now() + 60000),
                  ownerIds: ["00000000-0000-4000-8000-000000000001"],
                  maxOutputTokens: 600,
                },
              }
            : {}),
        });
        const work = provider.reply(
          "PRIVATE_SPEECH",
          { kinds: [], prompt: "PRIVATE_CURRENT_CALL" },
          route,
          { voice: true, voiceObserver: (value) => observations.push(value) },
        );
        if (counted === 1900) {
          await work;
          assert.equal(requests.length, 2);
          assert.equal(
            observations.filter((v) => v.phase === "provider_usage").length,
            1,
          );
        } else {
          await assert.rejects(
            work,
            (e) =>
              e instanceof VoiceProviderFailure &&
              e.code === "INPUT_BUDGET_EXCEEDED",
          );
          assert.equal(requests.length, 1);
        }
        assert.equal(JSON.stringify(observations).includes("PRIVATE"), false);
      }
  } finally {
    globalThis.fetch = original;
  }
});

void test("Voice persona switch cannot alter Text provider bytes or add navigation suggestions", async () => {
  const original = globalThis.fetch;
  try {
    const textBodies: string[] = [];
    for (const enabled of [false, true]) {
      globalThis.fetch = (_url, init) => {
        if (typeof init?.body !== "string") throw Error("EXPECTED_BODY");
        textBodies.push(init.body);
        return Promise.resolve(response());
      };
      await new RoutedChatProvider({
        ...configuration,
        VOICE_FRIEND_ENABLED: enabled,
      }).reply("Câu thử tiếng Việt", { kinds: [], prompt: "" }, route);
    }
    assert.equal(textBodies[0], textBodies[1]);
    for (const [patch, code] of [
      [{ clinicalAssessment: "DIAGNOSIS" }, "OUTPUT_SCHEMA_INVALID"],
      [{ suggestion: { destination: "JOURNAL" } }, "OUTPUT_SCHEMA_INVALID"],
      [
        { message: "Bạn hãy mở https://untrusted.invalid để xem nhé." },
        "OUTPUT_SAFETY_REJECTED",
      ],
    ] as const) {
      let calls = 0;
      globalThis.fetch = () =>
        Promise.resolve(
          ++calls === 1
            ? Response.json({ inputTokens: 1200 })
            : response({ ...output(), ...patch }),
        );
      await assert.rejects(
        new RoutedChatProvider({
          ...configuration,
          VOICE_FRIEND_ENABLED: true,
        }).reply("Câu thử tiếng Việt", { kinds: [], prompt: "" }, route, {
          voice: true,
        }),
        (e) => e instanceof VoiceProviderFailure && e.code === code,
      );
      assert.equal(calls, 2);
    }
  } finally {
    globalThis.fetch = original;
  }
});
