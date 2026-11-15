import assert from "node:assert/strict";
import test from "node:test";
import type { Server } from "node:http";
import { createRequire } from "node:module";
import { exportSPKI, generateKeyPair, SignJWT } from "jose";
import { BSON, MongoClient } from "mongodb";
import { MongoMemoryReplSet } from "mongodb-memory-server";
import request from "supertest";

import { createApplication } from "../application.js";
import { aiConsentAuthorizationsFixture } from "../analysis/consent.fixture.js";
import { ConsentUnavailableError } from "../analysis/analysis.js";
import {
  ChatCrypto,
  MongoChatRepository,
  type ChatProvider,
  type Conversation,
} from "./companion-chat.js";
import type { ServiceConfiguration } from "../configuration/configuration.js";
import type { ServicePlan } from "../model-routing/model-routing.js";

const require = createRequire(import.meta.url);
const migrations = [
  require("../../migrations/010_ai_companion_chat_quotas.cjs"),
  require("../../migrations/012_ai_companion_conversation_context.cjs"),
  require("../../migrations/017_companion_message_suggestion.cjs"),
  require("../../migrations/018_companion_conversation_title.cjs"),
] as { up(database: unknown): Promise<void> }[];

void test("persists encrypted quota-governed AI Companion conversations with real MongoDB", async (t) => {
  const externalUri = process.env.JOURNAL_INTEGRATION_MONGODB_URI;
  const mongo = externalUri
    ? undefined
    : await MongoMemoryReplSet.create({ replSet: { count: 1 } });
  const uri = externalUri ?? mongo?.getUri();
  assert.ok(uri);
  const client = new MongoClient(uri);
  const databaseName = externalUri
    ? `companion_chat_verify_${String(Date.now())}`
    : "companion_chat_integration";
  assert.match(
    databaseName,
    /^(?:companion_chat_integration|companion_chat_verify_\d+)$/,
  );
  const database = client.db(databaseName);
  assert.equal((await database.listCollections().toArray()).length, 0);
  for (const migration of migrations) await migration.up(database);

  const { privateKey, publicKey } = await generateKeyPair("RS256", {
    extractable: true,
  });
  const configuration: ServiceConfiguration = {
    NODE_ENV: "test",
    PORT: 3000,
    LOG_LEVEL: "silent",
    SERVICE_NAME: "journal-ai-service",
    MONGODB_URI: uri,
    MONGODB_DATABASE: databaseName,
    MONGODB_CONNECTION_TIMEOUT_MS: 2_000,
    JOURNAL_ENCRYPTION_KEY: Buffer.alloc(32, 61),
    JOURNAL_ENCRYPTION_KEY_ID: "single-key",
    JOURNAL_IDEMPOTENCY_HMAC_KEY: Buffer.alloc(32, 62),
    IDENTITY_JWT_ISSUER: "https://identity.test.mentalbridge",
    IDENTITY_JWT_AUDIENCE: "mentalbridge-api",
    IDENTITY_JWT_KEY_ID: "companion-integration-key",
    IDENTITY_JWT_PUBLIC_KEY: await exportSPKI(publicKey),
    CARE_BASE_URL: "http://localhost:8081",
    CONTENT_BASE_URL: "http://localhost:3003",
    CONTENT_TIMEOUT_MS: 2_000,
    CARE_TIMEOUT_MS: 100,
    CONSULTATION_BASE_URL: "http://localhost:8082",
    CONSULTATION_TIMEOUT_MS: 100,
    REMINDER_SERVICE_TOKEN: null,
    SUPPORT_PLAN_CARE_SERVICE_TOKEN: null,
    PROVIDER_MODE: "DETERMINISTIC_FAKE",
    ROUTING_POLICY_VERSION: "exact-revision-routing-v1",
    PROVIDER_APPROVAL_VERSION: null,
    FREE_PLUS_ROUTE: null,
    PREMIUM_ROUTE: null,
    GEMINI_BASE_URL: "https://generativelanguage.googleapis.com",
    GEMINI_API_KEY: null,
    OPENAI_BASE_URL: "https://api.openai.com",
    OPENAI_API_KEY: null,
    BEDROCK_REGION: "ap-southeast-1",
    BEDROCK_API_KEY: null,
    PROVIDER_TIMEOUT_MS: 30_000,
    BEDROCK_SCHEMA_WARMUP_TIMEOUT_MS: 300_000,
    BENCHMARK_ENABLED: false,
    BENCHMARK_DATASET_PATH:
      "benchmarks/datasets/exact-revision-synthetic-v1.json",
    BENCHMARK_GEMINI_ROUTE: null,
    BENCHMARK_OPENAI_ROUTE: null,
    BENCHMARK_BEDROCK_ROUTE: null,
    ANALYSIS_ENABLED: false,
    ANALYSIS_POLL_INTERVAL_MS: 250,
    ANALYSIS_LEASE_MS: 35_000,
    CHAT_RETENTION_DAYS: 90,
    CHAT_FREE_DAILY_ANSWERS: 5,
    CHAT_PLUS_DAILY_ANSWERS: 7,
    CHAT_PREMIUM_FAIR_USE_DAILY_ANSWERS: 9,
    CHAT_RATE_LIMIT_PER_MINUTE: 20,
    CHAT_FREE_DAILY_TOKEN_BUDGET: 100_000,
    CHAT_PLUS_DAILY_TOKEN_BUDGET: 250_000,
    CHAT_PREMIUM_DAILY_TOKEN_BUDGET: 600_000,
    CHAT_DEFAULT_TIMEZONE: "Asia/Ho_Chi_Minh",
    CHAT_ROUTING_POLICY_VERSION: "companion-chat-routing-v1",
  };
  const ownerId = "11111111-1111-4111-8111-111111111111";
  const otherOwnerId = "22222222-2222-4222-8222-222222222222";
  const tokenFor = (accountId: string) =>
    new SignJWT({ roles: ["USER"] })
      .setProtectedHeader({
        alg: "RS256",
        kid: configuration.IDENTITY_JWT_KEY_ID,
      })
      .setIssuer(configuration.IDENTITY_JWT_ISSUER)
      .setAudience(configuration.IDENTITY_JWT_AUDIENCE)
      .setSubject(accountId)
      .setIssuedAt()
      .setNotBefore("0s")
      .setExpirationTime("5m")
      .setJti(`companion-${accountId}`)
      .sign(privateKey);
  const [ownerToken, otherToken] = await Promise.all([
    tokenFor(ownerId),
    tokenFor(otherOwnerId),
  ]);
  let now = new Date("2026-09-20T16:58:00.000Z");
  let plan: ServicePlan = "FREE";
  let consentAuthorized = true;
  let contextAuthorized = true;
  let consentUnavailable = false;
  let assemblyCalls = 0;
  let afterAssembly: () => void = () => undefined;
  const providerInputs: Parameters<ChatProvider["reply"]>[] = [];
  let providerFails = false;
  let replyOutputTokens = 10;
  let rendezvous: (() => void)[] | null = null;
  let titleForMessage: ((message: string) => string) | null = null;
  let pauseProvider: (() => Promise<void>) | null = null;
  const app = await createApplication(configuration, {
    readinessProbe: { check: () => Promise.resolve() },
    companionChat: {
      clock: { now: () => new Date(now) },
      consentClient: {
        check: () =>
          Promise.resolve({
            authorized: consentAuthorized,
            reason: consentAuthorized ? "GRANTED" : "REVOKED",
          }),
        snapshot: () =>
          consentUnavailable
            ? Promise.reject(new ConsentUnavailableError())
            : Promise.resolve(
                aiConsentAuthorizationsFixture(
                  {
                    authorized: consentAuthorized && contextAuthorized,
                    reason: !consentAuthorized
                      ? "DEPENDENCY_REQUIRED"
                      : contextAuthorized
                        ? "GRANTED"
                        : "REVOKED",
                  },
                  {
                    authorized: consentAuthorized,
                    reason: consentAuthorized ? "GRANTED" : "REVOKED",
                  },
                ),
              ),
      },
      entitlementClient: {
        current: () =>
          Promise.resolve({
            packageCode: plan,
            source: plan === "FREE" ? "DEFAULT_FREE" : "PAID",
            policyVersion: "service-entitlement-v1",
            version: 1,
          }),
      },
      contextAssembler: {
        assemble: () => {
          assemblyCalls++;
          afterAssembly();
          return Promise.resolve({
            kinds: ["SUPPORT_PLAN"],
            prompt: "Synthetic minimized plan context",
          });
        },
      },
      provider: {
        reply: async (...input) => {
          providerInputs.push([
            input[0],
            structuredClone(input[1]),
            structuredClone(input[2]),
            {
              ...(input[3]?.friend ? { friend: input[3].friend } : {}),
              ...(input[3]?.requestTitle ? { requestTitle: true } : {}),
            },
          ]);
          if (providerFails) throw new Error("synthetic provider timeout");
          // Holds concurrent sends inside provider work until both arrive.
          const waiting = rendezvous;
          if (waiting)
            await new Promise<void>((release) => {
              waiting.push(release);
              if (waiting.length === 2) for (const resume of waiting) resume();
            });
          if (pauseProvider) await pauseProvider();
          return {
            message: "Phản hồi tổng hợp được giới hạn.",
            inputTokens: 20,
            outputTokens: replyOutputTokens,
            suggestion: { destination: "RESOURCE", resourceRef: "R1" },
            ...(titleForMessage ? { title: titleForMessage(input[0]) } : {}),
          };
        },
      },
      catalog: {
        items: () =>
          Promise.resolve([
            {
              ref: "R1",
              resourceId: "33333333-3333-4333-8333-333333333333",
              category: "BREATHING",
              title: "Thở 4-7-8",
            },
          ]),
      },
    },
  });
  await app.init();
  const server = app.getHttpServer() as unknown as Server;
  const authorization = { Authorization: `Bearer ${ownerToken}` };

  try {
    const created = await request(server)
      .post("/api/v1/ai-companion/conversations")
      .set(authorization)
      .send({})
      .expect(201);
    const createdBody = created.body as unknown as { conversationId: string };
    const conversationId = createdBody.conversationId;
    assert.match(conversationId, /^[0-9a-f-]{36}$/);

    providerFails = true;
    await request(server)
      .post(`/api/v1/ai-companion/conversations/${conversationId}/messages`)
      .set(authorization)
      .set("Idempotency-Key", "companion-provider-failure")
      .send({ message: "This must not consume quota" })
      .expect(503);
    const failedLedger = await database
      .collection<{
        ownerAccountId: string;
        usedTokens: number;
        successfulAnswers: number;
        reservations: string[];
      }>("ai_companion_quota_ledgers")
      .findOne({ ownerAccountId: ownerId });
    assert.ok(failedLedger);
    assert.ok(failedLedger.usedTokens > 0);
    assert.equal(failedLedger.successfulAnswers, 0);
    assert.deepEqual(failedLedger.reservations, []);
    assert.equal(providerInputs.length, 2);
    await request(server)
      .post(`/api/v1/ai-companion/conversations/${conversationId}/messages`)
      .set(authorization)
      .set("Idempotency-Key", "companion-provider-failure")
      .send({ message: "This must not consume quota" })
      .expect(503);
    assert.equal(providerInputs.length, 2);
    assert.equal(
      (
        await database
          .collection<{ usedTokens: number }>("ai_companion_quota_ledgers")
          .findOne({ ownerAccountId: ownerId })
      )?.usedTokens,
      failedLedger.usedTokens,
    );
    providerFails = false;

    const initialSuccess = await request(server)
      .post(`/api/v1/ai-companion/conversations/${conversationId}/messages`)
      .set(authorization)
      .set("Idempotency-Key", "companion-concurrent-0000")
      .send({ message: "Private synthetic message 0" })
      .expect(201);

    const attempts = await Promise.all(
      Array.from({ length: 5 }, (_, offset) => {
        const index = offset + 1;
        return request(server)
          .post(`/api/v1/ai-companion/conversations/${conversationId}/messages`)
          .set(authorization)
          .set(
            "Idempotency-Key",
            `companion-concurrent-${String(index).padStart(4, "0")}`,
          )
          .send({ message: `Private synthetic message ${String(index)}` });
      }),
    );
    assert.deepEqual(
      attempts.map((response) => response.status).sort(),
      [201, 201, 201, 201, 429],
    );
    const initialSuccessBody = initialSuccess.body as unknown as {
      assistantMessageId: string;
      quota: { plan: string };
    };
    assert.equal(initialSuccessBody.quota.plan, "FREE");

    const replay = await request(server)
      .post(`/api/v1/ai-companion/conversations/${conversationId}/messages`)
      .set(authorization)
      .set("Idempotency-Key", "companion-concurrent-0000")
      .send({ message: "Private synthetic message 0" })
      .expect(201);
    const replayBody = replay.body as unknown as { assistantMessageId: string };
    assert.equal(
      replayBody.assistantMessageId,
      initialSuccessBody.assistantMessageId,
    );
    assert.equal(
      (
        await database
          .collection<{ usedTokens: number }>("ai_companion_quota_ledgers")
          .findOne({ ownerAccountId: ownerId })
      )?.usedTokens,
      failedLedger.usedTokens + 5 * 30,
    );

    const stored = await database
      .collection<{ _id: string; ownerAccountId: string; messages: unknown[] }>(
        "ai_companion_conversations",
      )
      .findOne({ _id: conversationId, ownerAccountId: ownerId });
    assert.ok(stored);
    assert.equal(stored.messages.length, 10);
    assert.equal(
      JSON.stringify(stored).includes("Private synthetic message"),
      false,
    );
    assert.equal(
      JSON.stringify(stored).includes("Synthetic minimized plan context"),
      false,
    );
    const history = await request(server)
      .get("/api/v1/ai-companion/conversations")
      .set(authorization)
      .expect(200);
    const historyBody = history.body as unknown as {
      items: Record<string, unknown>[];
    };
    assert.equal(historyBody.items.length, 1);
    const historyItem = historyBody.items[0];
    assert.ok(historyItem);
    assert.equal("messages" in historyItem, false);
    assert.equal(historyItem.conversationId, conversationId);
    await request(server)
      .get(`/api/v1/ai-companion/conversations/${conversationId}`)
      .set("Authorization", `Bearer ${otherToken}`)
      .expect(404);

    consentAuthorized = false;
    const revoked = await request(server)
      .post(`/api/v1/ai-companion/conversations/${conversationId}/messages`)
      .set(authorization)
      .set("Idempotency-Key", "companion-consent-revoked")
      .send({ message: "Blocked before provider" })
      .expect(403);
    const revokedBody = revoked.body as unknown as { code: string };
    assert.equal(revokedBody.code, "AI_CONSENT_REQUIRED");
    await request(server)
      .post(`/api/v1/ai-companion/conversations/${conversationId}/messages`)
      .set(authorization)
      .set("Idempotency-Key", "companion-consent-revoked")
      .send({ message: "Blocked before provider" })
      .expect(403);
    consentAuthorized = true;

    now = new Date("2026-09-20T17:02:00.000Z");
    const reset = await request(server)
      .post(`/api/v1/ai-companion/conversations/${conversationId}/messages`)
      .set(authorization)
      .set("Idempotency-Key", "companion-next-local-day")
      .send({ message: "New local day" })
      .expect(201);
    const resetBody = reset.body as unknown as {
      quota: { remaining: number | null };
    };
    assert.equal(resetBody.quota.remaining, 4);

    plan = "PREMIUM";
    const premiumConversation = await request(server)
      .post("/api/v1/ai-companion/conversations")
      .set("Authorization", `Bearer ${otherToken}`)
      .send({})
      .expect(201);
    const premiumConversationBody = premiumConversation.body as unknown as {
      conversationId: string;
    };
    configuration.CHAT_RATE_LIMIT_PER_MINUTE = 1;
    const rateAttempts = await Promise.all(
      ["0001", "0002"].map((suffix) =>
        request(server)
          .post(
            `/api/v1/ai-companion/conversations/${premiumConversationBody.conversationId}/messages`,
          )
          .set("Authorization", `Bearer ${otherToken}`)
          .set("Idempotency-Key", `companion-premium-rate-${suffix}`)
          .send({ message: `Premium rate ${suffix}` }),
      ),
    );
    assert.deepEqual(
      rateAttempts.map((response) => response.status).sort(),
      [201, 429],
    );
    assert.equal(
      (
        rateAttempts.find((response) => response.status === 429)?.body as {
          code: string;
        }
      ).code,
      "CHAT_RATE_LIMITED",
    );

    now = new Date("2026-09-20T17:03:00.000Z");
    configuration.CHAT_RATE_LIMIT_PER_MINUTE = 20;
    // The pre-call estimate must fit, so the race is decided by the reply's spend.
    replyOutputTokens = 20_000;
    const premiumUsed =
      (
        await database
          .collection<{ ownerAccountId: string; usedTokens: number }>(
            "ai_companion_quota_ledgers",
          )
          .findOne({ ownerAccountId: otherOwnerId })
      )?.usedTokens ?? 0;
    configuration.CHAT_PREMIUM_DAILY_TOKEN_BUDGET = premiumUsed + 25_000;
    rendezvous = [];
    const tokenAttempts = await Promise.all(
      ["0001", "0002"].map((suffix) =>
        request(server)
          .post(
            `/api/v1/ai-companion/conversations/${premiumConversationBody.conversationId}/messages`,
          )
          .set("Authorization", `Bearer ${otherToken}`)
          .set("Idempotency-Key", `companion-premium-token-${suffix}`)
          .send({ message: `Premium token ${suffix}` }),
      ),
    );
    assert.deepEqual(
      tokenAttempts.map((response) => response.status).sort(),
      [201, 429],
    );
    assert.equal(
      (
        tokenAttempts.find((response) => response.status === 429)?.body as {
          code: string;
        }
      ).code,
      "CHAT_TOKEN_BUDGET_EXHAUSTED",
    );
    const premiumBody = tokenAttempts.find(
      (response) => response.status === 201,
    )?.body as unknown as {
      quota: { remaining: number | null; limitDisplayed: boolean };
    };
    assert.equal(premiumBody.quota.remaining, null);
    assert.equal(premiumBody.quota.limitDisplayed, false);
    rendezvous = null;
    const premiumLedger = await database
      .collection<{
        ownerAccountId: string;
        usedTokens: number;
        reservations: string[];
      }>("ai_companion_quota_ledgers")
      .findOne({ ownerAccountId: otherOwnerId });
    assert.equal(premiumLedger?.usedTokens, premiumUsed + 40_040);
    assert.deepEqual(premiumLedger.reservations, []);

    (
      configuration as { COMPANION_GUIDANCE_V2_ENABLED?: boolean }
    ).COMPANION_GUIDANCE_V2_ENABLED = true;
    configuration.CHAT_PREMIUM_DAILY_TOKEN_BUDGET = 600_000;
    replyOutputTokens = 10;
    const friendSend = () =>
      request(server)
        .post(
          `/api/v1/ai-companion/conversations/${premiumConversationBody.conversationId}/messages`,
        )
        .set("Authorization", `Bearer ${otherToken}`)
        .set("Idempotency-Key", "companion-friend-snapshot-0001")
        .send({ message: "Ừ, thử bài thở đó coi" });
    const friendBody = (await friendSend().expect(201)).body as {
      assistantMessageId: string;
      suggestion: unknown;
    };
    assert.deepEqual(friendBody.suggestion, {
      destination: "RESOURCE",
      resourceId: "33333333-3333-4333-8333-333333333333",
      resourceTitle: "Thở 4-7-8",
    });
    const snapshot = await database
      .collection<{ response: { assistantMessageId: string } | null }>(
        "ai_companion_commands",
      )
      .findOne({
        "response.assistantMessageId": friendBody.assistantMessageId,
      });
    assert.ok(snapshot?.response);
    const snapshotText = JSON.stringify(snapshot.response);
    assert.equal(snapshotText.includes("Thở 4-7-8"), false);
    assert.equal(
      snapshotText.includes("33333333-3333-4333-8333-333333333333"),
      false,
    );
    const storedMessage = (
      await database
        .collection<{
          _id: string;
          messages: {
            messageId: string;
            suggestion?: { ciphertext: unknown };
          }[];
        }>("ai_companion_conversations")
        .findOne({ _id: premiumConversationBody.conversationId })
    )?.messages.find(
      (message) => message.messageId === friendBody.assistantMessageId,
    );
    assert.ok(storedMessage?.suggestion?.ciphertext);
    assert.deepEqual((await friendSend().expect(201)).body, friendBody);
    (
      configuration as { COMPANION_GUIDANCE_V2_ENABLED?: boolean }
    ).COMPANION_GUIDANCE_V2_ENABLED = false;

    await request(server)
      .delete(`/api/v1/ai-companion/conversations/${conversationId}`)
      .set(authorization)
      .expect(204);
    assert.equal(
      await database
        .collection<{ _id: string }>("ai_companion_conversations")
        .countDocuments({ _id: conversationId }),
      0,
    );
    assert.equal(
      await database
        .collection<{ conversationId: string }>("ai_companion_commands")
        .countDocuments({ conversationId }),
      0,
    );

    configuration.CHAT_RATE_LIMIT_PER_MINUTE = 20;
    (
      configuration as { COMPANION_GUIDANCE_V2_ENABLED?: boolean }
    ).COMPANION_GUIDANCE_V2_ENABLED = true;
    plan = "FREE";
    now = new Date("2026-10-01T00:00:00Z");
    const crypto = new ChatCrypto(configuration);
    for (const scenario of [
      "l1-only",
      "l2-lost",
      "l1-lost",
      "care-unavailable",
    ] as const) {
      await t.test(
        `MB-806 ${scenario} fences personal payloads and settles real Mongo reservations`,
        async () => {
          now = new Date(now.getTime() + 86_400_000);
          consentAuthorized = true;
          contextAuthorized = scenario !== "l1-only";
          consentUnavailable = false;
          afterAssembly = () => {
            if (scenario === "l2-lost") contextAuthorized = false;
            if (scenario === "l1-lost") consentAuthorized = false;
            if (scenario === "care-unavailable") consentUnavailable = true;
          };
          const created = await request(server)
            .post("/api/v1/ai-companion/conversations")
            .set(authorization)
            .send({})
            .expect(201);
          const id = (created.body as { conversationId: string })
            .conversationId;
          const messages: Conversation["messages"] = [];
          for (const [role, content, kinds] of [
            ["USER", "SYNTHETIC_ORDINARY_TOPIC", []],
            ["ASSISTANT", "SYNTHETIC_ORDINARY_REPLY", []],
            ["USER", "SYNTHETIC_PERSONAL_QUESTION", []],
            ["ASSISTANT", "SYNTHETIC_PERSONAL_REPLY", ["SUPPORT_PLAN"]],
          ] as const) {
            const messageId = `${String(messages.length + 1)}1111111-1111-4111-8111-111111111111`;
            messages.push({
              messageId,
              role,
              content: crypto.encrypt(ownerId, id, messageId, content),
              contextKinds: [...kinds],
              route: null,
              createdAt: now,
            });
          }
          await database
            .collection<Conversation>("ai_companion_conversations")
            .updateOne(
              { _id: id },
              {
                $set: {
                  "context.sources": {
                    plan: true,
                    diary: false,
                    screening: false,
                    resourceIds: [],
                  },
                  messages,
                },
              },
            );
          const historical = await database
            .collection<Conversation>("ai_companion_conversations")
            .findOne({ _id: id });
          assert.ok(historical);
          const callsBefore = providerInputs.length;
          const assemblyBefore = assemblyCalls;
          const denied =
            scenario === "l1-lost" || scenario === "care-unavailable";
          const result = await request(server)
            .post(`/api/v1/ai-companion/conversations/${id}/messages`)
            .set(authorization)
            .set("Idempotency-Key", `mb806-${scenario}-synthetic-message`)
            .send({ message: "SYNTHETIC_CURRENT_MESSAGE" })
            .expect(
              scenario === "l1-lost"
                ? 403
                : scenario === "care-unavailable"
                  ? 503
                  : 201,
            );
          assert.equal(
            assemblyCalls - assemblyBefore,
            scenario === "l1-only" ? 0 : 1,
          );
          assert.equal(providerInputs.length - callsBefore, denied ? 0 : 1);
          if (denied) {
            assert.equal(
              (result.body as { code: string }).code,
              scenario === "l1-lost"
                ? "AI_CONSENT_REQUIRED"
                : "CONSENT_OR_ENTITLEMENT_UNAVAILABLE",
            );
          } else {
            assert.deepEqual(providerInputs.at(-1), [
              "SYNTHETIC_CURRENT_MESSAGE",
              { kinds: [], prompt: "" },
              {
                workload: "COMPANION_CHAT",
                servicePlan: "FREE",
                entitlementSource: "DEFAULT_FREE",
                entitlementPolicyVersion: "service-entitlement-v1",
                entitlementVersion: 1,
                provider: "DETERMINISTIC_FAKE",
                model: "deterministic-companion-v1",
                providerApprovalVersion: "local-deterministic-v1",
                routingPolicyVersion: "companion-chat-routing-v1",
                promptVersion: "companion-chat-v1",
              },
              {
                friend: {
                  history: [
                    { role: "USER", text: "SYNTHETIC_ORDINARY_TOPIC" },
                    { role: "ASSISTANT", text: "SYNTHETIC_ORDINARY_REPLY" },
                  ],
                  catalog: [
                    {
                      ref: "R1",
                      resourceId: "33333333-3333-4333-8333-333333333333",
                      category: "BREATHING",
                      title: "Thở 4-7-8",
                    },
                  ],
                },
              },
            ]);
            assert.deepEqual(
              (result.body as { contextKinds: string[] }).contextKinds,
              [],
            );
          }
          const saved = await database
            .collection<Conversation>("ai_companion_conversations")
            .findOne({ _id: id });
          assert.ok(saved);
          assert.deepEqual(saved.messages.slice(0, 4), historical.messages);
          assert.equal(saved.messages.length, denied ? 4 : 6);
          for (const message of saved.messages.slice(4))
            assert.deepEqual(message.contextKinds, []);
          const ledger = await database
            .collection<{
              ownerAccountId: string;
              localDate: string;
              reservations: string[];
              successfulAnswers: number;
              usedTokens: number;
            }>("ai_companion_quota_ledgers")
            .findOne({
              ownerAccountId: ownerId,
              localDate: now.toISOString().slice(0, 10),
            });
          assert.ok(ledger);
          assert.deepEqual(ledger.reservations, []);
          assert.equal(ledger.successfulAnswers, denied ? 0 : 1);
          assert.equal(ledger.usedTokens, denied ? 0 : 30);
          consentAuthorized = true;
          contextAuthorized = true;
          consentUnavailable = false;
          afterAssembly = () => undefined;
        },
      );
    }
    await t.test(
      "MB-810 title races, encrypted replay and lifecycle use real Mongo transactions",
      async (t) => {
        now = new Date("2026-11-06T00:00:00Z");
        consentAuthorized = true;
        contextAuthorized = true;
        configuration.CHAT_FREE_DAILY_ANSWERS = 50;
        configuration.CHAT_RATE_LIMIT_PER_MINUTE = 50;
        plan = "FREE";
        const conversations = database.collection<Conversation>(
          "ai_companion_conversations",
        );
        const commands = database.collection<{
          conversationId: string;
          response: Record<string, unknown> | null;
          state: string;
          expiresAt: Date;
        }>("ai_companion_commands");
        const crypto = new ChatCrypto(configuration);
        const create = async (title?: string) => {
          const response = await request(server)
            .post("/api/v1/ai-companion/conversations")
            .set(authorization)
            .send(title === undefined ? {} : { title })
            .expect(201);
          return response.body as {
            conversationId: string;
            title: string;
            titleVersion: string;
          };
        };
        const rename = (
          id: string,
          title: string,
          version: string,
          key: string,
        ) =>
          request(server)
            .put(`/api/v1/ai-companion/conversations/${id}/title`)
            .set(authorization)
            .set("If-Match", `"${version}"`)
            .set("Idempotency-Key", key)
            .send({ title });
        const send = (id: string, key: string, message: string) =>
          request(server)
            .post(`/api/v1/ai-companion/conversations/${id}/messages`)
            .set(authorization)
            .set("Idempotency-Key", key)
            .send({ message });
        const stored = async (id: string) => {
          const value = await conversations.findOne({
            _id: id,
            ownerAccountId: ownerId,
          });
          assert.ok(value);
          return value;
        };

        await t.test(
          "two first sends initialize one encrypted title and settle both answers once",
          async () => {
            const created = await create();
            const id = created.conversationId;
            titleForMessage = (message) =>
              `Synthetic topic ${message.slice(-1)}`;
            const callsBefore = providerInputs.length;
            rendezvous = [];
            const results = await Promise.all([
              send(id, "mb810-first-race-send-a", "Synthetic first turn A"),
              send(id, "mb810-first-race-send-b", "Synthetic first turn B"),
            ]);
            rendezvous = null;
            assert.deepEqual(
              results.map((value) => value.status),
              [201, 201],
            );
            assert.equal(providerInputs.length - callsBefore, 2);
            const conversation = await stored(id);
            assert.equal(conversation.messages.length, 4);
            assert.equal(conversation.title, "Cuộc trò chuyện mới");
            assert.ok(conversation.encryptedTitle);
            const title = crypto.decryptTitle(
              ownerId,
              id,
              conversation.encryptedTitle,
            );
            const firstUser = conversation.messages.find(
              (message) => message.role === "USER",
            );
            assert.ok(firstUser);
            assert.equal(
              title,
              titleForMessage(crypto.decrypt(ownerId, id, firstUser)),
            );
            const titleState = BSON.serialize(conversation.encryptedTitle);
            const commandRows = await commands
              .find({ conversationId: id })
              .toArray();
            assert.equal(commandRows.length, 2);
            assert.ok(commandRows.every((row) => row.state === "SUCCEEDED"));
            for (const row of commandRows) {
              assert.ok(row.response);
              assert.equal("title" in row.response, false);
              assert.equal("titleVersion" in row.response, false);
            }
            const ledger = await database
              .collection<{
                successfulAnswers: number;
                reservations: string[];
              }>("ai_companion_quota_ledgers")
              .findOne({ ownerAccountId: ownerId, localDate: "2026-11-06" });
            assert.equal(ledger?.successfulAnswers, 2);
            assert.deepEqual(ledger.reservations, []);
            titleForMessage = () => "Unwanted later title";
            await send(
              id,
              "mb810-later-send-stable",
              "Synthetic later turn",
            ).expect(201);
            const later = await stored(id);
            assert.ok(later.encryptedTitle);
            assert.deepEqual(BSON.serialize(later.encryptedTitle), titleState);
            assert.equal(
              crypto.titleVersion(later),
              crypto.titleVersion(conversation),
            );
            const serialized = JSON.stringify({
              conversation: later,
              commands: await commands.find({ conversationId: id }).toArray(),
            });
            for (const text of [
              "Synthetic topic A",
              "Synthetic topic B",
              "Synthetic first turn",
              "Unwanted later title",
            ])
              assert.equal(serialized.includes(text), false);
          },
        );

        await t.test(
          "manual rename commits while the first send waits and cannot be overwritten",
          async () => {
            now = new Date("2026-11-07T00:00:00Z");
            const created = await create();
            const id = created.conversationId;
            titleForMessage = () => "Discarded automatic synthetic title";
            let entered: () => void = () => undefined;
            let resume: () => void = () => undefined;
            const dispatched = new Promise<void>((resolve) => {
              entered = resolve;
            });
            const released = new Promise<void>((resolve) => {
              resume = resolve;
            });
            pauseProvider = () => {
              entered();
              return released;
            };
            const pending = send(
              id,
              "mb810-send-before-manual-rename",
              "Synthetic held first turn",
            ).then((response) => response);
            await dispatched;
            const before = await stored(id);
            const ledgerBefore = await database
              .collection("ai_companion_quota_ledgers")
              .findOne({ ownerAccountId: ownerId, localDate: "2026-11-07" });
            const callsBefore = providerInputs.length;
            const ratesBefore = await database
              .collection("ai_companion_rate_ledgers")
              .find({ ownerAccountId: ownerId })
              .toArray();
            consentAuthorized = false;
            let renamed;
            try {
              renamed = await rename(
                id,
                "Private manual synthetic title",
                created.titleVersion,
                "mb810-manual-during-first-send",
              ).expect(200);
              assert.equal((await stored(id)).messages.length, 0);
              assert.deepEqual(
                await database
                  .collection("ai_companion_quota_ledgers")
                  .findOne({
                    ownerAccountId: ownerId,
                    localDate: "2026-11-07",
                  }),
                ledgerBefore,
              );
              assert.deepEqual(
                await database
                  .collection("ai_companion_rate_ledgers")
                  .find({ ownerAccountId: ownerId })
                  .toArray(),
                ratesBefore,
              );
            } finally {
              consentAuthorized = true;
              pauseProvider = null;
              resume();
            }
            assert.equal(providerInputs.length, callsBefore);
            const response = await pending;
            assert.equal(response.status, 201);
            const body = renamed.body as {
              title: string;
              titleVersion: string;
            };
            assert.equal(body.title, "Private manual synthetic title");
            assert.equal(
              (response.body as { title: string }).title,
              "Private manual synthetic title",
            );
            const conversation = await stored(id);
            assert.equal(conversation.messages.length, 2);
            assert.deepEqual(conversation.context, before.context);
            assert.equal(
              conversation.expiresAt.getTime(),
              before.expiresAt.getTime(),
            );
            assert.equal(crypto.title(conversation).title, body.title);
            assert.equal(crypto.titleVersion(conversation), body.titleVersion);
            assert.ok(ledgerBefore);
            const serialized = JSON.stringify({
              conversation,
              commands: await commands.find({ conversationId: id }).toArray(),
            });
            assert.equal(
              serialized.includes("Private manual synthetic title"),
              false,
            );
            assert.equal(
              serialized.includes("Discarded automatic synthetic title"),
              false,
            );
          },
        );

        await t.test(
          "auto commit fences stale rename; duplicate replay remains historical after later rename",
          async () => {
            now = new Date("2026-11-08T00:00:00Z");
            const created = await create();
            const id = created.conversationId;
            titleForMessage = () => "Synthetic automatic topic";
            let renameEntered: () => void = () => undefined;
            let resumeRename: () => void = () => undefined;
            const renameDispatched = new Promise<void>((resolve) => {
              renameEntered = resolve;
            });
            const renameReleased = new Promise<void>((resolve) => {
              resumeRename = resolve;
            });
            const originalRename = Reflect.get(
              MongoChatRepository.prototype,
              "renameTitle",
            );
            MongoChatRepository.prototype.renameTitle = async function (
              this: MongoChatRepository,
              ...input: Parameters<MongoChatRepository["renameTitle"]>
            ) {
              if (input[0].conversationId === id) {
                renameEntered();
                await renameReleased;
              }
              return originalRename.call(this, ...input);
            };
            const pendingRename = rename(
              id,
              "Stale synthetic rename",
              created.titleVersion,
              "mb810-stale-after-auto-title",
            ).then((response) => response);
            await renameDispatched;
            let sent;
            try {
              sent = await send(
                id,
                "mb810-send-replay-after-rename",
                "Synthetic replay first turn",
              ).expect(201);
            } finally {
              resumeRename();
              MongoChatRepository.prototype.renameTitle = originalRename;
            }
            const sentBody = sent.body as {
              title: string;
              titleVersion: string;
              assistantMessageId: string;
              quota: unknown;
            };
            const stale = await pendingRename;
            assert.equal(stale.status, 412);
            assert.equal(
              await commands.countDocuments({ conversationId: id }),
              1,
            );
            assert.equal(
              (stale.body as { code: string }).code,
              "CHAT_TITLE_VERSION_CONFLICT",
            );
            const duplicate = await Promise.all([
              rename(
                id,
                "First manual private title",
                sentBody.titleVersion,
                "mb810-duplicate-title-rename",
              ),
              rename(
                id,
                "First manual private title",
                sentBody.titleVersion,
                "mb810-duplicate-title-rename",
              ),
            ]);
            assert.deepEqual(
              duplicate.map((value) => value.status),
              [200, 200],
            );
            assert.deepEqual(duplicate[0].body, duplicate[1].body);
            const first = duplicate[0];
            assert.ok(first);
            const firstBody = first.body as {
              title: string;
              titleVersion: string;
            };
            const second = await rename(
              id,
              "Second manual private title",
              firstBody.titleVersion,
              "mb810-second-title-rename",
            ).expect(200);
            const secondBody = second.body as {
              title: string;
              titleVersion: string;
            };
            const replay = await rename(
              id,
              "First manual private title",
              sentBody.titleVersion,
              "mb810-duplicate-title-rename",
            ).expect(200);
            assert.deepEqual(replay.body, first.body);
            assert.equal(replay.headers.etag, first.headers.etag);
            assert.equal(replay.headers["cache-control"], "no-store");
            const changed = await rename(
              id,
              "Changed private title",
              sentBody.titleVersion,
              "mb810-duplicate-title-rename",
            ).expect(409);
            assert.equal(
              (changed.body as { code: string }).code,
              "IDEMPOTENCY_KEY_REUSED",
            );
            const callsBefore = providerInputs.length;
            const sendReplay = await send(
              id,
              "mb810-send-replay-after-rename",
              "Synthetic replay first turn",
            ).expect(201);
            const replayBody = sendReplay.body as typeof sentBody;
            assert.equal(providerInputs.length, callsBefore);
            assert.equal(
              replayBody.assistantMessageId,
              sentBody.assistantMessageId,
            );
            assert.deepEqual(replayBody.quota, sentBody.quota);
            assert.equal(replayBody.title, secondBody.title);
            assert.equal(replayBody.titleVersion, secondBody.titleVersion);
            assert.equal(
              (await commands.find({ conversationId: id }).toArray()).length,
              3,
            );
            assert.equal(
              crypto.title(await stored(id)).title,
              secondBody.title,
            );
            const serialized = JSON.stringify({
              conversation: await stored(id),
              commands: await commands.find({ conversationId: id }).toArray(),
            });
            for (const title of [
              sentBody.title,
              firstBody.title,
              secondBody.title,
            ])
              assert.equal(serialized.includes(title), false);
          },
        );

        await t.test(
          "different rename keys compete on one title token and cannot cross conversation ownership",
          async () => {
            const created = await create();
            const id = created.conversationId;
            const attempts = await Promise.all([
              rename(
                id,
                "Competing private title A",
                created.titleVersion,
                "mb810-competing-rename-a",
              ),
              rename(
                id,
                "Competing private title B",
                created.titleVersion,
                "mb810-competing-rename-b",
              ),
            ]);
            assert.deepEqual(
              attempts.map((value) => value.status).sort(),
              [200, 412],
            );
            const winningIndex = attempts.findIndex(
              (value) => value.status === 200,
            );
            const winningKey =
              winningIndex === 0
                ? "mb810-competing-rename-a"
                : "mb810-competing-rename-b";
            assert.equal(
              (await commands.find({ conversationId: id }).toArray()).length,
              1,
            );
            const other = await create();
            await rename(
              other.conversationId,
              "Another private title",
              other.titleVersion,
              winningKey,
            ).expect(409);
            await request(server)
              .put(`/api/v1/ai-companion/conversations/${id}/title`)
              .set("Authorization", `Bearer ${otherToken}`)
              .set("If-Match", `"${created.titleVersion}"`)
              .set("Idempotency-Key", "mb810-foreign-title-rename")
              .send({ title: "Foreign private title" })
              .expect(404);
          },
        );

        await t.test(
          "explicit defaults and legacy custom titles survive sends, corrupt envelopes read neutral and remain replaceable",
          async () => {
            now = new Date("2026-11-09T00:00:00Z");
            titleForMessage = () => "Unwanted provider synthetic title";
            const explicit = await create("Cuộc trò chuyện mới");
            const initial = await stored(explicit.conversationId);
            assert.ok(initial.encryptedTitle);
            await send(
              explicit.conversationId,
              "mb810-explicit-default-first",
              "Synthetic explicit first turn",
            ).expect(201);
            assert.equal(
              crypto.titleVersion(await stored(explicit.conversationId)),
              explicit.titleVersion,
            );

            const legacy = await create();
            await conversations.updateOne(
              { _id: legacy.conversationId, ownerAccountId: ownerId },
              { $set: { title: "Legacy custom synthetic title" } },
            );
            const legacyDetail = await request(server)
              .get(
                `/api/v1/ai-companion/conversations/${legacy.conversationId}`,
              )
              .set(authorization)
              .expect(200);
            const legacyBody = legacyDetail.body as {
              title: string;
              titleVersion: string;
            };
            assert.equal(legacyBody.title, "Legacy custom synthetic title");
            await send(
              legacy.conversationId,
              "mb810-legacy-custom-first",
              "Synthetic legacy first turn",
            ).expect(201);
            const persistedLegacy = await stored(legacy.conversationId);
            assert.equal(persistedLegacy.encryptedTitle, undefined);
            assert.equal(
              crypto.titleVersion(persistedLegacy),
              legacyBody.titleVersion,
            );

            await conversations.updateOne(
              { _id: explicit.conversationId, ownerAccountId: ownerId },
              {
                $set: {
                  title: "Sensitive stale legacy title",
                  "encryptedTitle.tag": Buffer.alloc(16, 99),
                },
              },
            );
            const corrupt = await request(server)
              .get(
                `/api/v1/ai-companion/conversations/${explicit.conversationId}`,
              )
              .set(authorization)
              .expect(200);
            const corruptBody = corrupt.body as {
              title: string;
              titleVersion: string;
            };
            assert.equal(corruptBody.title, "Cuộc trò chuyện mới");
            assert.notEqual(corruptBody.titleVersion, explicit.titleVersion);
            const history = await request(server)
              .get("/api/v1/ai-companion/conversations")
              .set(authorization)
              .expect(200);
            const row = (
              history.body as {
                items: { conversationId: string; title: string }[];
              }
            ).items.find(
              (item) => item.conversationId === explicit.conversationId,
            );
            assert.equal(row?.title, "Cuộc trò chuyện mới");
            await rename(
              explicit.conversationId,
              "Recovered private title",
              corruptBody.titleVersion,
              "mb810-replace-corrupt-title",
            ).expect(200);
            const repaired = await stored(explicit.conversationId);
            assert.equal(
              crypto.title(repaired).title,
              "Recovered private title",
            );
            assert.equal(repaired.title, "Cuộc trò chuyện mới");
          },
        );

        await t.test(
          "failed command persistence aborts the title transaction and exact retry can complete",
          async () => {
            const created = await create();
            const id = created.conversationId;
            const before = BSON.serialize(await stored(id));
            const commandMigration =
              require("../../migrations/010_ai_companion_chat_quotas.cjs") as {
                commandValidator: Record<string, unknown>;
              };
            await database.command({
              collMod: "ai_companion_commands",
              validator: {
                $and: [
                  commandMigration.commandValidator,
                  { conversationId: { $ne: id } },
                ],
              },
              validationLevel: "strict",
              validationAction: "error",
            });
            try {
              const failure = await rename(
                id,
                "Transactional private title",
                created.titleVersion,
                "mb810-transaction-failure-retry",
              ).expect(503);
              assert.equal(
                (failure.body as { code: string }).code,
                "CHAT_TITLE_UNAVAILABLE",
              );
              assert.deepEqual(BSON.serialize(await stored(id)), before);
              assert.equal(
                await commands.countDocuments({ conversationId: id }),
                0,
              );
            } finally {
              await database.command({
                collMod: "ai_companion_commands",
                validator: commandMigration.commandValidator,
                validationLevel: "strict",
                validationAction: "error",
              });
            }
            const retry = await rename(
              id,
              "Transactional private title",
              created.titleVersion,
              "mb810-transaction-failure-retry",
            ).expect(200);
            assert.equal(
              (retry.body as { title: string }).title,
              "Transactional private title",
            );
            assert.equal(
              await commands.countDocuments({ conversationId: id }),
              1,
            );
          },
        );

        await t.test(
          "expired or deleted conversations cannot rename or replay and deletion removes encrypted commands",
          async () => {
            const expired = await create();
            await conversations.updateOne(
              { _id: expired.conversationId, ownerAccountId: ownerId },
              { $set: { expiresAt: new Date(now.getTime() - 1) } },
            );
            const expiredResponse = await rename(
              expired.conversationId,
              "Expired private title",
              expired.titleVersion,
              "mb810-expired-title-command",
            ).expect(404);
            assert.equal(
              (expiredResponse.body as { code: string }).code,
              "RESOURCE_NOT_FOUND",
            );
            assert.equal(
              await commands.countDocuments({
                conversationId: expired.conversationId,
              }),
              0,
            );

            const created = await create();
            await rename(
              created.conversationId,
              "Deleted private title",
              created.titleVersion,
              "mb810-deleted-title-command",
            ).expect(200);
            const conversation = await stored(created.conversationId);
            const command = await commands.findOne({
              conversationId: created.conversationId,
            });
            assert.ok(command);
            assert.ok(
              command.expiresAt.getTime() <= conversation.expiresAt.getTime(),
            );
            await request(server)
              .delete(
                `/api/v1/ai-companion/conversations/${created.conversationId}`,
              )
              .set(authorization)
              .expect(204);
            assert.equal(
              await commands.countDocuments({
                conversationId: created.conversationId,
              }),
              0,
            );
            await rename(
              created.conversationId,
              "Deleted private title",
              created.titleVersion,
              "mb810-deleted-title-command",
            ).expect(404);
            assert.equal(
              await conversations.countDocuments({
                _id: created.conversationId,
                ownerAccountId: ownerId,
              }),
              0,
            );
          },
        );

        titleForMessage = null;
      },
    );
  } finally {
    await app.close();
    if (externalUri) await database.dropDatabase();
    await client.close();
    await mongo?.stop();
  }
});

