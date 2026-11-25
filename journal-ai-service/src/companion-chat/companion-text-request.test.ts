import assert from "node:assert/strict";
import test from "node:test";
import {
  companionUserInput,
  estimateInputTokens,
} from "./companion-text-request.js";

const context = {
  kinds: ["JOURNAL" as const],
  prompt: "Nhật ký <b>hôm qua</b>",
};

void test("input without friend equals the v1 JSON shape with escaped angle brackets", () => {
  const message = "Mình <script>buồn</script>";
  assert.equal(
    companionUserInput({ message, context }),
    JSON.stringify({
      message,
      context: { kinds: context.kinds, data: context.prompt },
    })
      .replaceAll("<", "\\u003c")
      .replaceAll(">", "\\u003e"),
  );
});

void test("friend input adds history and a compact catalogue as data", () => {
  const input = companionUserInput({
    message: "Có gì giúp mình đỡ hơn không?",
    context: { kinds: [], prompt: "" },
    friend: {
      history: [
        { role: "USER", text: "Hôm nay buồn quá" },
        { role: "ASSISTANT", text: "Mình nghe nè <3" },
      ],
      catalog: [
        {
          ref: "R1",
          resourceId: "00000000-0000-4000-8000-000000000001",
          category: "BREATHING",
          title: "Thở 4-7-8",
        },
      ],
    },
  });
  assert.equal(input.includes("<"), false);
  assert.deepEqual(JSON.parse(input), {
    message: "Có gì giúp mình đỡ hơn không?",
    context: { kinds: [], data: "" },
    history: [
      { role: "USER", text: "Hôm nay buồn quá" },
      { role: "ASSISTANT", text: "Mình nghe nè <3" },
    ],
    catalog: [{ ref: "R1", category: "BREATHING", title: "Thở 4-7-8" }],
  });
});

void test("token estimate counts UTF-8 bytes of system and input, rounded up", () => {
  assert.equal(estimateInputTokens("ab", "cd"), 1);
  assert.equal(estimateInputTokens("abc", "de"), 2);
  // "ệ" is 3 UTF-8 bytes.
  assert.equal(estimateInputTokens("ệệệệ", ""), 3);
});

void test("MB-846c: previous conversations come before history only when supplied", () => {
  const friend = {
    history: [{ role: "USER" as const, text: "Lúc nãy" }],
    catalog: [],
  };
  const without = companionUserInput({
    message: "x",
    context: { kinds: [], prompt: "" },
    friend,
  });
  assert.equal(without.includes("previous_conversations"), false);
  const empty = companionUserInput({
    message: "x",
    context: { kinds: [], prompt: "" },
    friend: { ...friend, previousConversations: [] },
  });
  assert.equal(empty, without);
  const withNotes = JSON.parse(
    companionUserInput({
      message: "x",
      context: { kinds: [], prompt: "" },
      friend: {
        ...friend,
        previousConversations: [
          {
            date: "20/09",
            title: "Ba deadline",
            note: "Người dùng kể về ba deadline.",
          },
        ],
      },
    }),
  ) as Record<string, unknown>;
  assert.deepEqual(Object.keys(withNotes), [
    "message",
    "context",
    "previous_conversations",
    "history",
    "catalog",
  ]);
});
