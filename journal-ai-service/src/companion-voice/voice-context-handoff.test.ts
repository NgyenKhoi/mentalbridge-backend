import assert from "node:assert/strict";
import test from "node:test";
import { randomUUID } from "node:crypto";
import {
  ChatCrypto,
  type Conversation,
} from "../companion-chat/companion-chat.js";
import { loadConfiguration } from "../configuration/configuration.js";
import { VoiceContextHandoffs } from "./voice-context-handoff.js";
import type { VoiceSession } from "./voice-ledger.js";

const code = (expected: string) => (error: unknown) =>
  error instanceof Error && "code" in error && error.code === expected;
function fixture() {
  let now = new Date("2026-11-02T10:00:00Z");
  const config = loadConfiguration({
    NODE_ENV: "test",
    IDENTITY_JWT_ISSUER: "https://identity.test",
    IDENTITY_JWT_AUDIENCE: "test",
    IDENTITY_JWT_KEY_ID: "test",
    IDENTITY_JWT_PUBLIC_KEY: "test",
  });
  const crypto = new ChatCrypto(config),
    owner = randomUUID(),
    id = randomUUID();
  let row: Conversation | null = {
    _id: id,
    ownerAccountId: owner,
    title: "Synthetic",
    context: {
      sources: { plan: false, diary: false, screening: false, resourceIds: [] },
      updatedAt: now,
    },
    messages: [],
    createdAt: now,
    updatedAt: now,
    expiresAt: new Date(now.getTime() + 86400000),
  };
  const add = (user: string, assistant: string, privateContext = false) => {
    assert.ok(row);
    for (const [role, content] of [
      ["USER", user],
      ["ASSISTANT", assistant],
    ] as const) {
      const messageId = randomUUID();
      row.messages.push({
        messageId,
        role,
        content: crypto.encrypt(owner, id, messageId, content),
        createdAt: now,
        route: null,
        contextKinds: privateContext && role === "ASSISTANT" ? ["JOURNAL"] : [],
      });
    }
  };
  const create = (enabled = true) =>
    new VoiceContextHandoffs(
      enabled,
      config.JOURNAL_IDEMPOTENCY_HMAC_KEY,
      {
        findConversation: (o, c) =>
          Promise.resolve(o === owner && c === id ? row : null),
      },
      crypto,
      () => now,
    );
  const handoffs = create();
  const session = (hash: string) =>
    ({
      voiceSessionId: randomUUID(),
      ownerId: owner,
      attemptId: randomUUID(),
      contextHandoffHash: hash,
      hardStopAt: new Date(now.getTime() + 300000),
    }) as VoiceSession;
  return {
    owner,
    id,
    add,
    create,
    handoffs,
    session,
    row: () => row,
    remove: () => {
      row = null;
    },
    advance: (ms: number) => {
      now = new Date(now.getTime() + ms);
    },
  };
}

void test("handoff tickets carry no Text content and bind selected owner pairs once in RAM", async () => {
  const f = fixture();
  f.add("SYNTHETIC_TOPIC", "Mình đang lắng nghe.");
  const ticket = await f.handoffs.prepare(f.owner, f.id);
  assert.equal(ticket.selectedPairs, 1);
  assert.equal(
    Buffer.from(ticket.handoffToken.split(".")[0] ?? "", "base64url")
      .toString()
      .includes("SYNTHETIC_TOPIC"),
    false,
  );
  const prepared = await f.handoffs.resolve(f.owner, ticket.handoffToken),
    session = f.session(prepared.hash);
  f.handoffs.bind(session, prepared);
  f.handoffs.bind(session);
  assert.deepEqual(f.handoffs.take(session), [
    { user: "SYNTHETIC_TOPIC", assistant: "Mình đang lắng nghe." },
  ]);
  assert.throws(() => f.handoffs.take(session), code("STALE_ATTEMPT"));
  await assert.rejects(
    f.handoffs.resolve(f.owner, ticket.handoffToken),
    code("STALE_ATTEMPT"),
  );
  f.handoffs.discard(session.voiceSessionId);
  assert.throws(() => {
    f.handoffs.bind(session);
  }, code("STALE_ATTEMPT"));
});