void test("migration 017 accepts an encrypted suggestion, rejects a plaintext one and guards its rollback", async () => {
  const suggestionMigration =
    require("../../migrations/017_companion_message_suggestion.cjs") as {
      up(database: unknown): Promise<void>;
      down(database: unknown): Promise<void>;
    };
  const externalUri = process.env.JOURNAL_INTEGRATION_MONGODB_URI;
  const mongo = externalUri
    ? undefined
    : await MongoMemoryReplSet.create({ replSet: { count: 1 } });
  const uri = externalUri ?? mongo?.getUri();
  assert.ok(uri);
  const client = new MongoClient(uri);
  const database = client.db(
    externalUri
      ? `companion_suggestion_verify_${String(Date.now())}`
      : "companion_suggestion_integration",
  );
  try {
    for (const migration of migrations.slice(0, -1))
      await migration.up(database);
    const envelope = () => ({
      ciphertext: Buffer.from("sealed"),
      iv: Buffer.alloc(12, 1),
      tag: Buffer.alloc(16, 2),
      algorithm: "AES-256-GCM",
      keyId: "single-key",
    });
    const now = new Date("2026-11-03T00:00:00.000Z");
    const conversation = (id: string, suggestion: unknown) => ({
      _id: id,
      ownerAccountId: "11111111-1111-4111-8111-111111111111",
      title: "Trò chuyện",
      context: {
        sources: {
          plan: true,
          diary: false,
          screening: false,
          resourceIds: [],
        },
        updatedAt: now,
      },
      messages: [
        {
          messageId: "44444444-4444-4444-8444-444444444444",
          role: "ASSISTANT",
          content: envelope(),
          createdAt: now,
          route: null,
          contextKinds: [],
          ...(suggestion === undefined ? {} : { suggestion }),
        },
      ],
      createdAt: now,
      updatedAt: now,
      expiresAt: now,
    });
    const conversations = database.collection("ai_companion_conversations");
    await conversations.insertOne(
      conversation("55555555-5555-4555-8555-555555555555", undefined) as never,
    );
    await conversations.insertOne(
      conversation("66666666-6666-4666-8666-666666666666", envelope()) as never,
    );
    await assert.rejects(
      conversations.insertOne(
        conversation("77777777-7777-4777-8777-777777777777", {
          destination: "RESOURCE",
          resourceId: "33333333-3333-4333-8333-333333333333",
          resourceTitle: "Thở 4-7-8",
        }) as never,
      ),
    );
    await assert.rejects(suggestionMigration.down(database), /Cannot remove/u);
    await conversations.deleteOne({
      _id: "66666666-6666-4666-8666-666666666666",
    } as never);
    await suggestionMigration.down(database);
    await assert.rejects(
      conversations.insertOne(
        conversation(
          "88888888-8888-4888-8888-888888888888",
          envelope(),
        ) as never,
      ),
    );
  } finally {
    if (externalUri) await database.dropDatabase();
    await client.close();
    await mongo?.stop();
  }
});

