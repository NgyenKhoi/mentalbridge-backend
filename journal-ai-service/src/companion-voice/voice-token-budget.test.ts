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
      Response.json({ input_tokens: 1900 }),
      Response.json({ input_tokens: 1901 }),
      Response.json({ input_tokens: "12" }),
      new Response(null, { status: 403 }),
      new Response(null, { status: 429 }),
    ]) {
      globalThis.fetch = (url, options) => {
        calls++;
        assert.equal(
          (url instanceof Request ? new URL(url.url) : new URL(url)).href,
          "https://bedrock-mantle.ap-northeast-1.api.aws/anthropic/v1/messages/count_tokens",
        );
        assert.equal(
          new Headers(options?.headers).get("x-api-key"),
          "synthetic",
        );
        assert.ok(typeof options?.body === "string");
        const body = JSON.parse(options.body) as {
          model: string;
          system: string;
        };
        assert.equal(body.model, "anthropic.claude-sonnet-4-6");
        assert.equal(body.system, "synthetic system plus full schema");
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
    assert.equal(calls, 5);
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