void test("an accepted same-session START can prepare its still-unbound seed without reseeding a bound session", async () => {
  const f = fixture();
  f.add("SYNTHETIC_TOPIC", "Synthetic answer");
  const ticket = await f.handoffs.prepare(f.owner, f.id);
  const first = await f.handoffs.resolveStart(f.owner, ticket.handoffToken);
  assert.ok(first);
  const session = f.session(first.hash);
  const acceptedRetry = await f.handoffs.resolveStart(
    f.owner,
    ticket.handoffToken,
    session,
  );
  assert.deepEqual(acceptedRetry, first);
  assert.ok(acceptedRetry);
  await assert.rejects(
    f.create().resolveStart(f.owner, ticket.handoffToken, session),
    code("STALE_ATTEMPT"),
  );
  f.handoffs.bind(session, acceptedRetry);
  f.handoffs.bind(session, first);
  assert.equal(
    await f.handoffs.resolveStart(f.owner, ticket.handoffToken, session),
    undefined,
  );
  assert.deepEqual(f.handoffs.take(session), [
    { user: "SYNTHETIC_TOPIC", assistant: "Synthetic answer" },
  ]);
  f.advance(120000);
  assert.equal(
    await f.handoffs.resolveStart(f.owner, ticket.handoffToken, session),
    undefined,
  );
  assert.throws(() => {
    f.handoffs.take(session);
  }, code("STALE_ATTEMPT"));
});

void test("wrong owner, tampering, deleted/expired chat and changed snapshot fail closed", async () => {
  const f = fixture();
  f.add("Chủ đề giả lập", "Mình đang lắng nghe.");
  const ticket = await f.handoffs.prepare(f.owner, f.id);
  await assert.rejects(
    f.handoffs.prepare(randomUUID(), f.id),
    code("SESSION_NOT_FOUND"),
  );
  await assert.rejects(
    f.handoffs.resolve(randomUUID(), ticket.handoffToken),
    code("INVALID_REQUEST"),
  );
  await assert.rejects(
    f.handoffs.resolve(f.owner, ticket.handoffToken + "x"),
    code("INVALID_REQUEST"),
  );
  f.add("Thay đổi", "Nội dung mới.");
  await assert.rejects(
    f.handoffs.resolve(f.owner, ticket.handoffToken),
    code("STALE_ATTEMPT"),
  );
  f.remove();
  await assert.rejects(
    f.handoffs.resolve(f.owner, ticket.handoffToken),
    code("SESSION_NOT_FOUND"),
  );
  const expired = fixture();
  expired.add("Chủ đề", "Nội dung.");
  expired.advance(86400000);
  await assert.rejects(
    expired.handoffs.prepare(expired.owner, expired.id),
    code("SESSION_NOT_FOUND"),
  );
});

void test("120-second expiry, process affinity and missing session RAM cannot silently recreate context", async () => {
  const f = fixture();
  f.add("Chủ đề", "Mình đang lắng nghe.");
  const ticket = await f.handoffs.prepare(f.owner, f.id);
  await assert.rejects(
    f.create().resolve(f.owner, ticket.handoffToken),
    code("STALE_ATTEMPT"),
  );
  f.advance(120000);
  await assert.rejects(
    f.handoffs.resolve(f.owner, ticket.handoffToken),
    code("INVALID_REQUEST"),
  );
  const hash = f.handoffs.reference(f.owner, ticket.handoffToken);
  assert.throws(() => {
    f.handoffs.bind(f.session(hash));
  }, code("STALE_ATTEMPT"));
});

void test("selection is at most two recent pairs, stops at private-context provenance and never decrypts excluded messages", async () => {
  const f = fixture();
  f.add("EXCLUDED_PRIVATE_SOURCE", "Không được chuyển.", true);
  f.add("Chủ đề gần đây", "Mình lắng nghe.");
  f.add("Chủ đề cuối", "Bạn muốn chia sẻ thêm?");
  const ticket = await f.handoffs.prepare(f.owner, f.id),
    prepared = await f.handoffs.resolve(f.owner, ticket.handoffToken);
  assert.equal(prepared.pairs.length, 2);
  assert.equal(JSON.stringify(prepared.pairs).includes("EXCLUDED"), false);
  f.add("Không chuyển", "Có nguồn cá nhân.", true);
  await assert.rejects(
    f.handoffs.prepare(f.owner, f.id),
    code("INVALID_REQUEST"),
  );
});

void test("oversized latest pair is not silently truncated or skipped to an older topic; disabled means no read", async () => {
  const f = fixture();
  f.add("Ngắn", "Mình nghe.");
  f.add("ắ".repeat(800), "Mình nghe.");
  await assert.rejects(
    f.handoffs.prepare(f.owner, f.id),
    code("INVALID_REQUEST"),
  );
  await assert.rejects(
    f.create(false).prepare(f.owner, f.id),
    code("PROVIDER_UNAVAILABLE"),
  );
});

void test("preparation limits each owner to four live tickets and releases expired entries", async () => {
  const f = fixture();
  f.add("Synthetic topic", "Synthetic answer");
  for (let i = 0; i < 4; i += 1) await f.handoffs.prepare(f.owner, f.id);
  await assert.rejects(f.handoffs.prepare(f.owner, f.id), code("RATE_LIMITED"));
  f.advance(120000);
  assert.equal((await f.handoffs.prepare(f.owner, f.id)).selectedPairs, 1);
});