void test("migration 018 changes only the validator, preserves legacy bytes and guards encrypted-title rollback", async () => {
  const titleMigration =
    require("../../migrations/018_companion_conversation_title.cjs") as {
      up(database: unknown): Promise<void>;
      down(database: unknown): Promise<void>;
    };
  const mongo = await MongoMemoryReplSet.create({ replSet: { count: 1 } });
  const client = new MongoClient(mongo.getUri());
  const database = client.db("companion_title_migration_integration");
  try {
    for (const migration of migrations.slice(0, -1))
      await migration.up(database);
    const envelope = () => ({
      ciphertext: Buffer.from("sealed"),
      iv: Buffer.alloc(12, 1),
      tag: Buffer.alloc(16, 2),
      algorithm: "AES-256-GCM",
      keyId: "single-key",
    });
    const now = new Date();
    const conversation = (id: string) => ({
      _id: id,
      ownerAccountId: "11111111-1111-4111-8111-111111111111",
      title: "Legacy private synthetic title",
      context: {
        sources: {
          plan: false,
          diary: false,
          screening: false,
          resourceIds: [],
        },
        updatedAt: now,
      },
      messages: [
        {
          messageId: "44444444-4444-4444-8444-444444444444",
          role: "ASSISTANT",
          content: envelope(),
          suggestion: envelope(),
          createdAt: now,
          route: null,
          contextKinds: [],
        },
      ],
      createdAt: now,
      updatedAt: now,
      expiresAt: new Date(now.getTime() + 86_400_000),
    });
    const conversations = database.collection("ai_companion_conversations");
    const legacyId = "55555555-5555-4555-8555-555555555555";
    const encryptedId = "66666666-6666-4666-8666-666666666666";
    await conversations.insertOne(conversation(legacyId) as never);
    const before = await conversations.findOne({ _id: legacyId } as never);
    assert.ok(before);
    const legacyBytes = BSON.serialize(before);
    const indexes = await conversations.listIndexes().toArray();
    await titleMigration.up(database);
    const after = await conversations.findOne({ _id: legacyId } as never);
    assert.ok(after);
    assert.deepEqual(BSON.serialize(after), legacyBytes);
    assert.deepEqual(await conversations.listIndexes().toArray(), indexes);
    assert.equal("encryptedTitle" in after, false);
    await conversations.insertOne({
      ...conversation(encryptedId),
      encryptedTitle: envelope(),
    } as never);
    for (const encryptedTitle of [
      "Plaintext synthetic title",
      null,
      { ...envelope(), ciphertext: "plaintext" },
      { ...envelope(), iv: undefined },
      { ...envelope(), algorithm: "AES-CBC" },
      { ...envelope(), title: "Unexpected plaintext" },
    ])
      await assert.rejects(
        conversations.insertOne({
          ...conversation("77777777-7777-4777-8777-777777777777"),
          encryptedTitle,
        } as never),
        /validation/u,
      );
    await assert.rejects(titleMigration.down(database), /Cannot remove/u);
    await conversations.deleteOne({ _id: encryptedId } as never);
    await titleMigration.down(database);
    const restored = await conversations.findOne({ _id: legacyId } as never);
    assert.ok(restored);
    assert.deepEqual(BSON.serialize(restored), legacyBytes);
    assert.deepEqual(await conversations.listIndexes().toArray(), indexes);
    await assert.rejects(
      conversations.insertOne({
        ...conversation(encryptedId),
        encryptedTitle: envelope(),
      } as never),
      /validation/u,
    );
    await conversations.insertOne(
      conversation("88888888-8888-4888-8888-888888888888") as never,
    );
  } finally {
    await client.close();
    await mongo.stop();
  }
});
