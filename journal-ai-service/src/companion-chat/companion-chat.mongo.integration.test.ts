import assert from "node:assert/strict";
import test from "node:test";
import type { Server } from "node:http";
import { createRequire } from "node:module";
import { exportSPKI, generateKeyPair, SignJWT } from "jose";
import { MongoClient } from "mongodb";
import { MongoMemoryReplSet } from "mongodb-memory-server";
import request from "supertest";

import { createApplication } from "../application.js";
import type { ServiceConfiguration } from "../configuration/configuration.js";
import type { ServicePlan } from "../model-routing/model-routing.js";

const require = createRequire(import.meta.url);
const migration =
  require("../../migrations/010_ai_companion_chat_quotas.cjs") as {
    up(database: unknown): Promise<void>;
  };

void test("persists encrypted quota-governed AI Companion conversations with real MongoDB", async () => {
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
  await migration.up(database);

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
    CARE_TIMEOUT_MS: 100,
    CONSULTATION_BASE_URL: "http://localhost:8082",
    CONSULTATION_TIMEOUT_MS: 100,
    PROVIDER_MODE: "DETERMINISTIC_FAKE",
    ROUTING_POLICY_VERSION: "exact-revision-routing-v1",
    PROVIDER_APPROVAL_VERSION: null,
    FREE_PLUS_ROUTE: null,
    PREMIUM_ROUTE: null,
    GEMINI_BASE_URL: "https://generativelanguage.googleapis.com",
    GEMINI_API_KEY: null,
    OPENAI_BASE_URL: "https://api.openai.com",
    OPENAI_API_KEY: null,
    PROVIDER_TIMEOUT_MS: 30_000,
    BENCHMARK_ENABLED: false,
    BENCHMARK_DATASET_PATH:
      "benchmarks/datasets/exact-revision-synthetic-v1.json",
    BENCHMARK_GEMINI_ROUTE: null,
    BENCHMARK_OPENAI_ROUTE: null,
    ANALYSIS_ENABLED: false,
    ANALYSIS_POLL_INTERVAL_MS: 250,
    ANALYSIS_LEASE_MS: 35_000,
    CHAT_RETENTION_DAYS: 90,
    CHAT_FREE_DAILY_ANSWERS: 5,
    CHAT_PLUS_DAILY_ANSWERS: 7,
    CHAT_PREMIUM_FAIR_USE_DAILY_ANSWERS: 9,
    CHAT_RATE_LIMIT_PER_MINUTE: 20,
    CHAT_DAILY_TOKEN_BUDGET: 100_000,
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
  let providerFails = false;
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
        assemble: () =>
          Promise.resolve({
            kinds: ["SUPPORT_PLAN"],
            prompt: "Synthetic minimized plan context",
          }),
      },
      provider: {
        reply: () =>
          providerFails
            ? Promise.reject(new Error("synthetic provider timeout"))
            : Promise.resolve({
                message: "Phản hồi tổng hợp được giới hạn.",
                inputTokens: 20,
                outputTokens: 10,
              }),
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
    configuration.CHAT_DAILY_TOKEN_BUDGET = 60;
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
  } finally {
    await app.close();
    if (externalUri) await database.dropDatabase();
    await client.close();
    await mongo?.stop();
  }
});
