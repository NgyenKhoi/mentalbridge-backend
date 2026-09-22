import assert from "node:assert/strict";
import test from "node:test";
import { HttpException } from "@nestjs/common";

import type { ConsentClient } from "../analysis/analysis.js";
import { loadConfiguration } from "../configuration/configuration.js";
import type { ServiceConfiguration } from "../configuration/configuration.js";
import type { EntitlementDecision } from "../model-routing/model-routing.js";
import {
  CompanionChatService,
  ChatCrypto,
  type ChatCommand,
  type ChatContextAssembler,
  type ChatMessage,
  type ChatProvider,
  type ChatRepository,
  type Conversation,
  type SendResult,
} from "./companion-chat.js";

const ownerId = "11111111-1111-4111-8111-111111111111";
const otherOwnerId = "22222222-2222-4222-8222-222222222222";
const configuration = loadConfiguration({
  NODE_ENV: "test",
  IDENTITY_JWT_ISSUER: "https://identity.test.mentalbridge",
  IDENTITY_JWT_AUDIENCE: "mentalbridge-api",
  IDENTITY_JWT_KEY_ID: "test-key",
  IDENTITY_JWT_PUBLIC_KEY: "test-public-key",
  JOURNAL_AI_CHAT_FREE_DAILY_ANSWERS: "5",
  JOURNAL_AI_CHAT_PLUS_DAILY_ANSWERS: "7",
  JOURNAL_AI_CHAT_PREMIUM_FAIR_USE_DAILY_ANSWERS: "9",
  JOURNAL_AI_CHAT_RATE_LIMIT_PER_MINUTE: "20",
  JOURNAL_AI_CHAT_DAILY_TOKEN_BUDGET: "10000",
});

class MemoryRepository implements ChatRepository {
  readonly conversations: Conversation[] = [];
  readonly commands: ChatCommand[] = [];
  readonly quota = new Map<
    string,
    { successes: number; tokens: number; reservations: Set<string> }
  >();
  readonly rates = new Map<string, number>();

  createConversation(value: Conversation) {
    this.conversations.push(value);
    return Promise.resolve(value);
  }
  listConversations(owner: string) {
    return Promise.resolve(
      this.conversations.filter((row) => row.ownerAccountId === owner),
    );
  }
  findConversation(owner: string, id: string) {
    return Promise.resolve(
      this.conversations.find(
        (row) => row.ownerAccountId === owner && row._id === id,
      ) ?? null,
    );
  }
  deleteConversation(owner: string, id: string) {
    const index = this.conversations.findIndex(
      (row) => row.ownerAccountId === owner && row._id === id,
    );
    if (index < 0) return Promise.resolve(false);
    this.conversations.splice(index, 1);
    for (
      let candidate = this.commands.length - 1;
      candidate >= 0;
      candidate -= 1
    ) {
      if (
        this.commands[candidate]?.ownerAccountId === owner &&
        this.commands[candidate]?.conversationId === id
      )
        this.commands.splice(candidate, 1);
    }
    return Promise.resolve(true);
  }
  findCommand(owner: string, keyHash: string) {
    return Promise.resolve(
      this.commands.find(
        (row) => row.ownerAccountId === owner && row.keyHash === keyHash,
      ) ?? null,
    );
  }
  beginCommand(command: ChatCommand) {
    if (
      this.commands.some(
        (row) =>
          row.ownerAccountId === command.ownerAccountId &&
          row.keyHash === command.keyHash,
      )
    )
      throw new Error("duplicate");
    this.commands.push(command);
    return Promise.resolve();
  }
  reserve(
    owner: string,
    reservation: string,
    day: string,
    minute: string,
    _plan: EntitlementDecision["packageCode"],
    answerLimit: number,
    rateLimit: number,
    tokenBudget: number,
  ) {
    const rateKey = `${owner}:${minute}`;
    const rate = this.rates.get(rateKey) ?? 0;
    if (rate >= rateLimit)
      throw new HttpException({ code: "CHAT_RATE_LIMITED" }, 429);
    this.rates.set(rateKey, rate + 1);
    const key = `${owner}:${day}`;
    const quota = this.quota.get(key) ?? {
      successes: 0,
      tokens: 0,
      reservations: new Set<string>(),
    };
    if (
      quota.successes + quota.reservations.size >= answerLimit ||
      quota.tokens >= tokenBudget
    )
      throw new HttpException({ code: "CHAT_QUOTA_EXHAUSTED" }, 429);
    quota.reservations.add(reservation);
    this.quota.set(key, quota);
    return Promise.resolve({
      successfulAnswers: quota.successes,
      usedTokens: quota.tokens,
    });
  }
  complete(
    command: ChatCommand,
    userMessage: ChatMessage,
    assistantMessage: ChatMessage,
    result: SendResult,
    usedTokens: number,
  ) {
    const conversation = this.conversations.find(
      (row) => row._id === command.conversationId,
    );
    if (!conversation) throw new Error("missing conversation");
    conversation.messages.push(userMessage, assistantMessage);
    conversation.updatedAt = new Date(result.createdAt);
    const quota = [...this.quota.values()].find((row) =>
      row.reservations.has(command._id),
    );
    if (!quota) throw new Error("missing reservation");
    quota.reservations.delete(command._id);
    quota.successes += 1;
    quota.tokens += usedTokens;
    command.state = "SUCCEEDED";
    command.response = result;
    return Promise.resolve();
  }
  fail(command: ChatCommand, code: string, status: number, title: string) {
    for (const quota of this.quota.values())
      quota.reservations.delete(command._id);
    command.state = "FAILED";
    command.failureCode = code;
    command.failureStatus = status;
    command.failureTitle = title;
    return Promise.resolve();
  }
}

