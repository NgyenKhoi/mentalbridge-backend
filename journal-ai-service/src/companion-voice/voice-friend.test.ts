import assert from "node:assert/strict";
import test from "node:test";
import { loadConfiguration } from "../configuration/configuration.js";
import {
  RoutedChatProvider,
  withoutOldestVoicePair,
} from "../companion-chat/companion-chat.js";
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

void test("Voice always speaks as Mầm: compact, speech-only, comfort plus a step, no default question", () => {
  for (const textHandoff of [false, true])
    for (const replyStyle of [undefined, "BRIEF", "SUPPORTIVE"] as const) {
      const friend = companionGuidanceSystem({
        channel: "VOICE",
        textHandoff,
        ...(replyStyle ? { replyStyle } : {}),
      });
      assert.match(friend, /You are Mầm/u);
      assert.match(friend, /as tớ to bạn/u);
      assert.match(
        friend,
        /Do not ask follow-up questions by default. When sufficient context exists, provide helpful guidance and end naturally. Ask at most ONE question, only when essential information is missing or the user explicitly wants to explore further. Do not optimize for conversation length./u,
      );
      assert.match(friend, /then one or two concrete small steps/u);
      assert.match(friend, /Humour scales with distress/u);
      assert.match(friend, /never claim to remember an earlier call/u);
      assert.match(friend, /at most 800 characters/u);
      assert.match(friend, /No suggestion field/u);
      assert.match(friend, /Cần trợ giúp ngay/u);
      assert.match(friend, /Never diagnose/u);
      assert.match(friend, /No humour, playful particles/u);
      assert.match(friend, /no emoji, emoticons/u);
      // The 1,900-token Voice input cap must leave room for history.
      assert.equal(Buffer.byteLength(friend, "utf8") <= 3_300, true);
      assert.equal(friend.includes("selectedText"), textHandoff);
    }
  assert.equal(
    selectVoiceProviderRoute(configuration, "PLUS").promptVersion,
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

void test("Voice rejects authority changes, navigation suggestions and links", async () => {
  const original = globalThis.fetch;
  try {
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
        new RoutedChatProvider(configuration).reply(
          "Câu thử tiếng Việt",
          { kinds: [], prompt: "" },
          route,
          {
            voice: true,
          },
        ),
        (e) => e instanceof VoiceProviderFailure && e.code === code,
      );
      assert.equal(calls, 2);
    }
  } finally {
    globalThis.fetch = original;
  }
});

void test("MB-846d: over the input cap Voice drops the oldest history pair and recounts before refusing", async () => {
  assert.equal(
    withoutOldestVoicePair(
      JSON.stringify({
        selectedText: [{ user: "t1", assistant: "a1" }],
        currentCall: [{ user: "c1", assistant: "b1" }],
      }),
    ),
    JSON.stringify({
      selectedText: [],
      currentCall: [{ user: "c1", assistant: "b1" }],
    }),
  );
  assert.equal(withoutOldestVoicePair("[]"), null);
  assert.equal(withoutOldestVoicePair("not json"), null);
  const original = globalThis.fetch;
  const history = JSON.stringify([
    { user: "OLDEST_PAIR", assistant: "Mình nghe." },
    { user: "NEWEST_PAIR", assistant: "Mình hiểu." },
  ]);
  try {
    for (const alwaysOver of [false, true]) {
      const counted: string[] = [];
      let inferred = "";
      globalThis.fetch = (url, init) => {
        if (typeof init?.body !== "string") throw Error("EXPECTED_BODY");
        if (
          String(url instanceof Request ? url.url : url).endsWith(
            "/count-tokens",
          )
        ) {
          counted.push(init.body);
          const over = alwaysOver || init.body.includes("OLDEST_PAIR");
          return Promise.resolve(
            Response.json({ inputTokens: over ? 1901 : 1800 }),
          );
        }
        inferred = init.body;
        return Promise.resolve(response());
      };
      const work = new RoutedChatProvider(configuration).reply(
        "Câu thử tiếng Việt",
        { kinds: [], prompt: history },
        route,
        { voice: true },
      );
      if (alwaysOver) {
        await assert.rejects(
          work,
          (e) =>
            e instanceof VoiceProviderFailure &&
            e.code === "INPUT_BUDGET_EXCEEDED",
        );
        assert.equal(counted.length, 3);
        assert.equal(inferred, "");
      } else {
        await work;
        assert.equal(counted.length, 2);
        assert.equal(inferred.includes("OLDEST_PAIR"), false);
        assert.equal(inferred.includes("NEWEST_PAIR"), true);
        assert.equal(inferred.includes(VOICE_FRIEND_GUIDANCE_VERSION), true);
        assert.match(inferred, /Cần trợ giúp ngay/u);
      }
    }
  } finally {
    globalThis.fetch = original;
  }
});

void test("handed-off Text pairs reach the Voice provider input as quoted selectedText with the handoff guidance", async () => {
  const original = globalThis.fetch;
  const prompt = JSON.stringify({
    selectedText: [
      { user: "SYNTHETIC_TEXT_USER", assistant: "SYNTHETIC_TEXT_ASSISTANT" },
    ],
    currentCall: [],
  });
  try {
    for (const textHandoff of [true, false]) {
      let inferred: { system: unknown; messages: unknown } | undefined;
      globalThis.fetch = (url, init) => {
        if (typeof init?.body !== "string") throw Error("EXPECTED_BODY");
        if (
          String(url instanceof Request ? url.url : url).endsWith(
            "/count-tokens",
          )
        )
          return Promise.resolve(Response.json({ inputTokens: 1200 }));
        inferred = JSON.parse(init.body) as typeof inferred;
        return Promise.resolve(response());
      };
      await new RoutedChatProvider(configuration).reply(
        "Câu thử tiếng Việt",
        { kinds: [], prompt },
        route,
        { voice: true, textHandoff },
      );
      assert.ok(inferred);
      const messages = JSON.stringify(inferred.messages);
      assert.match(messages, /SYNTHETIC_TEXT_USER/u);
      assert.match(messages, /SYNTHETIC_TEXT_ASSISTANT/u);
      assert.equal(
        JSON.stringify(inferred.system).includes(
          "explicitly attached selectedText pairs",
        ),
        textHandoff,
      );
    }
  } finally {
    globalThis.fetch = original;
  }
});
