import assert from "node:assert/strict";
import test from "node:test";
import { authorizeVoiceInput } from "../llm-providers/bedrock-voice-token-budget.js";
import { voicePilotSchema } from "./voice-pilot.js";
import { randomUUID } from "node:crypto";

void test("pilot input counting fails closed on overflow, permission, malformed and unknown outcomes without retry", async () => {
  const original = globalThis.fetch;
  let calls = 0;
  try {
    for (const response of [
      Response.json({ inputTokens: 1900 }),
      Response.json({ inputTokens: 1901 }),
      Response.json({ inputTokens: "12" }),
      Response.json({ input_tokens: 12 }),
      new Response(null, { status: 403 }),
      new Response(null, { status: 429 }),
    ]) {
      globalThis.fetch = (url, options) => {
        calls++;
        assert.ok(options);
        assert.equal(
          (url instanceof Request ? new URL(url.url) : new URL(url)).href,
          "https://bedrock-runtime.ap-southeast-1.amazonaws.com/model/anthropic.claude-sonnet-4-6/count-tokens",
        );
        assert.equal(
          new Headers(options.headers).get("authorization"),
          "Bearer synthetic",
        );
        assert.equal(new Headers(options.headers).has("x-api-key"), false);
        assert.equal(
          new Headers(options.headers).has("anthropic-version"),
          false,
        );
        assert.equal(options.method, "POST");
        assert.equal(options.redirect, "error");
        assert.ok(typeof options.body === "string");
        const body = JSON.parse(options.body) as {
          input: {
            converse: {
              system: { text: string }[];
              messages: { role: string; content: { text: string }[] }[];
            };
          };
        };
        assert.deepEqual(body, {
          input: {
            converse: {
              system: [{ text: "synthetic system plus full schema" }],
              messages: [
                { role: "user", content: [{ text: "synthetic user" }] },
              ],
            },
          },
        });
        return Promise.resolve(response);
      };
      const work = authorizeVoiceInput(
        "synthetic",
        "synthetic system plus full schema",
        "synthetic user",
        AbortSignal.timeout(1000),
      );
      if (calls === 1) await work;
      else await assert.rejects(work);
    }
    assert.equal(calls, 6);
  } finally {
    globalThis.fetch = original;
  }
});

void test("pilot window cannot outlive retained admission evidence or include more than two owners", () => {
  const pilot = {
    startsAt: "2026-10-29T00:00:00.000Z",
    endsAt: "2026-10-30T00:00:00.000Z",
    ownerIds: [randomUUID(), randomUUID()],
  };
  assert.ok(voicePilotSchema.safeParse(pilot).success);
  assert.equal(
    voicePilotSchema.safeParse({ ...pilot, endsAt: "2026-10-31T00:00:00.000Z" })
      .success,
    false,
  );
  assert.equal(
    voicePilotSchema.safeParse({
      ...pilot,
      ownerIds: [...pilot.ownerIds, randomUUID()],
    }).success,
    false,
  );
});