const granted: ConsentClient = {
  check: () => Promise.resolve({ authorized: true, reason: "GRANTED" }),
};
const context: ChatContextAssembler = {
  assemble: () =>
    Promise.resolve({
      kinds: ["SUPPORT_PLAN"],
      prompt: "Approved current plan summary",
    }),
};
const provider: ChatProvider = {
  reply: () =>
    Promise.resolve({
      message: "Một phản hồi hỗ trợ đã chuẩn hóa.",
      inputTokens: 20,
      outputTokens: 10,
    }),
};
const request = (
  body: unknown,
  key = "chat-command-key-0001",
  accountId = ownerId,
) => ({
  id: "chat-test-correlation",
  headers: { authorization: "Bearer synthetic-token", "idempotency-key": key },
  principal: { accountId, roles: ["USER"] },
  body,
});

const subject = (
  plan: EntitlementDecision["packageCode"] = "FREE",
  overrides: {
    consent?: ConsentClient;
    context?: ChatContextAssembler;
    provider?: ChatProvider;
  } = {},
  runtimeConfiguration: ServiceConfiguration = configuration,
) => {
  const repository = new MemoryRepository();
  let now = new Date("2026-09-20T16:59:00.000Z");
  const service = new CompanionChatService(
    repository,
    overrides.consent ?? granted,
    {
      current: () =>
        Promise.resolve({
          packageCode: plan,
          source: plan === "FREE" ? "DEFAULT_FREE" : "PAID",
          policyVersion: "service-entitlement-v1",
          version: 1,
        }),
    },
    overrides.context ?? context,
    overrides.provider ?? provider,
    { now: () => new Date(now) },
    runtimeConfiguration,
  );
  return {
    repository,
    service,
    setNow: (value: string) => {
      now = new Date(value);
    },
  };
};

const create = (service: CompanionChatService, accountId = ownerId) =>
  service.create(request({}, "unused-create-key", accountId));

void test("creates encrypted owner-scoped conversations and hard deletes their content", async () => {
  const { repository, service } = subject();
  const conversation = await create(service);
  await service.send(
    request({
      message: "Nội dung riêng tư",
      context: {
        journalIds: [],
        includeCurrentSupportPlan: true,
        includeReminderContext: false,
      },
    }),
    conversation.conversationId,
  );
  assert.equal(
    JSON.stringify(repository.conversations).includes("Nội dung riêng tư"),
    false,
  );
  await assert.rejects(() =>
    service.get(
      request({}, "unused", otherOwnerId),
      conversation.conversationId,
    ),
  );
  await service.delete(request({}, "unused"), conversation.conversationId);
  assert.equal(repository.conversations.length, 0);
  assert.equal(repository.commands.length, 0);
});

