import assert from "node:assert/strict";
import test from "node:test";
import type {
  ChatMessage,
  ContextKind,
  ConversationContextSources,
} from "./companion-chat.js";
import { recentHistory } from "./conversation-history.js";

const texts = new Map<string, string>();
const message = (
  index: number,
  text: string,
  contextKinds: ContextKind[] = [],
): ChatMessage => {
  const messageId = `message-${String(index)}`;
  texts.set(messageId, text);
  return {
    messageId,
    role: index % 2 === 0 ? "USER" : "ASSISTANT",
    content: {
      ciphertext: Buffer.alloc(0),
      iv: Buffer.alloc(0),
      tag: Buffer.alloc(0),
      algorithm: "AES-256-GCM",
      keyId: "test",
    },
    createdAt: new Date(0),
    route: null,
    contextKinds,
  };
};
const decrypt = (value: ChatMessage) => texts.get(value.messageId) ?? "";
const allSelected: ConversationContextSources = {
  plan: true,
  diary: true,
  screening: true,
  resourceIds: ["11111111-1111-4111-8111-111111111111"],
};

void test("keeps at most the last 8 messages, oldest first", () => {
  const messages = Array.from({ length: 12 }, (_, index) =>
    message(index, `lượt ${String(index)}`),
  );
  const history = recentHistory(messages, allSelected, decrypt);
  assert.deepEqual(
    history.map((turn) => turn.text),
    [4, 5, 6, 7, 8, 9, 10, 11].map((index) => `lượt ${String(index)}`),
  );
  assert.deepEqual(
    history.map((turn) => turn.role),
    [
      "USER",
      "ASSISTANT",
      "USER",
      "ASSISTANT",
      "USER",
      "ASSISTANT",
      "USER",
      "ASSISTANT",
    ],
  );
});

void test("drops an assistant turn whose context kind is no longer selected", () => {
  const messages = [
    message(0, "Hôm nay mình buồn"),
    message(1, "Từ nhật ký của bạn", ["JOURNAL"]),
    message(2, "Ừ"),
    message(3, "Theo kế hoạch", ["SUPPORT_PLAN"]),
  ];
  const history = recentHistory(
    messages,
    { ...allSelected, diary: false },
    decrypt,
  );
  assert.deepEqual(
    history.map((turn) => turn.text),
    ["Hôm nay mình buồn", "Ừ", "Theo kế hoạch"],
  );
  const withdrawn: [ContextKind, ConversationContextSources][] = [
    ["SUPPORT_PLAN", { ...allSelected, plan: false }],
    ["REASSESSMENT", { ...allSelected, screening: false }],
    ["RESOURCE", { ...allSelected, resourceIds: [] }],
  ];
  for (const [kind, sources] of withdrawn)
    assert.deepEqual(
      recentHistory([message(1, "bối cảnh", [kind])], sources, decrypt),
      [],
    );
});

void test("keeps turns with empty kinds or still-selected kinds", () => {
  const messages = [
    message(0, "Chào"),
    message(1, "Có kế hoạch", ["SUPPORT_PLAN", "RESOURCE"]),
  ];
  assert.equal(recentHistory(messages, allSelected, decrypt).length, 2);
});

void test("keeps the newest turns within 3000 UTF-8 bytes", () => {
  // "ệ" is 3 UTF-8 bytes, so 400 characters are 1200 bytes.
  const long = "ệ".repeat(400);
  const messages = [
    message(0, `cũ ${long}`),
    message(1, long),
    message(2, long),
    message(3, "mới nhất"),
  ];
  const history = recentHistory(messages, allSelected, decrypt);
  assert.deepEqual(
    history.map((turn) => turn.text),
    [long, long, "mới nhất"],
  );
  assert.ok(
    history.reduce((sum, turn) => sum + Buffer.byteLength(turn.text), 0) <=
      3_000,
  );
});

void test("an empty conversation gives no history", () => {
  assert.deepEqual(recentHistory([], allSelected, decrypt), []);
});