void test("enforces five FREE successes, replays exactly, and resets on the next local day", async () => {
  const { service, setNow } = subject("FREE");
  const conversation = await create(service);
  let first: SendResult | undefined;
  for (let index = 0; index < 5; index += 1) {
    const result = await service.send(
      request(
        { message: `Tin ${String(index)}` },
        `chat-command-key-000${String(index)}`,
      ),
      conversation.conversationId,
    );
    first ??= result;
    assert.equal(result.quota.remaining, 4 - index);
  }
  assert.deepEqual(
    await service.send(
      request({ message: "Tin 0" }, "chat-command-key-0000"),
      conversation.conversationId,
    ),
    first,
  );
  await assert.rejects(
    () =>
      service.send(
        request({ message: "Vượt hạn mức" }, "chat-command-key-9999"),
        conversation.conversationId,
      ),
    (error: unknown) =>
      error instanceof HttpException && error.getStatus() === 429,
  );
  setNow("2026-09-20T17:01:00.000Z");
  const nextDay = await service.send(
    request({ message: "Ngày mới" }, "chat-command-key-nextday"),
    conversation.conversationId,
  );
  assert.equal(nextDay.quota.remaining, 4);
});

void test("uses configurable PLUS limits and hides the PREMIUM fair-use answer count", async () => {
  const plus = subject("PLUS");
  const plusConversation = await create(plus.service);
  const plusResult = await plus.service.send(
    request({ message: "Plus" }),
    plusConversation.conversationId,
  );
  assert.equal(plusResult.quota.remaining, 6);
  assert.equal(plusResult.quota.limitDisplayed, true);

  const premium = subject("PREMIUM");
  const premiumConversation = await create(premium.service);
  const premiumResult = await premium.service.send(
    request({ message: "Premium" }),
    premiumConversation.conversationId,
  );
  assert.equal(premiumResult.quota.remaining, null);
  assert.equal(premiumResult.quota.limitDisplayed, false);

  for (let index = 1; index < 9; index += 1)
    await premium.service.send(
      request(
        { message: `Premium ${String(index)}` },
        `chat-premium-key-${String(index).padStart(4, "0")}`,
      ),
      premiumConversation.conversationId,
    );
  await assert.rejects(
    () =>
      premium.service.send(
        request({ message: "Premium fair use" }, "chat-premium-key-fair-use"),
        premiumConversation.conversationId,
      ),
    (error: unknown) =>
      error instanceof HttpException && error.getStatus() === 429,
  );
});

void test("enforces request-rate and token controls without consuming an answer", async () => {
  const rateLimited = subject(
    "PREMIUM",
    {},
    { ...configuration, CHAT_RATE_LIMIT_PER_MINUTE: 2 },
  );
  const rateConversation = await create(rateLimited.service);
  await rateLimited.service.send(
    request({ message: "First" }, "chat-rate-command-0001"),
    rateConversation.conversationId,
  );
  await rateLimited.service.send(
    request({ message: "Second" }, "chat-rate-command-0002"),
    rateConversation.conversationId,
  );
  await assert.rejects(
    () =>
      rateLimited.service.send(
        request({ message: "Third" }, "chat-rate-command-0003"),
        rateConversation.conversationId,
      ),
    (error: unknown) =>
      error instanceof HttpException && error.getStatus() === 429,
  );

  const tokenLimited = subject(
    "PREMIUM",
    {
      provider: {
        reply: () =>
          Promise.resolve({
            message: "Bounded",
            inputTokens: 700,
            outputTokens: 400,
          }),
      },
    },
    { ...configuration, CHAT_DAILY_TOKEN_BUDGET: 1_000 },
  );
  const tokenConversation = await create(tokenLimited.service);
  await assert.rejects(
    () =>
      tokenLimited.service.send(
        request({ message: "Token guard" }, "chat-token-command-0001"),
        tokenConversation.conversationId,
      ),
    (error: unknown) =>
      error instanceof HttpException && error.getStatus() === 429,
  );
  assert.equal([...tokenLimited.repository.quota.values()][0]?.successes, 0);
});

void test("rejects an idempotency key reused for different content", async () => {
  const { service } = subject();
  const conversation = await create(service);
  await service.send(
    request({ message: "Original" }, "chat-reused-command-0001"),
    conversation.conversationId,
  );
  await assert.rejects(
    () =>
      service.send(
        request({ message: "Changed" }, "chat-reused-command-0001"),
        conversation.conversationId,
      ),
    (error: unknown) =>
      error instanceof HttpException && error.getStatus() === 409,
  );
});

void test("does not consume a successful answer when provider execution fails", async () => {
  let attempts = 0;
  const { repository, service } = subject("FREE", {
    provider: {
      reply: () => {
        attempts += 1;
        return Promise.reject(new Error("synthetic provider timeout"));
      },
    },
  });
  const conversation = await create(service);
  await assert.rejects(() =>
    service.send(request({ message: "Timeout" }), conversation.conversationId),
  );
  assert.equal(attempts, 1);
  assert.equal([...repository.quota.values()][0]?.successes, 0);
  assert.equal([...repository.quota.values()][0]?.reservations.size, 0);
});

void test("fails closed for withdrawn consent and unsupported reminder context", async () => {
  const withdrawn = subject("FREE", {
    consent: {
      check: () => Promise.resolve({ authorized: false, reason: "REVOKED" }),
    },
  });
  const withdrawnConversation = await create(withdrawn.service);
  await assert.rejects(
    () =>
      withdrawn.service.send(
        request({ message: "Xin chào" }),
        withdrawnConversation.conversationId,
      ),
    (error: unknown) =>
      error instanceof HttpException && error.getStatus() === 403,
  );

  const reminder = subject("FREE", {
    context: {
      assemble: () =>
        Promise.reject(
          new HttpException({ code: "REMINDER_CONTEXT_UNAVAILABLE" }, 409),
        ),
    },
  });
  const reminderConversation = await create(reminder.service);
  await assert.rejects(
    () =>
      reminder.service.send(
        request({
          message: "Nhắc tôi",
          context: {
            journalIds: [],
            includeCurrentSupportPlan: false,
            includeReminderContext: true,
          },
        }),
        reminderConversation.conversationId,
      ),
    (error: unknown) =>
      error instanceof HttpException && error.getStatus() === 409,
  );
});

void test("prevents a concurrent quota race from delivering more than five FREE answers", async () => {
  const { repository, service } = subject("FREE");
  const conversation = await create(service);
  const outcomes = await Promise.allSettled(
    Array.from({ length: 8 }, (_, index) =>
      service.send(
        request(
          { message: `Concurrent ${String(index)}` },
          `chat-concurrent-key-${String(index).padStart(4, "0")}`,
        ),
        conversation.conversationId,
      ),
    ),
  );
  assert.equal(
    outcomes.filter((result) => result.status === "fulfilled").length,
    5,
  );
  assert.equal([...repository.quota.values()][0]?.successes, 5);
});

void test("does not persist raw context or provider hidden reasoning metadata", async () => {
  const { repository, service } = subject();
  const conversation = await create(service);
  await service.send(
    request({ message: "Dùng bối cảnh" }),
    conversation.conversationId,
  );
  const stored = JSON.stringify(repository);
  assert.equal(stored.includes("Approved current plan summary"), false);
  assert.equal(stored.toLowerCase().includes("chain-of-thought"), false);
  const publicRow = await service.get(
    request({}, "unused"),
    conversation.conversationId,
  );
  assert.deepEqual(publicRow.messages[1]?.contextKinds, ["SUPPORT_PLAN"]);
  assert.ok(new ChatCrypto(configuration));
});
