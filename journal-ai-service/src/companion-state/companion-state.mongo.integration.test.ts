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

const require = createRequire(import.meta.url);
const migration = require("../../migrations/026_companion_states.cjs") as {
  up(db: unknown): Promise<void>;
  down(db: unknown): Promise<void>;
};

void test("persists owner-isolated encrypted companion state with real MongoDB", async () => {
  const mongo = await MongoMemoryReplSet.create({ replSet: { count: 1 } });
  const uri = mongo.getUri();
  const client = new MongoClient(uri);
  const databaseName = "companion_state_integration";
  const database = client.db(databaseName);
  await migration.up(database);
  const indexes = await database.collection("companion_states").indexes();
  assert.ok(
    indexes.some(
      (index) =>
        index.name === "companion_states_owner_unique_idx" && index.unique,
    ),
  );
  await assert.rejects(
    database
      .collection("companion_states")
      .insertOne({ ownerAccountId: "11111111-1111-4111-8111-111111111111" }),
  );

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
    IDENTITY_JWT_KEY_ID: "companion-state-integration-key",
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
    ANALYSIS_POLL_INTERVAL_MS: 250,
    ANALYSIS_LEASE_MS: 35_000,
    CHAT_RETENTION_DAYS: 90,
    CHAT_FREE_DAILY_ANSWERS: 5,
    CHAT_PLUS_DAILY_ANSWERS: 30,
    CHAT_PREMIUM_FAIR_USE_DAILY_ANSWERS: 200,
    CHAT_RATE_LIMIT_PER_MINUTE: 10,
    CHAT_FREE_DAILY_TOKEN_BUDGET: 100_000,
    CHAT_PLUS_DAILY_TOKEN_BUDGET: 250_000,
    CHAT_PREMIUM_DAILY_TOKEN_BUDGET: 600_000,
    CHAT_DEFAULT_TIMEZONE: "Asia/Ho_Chi_Minh",
    CHAT_ROUTING_POLICY_VERSION: "companion-chat-routing-v1",
  };
  const tokenFor = (accountId: string) =>
    new SignJWT({ roles: ["USER"] })
      .setProtectedHeader({
        alg: "RS256",
        kid: "companion-state-integration-key",
      })
      .setIssuer(configuration.IDENTITY_JWT_ISSUER)
      .setAudience(configuration.IDENTITY_JWT_AUDIENCE)
      .setSubject(accountId)
      .setIssuedAt()
      .setNotBefore("0s")
      .setExpirationTime("5m")
      .setJti(`companion-state-${accountId}`)
      .sign(privateKey);
  const ownerId = "11111111-1111-4111-8111-111111111111";
  const otherOwnerId = "22222222-2222-4222-8222-222222222222";
  const racerId = "33333333-3333-4333-8333-333333333333";
  const [ownerToken, otherToken, racerToken] = await Promise.all([
    tokenFor(ownerId),
    tokenFor(otherOwnerId),
    tokenFor(racerId),
  ]);
  const app = await createApplication(configuration, {
    readinessProbe: { check: () => Promise.resolve() },
    companionState: {
      clock: { now: () => new Date("2026-11-27T10:00:00.000Z") },
    },
  });
  await app.init();
  const server = app.getHttpServer() as unknown as Server;
  const path = "/api/v1/companion-state";

  try {
    await request(server).get(path).expect(401);
    const empty = await request(server)
      .get(path)
      .set("Authorization", `Bearer ${ownerToken}`)
      .expect(200);
    assert.deepEqual(empty.body, {
      focus: null,
      chapters: [],
      commitments: [],
      ui: { guide: null, seen: [] },
      revision: 0,
    });

    await request(server)
      .patch(path)
      .set("Authorization", `Bearer ${ownerToken}`)
      .send({
        focus: { goal: "LONELINESS", chosenOn: "2026-11-27" },
        chapter: { startedOn: "2026-11-27", goal: "LONELINESS" },
        seenAdd: ["guide:intro"],
      })
      .expect(200);
    const merged = await request(server)
      .patch(path)
      .set("Authorization", `Bearer ${ownerToken}`)
      .send({
        chapter: { startedOn: "2026-11-27", connected: "NOT_YET" },
        guide: '{"step":1}',
      })
      .expect(200);
    const mergedBody = merged.body as {
      revision: number;
      chapters: Record<string, unknown>[];
      ui: { guide: string | null; seen: string[] };
    };
    assert.equal(mergedBody.revision, 2);
    assert.deepEqual(mergedBody.chapters, [
      {
        startedOn: "2026-11-27",
        goal: "LONELINESS",
        connected: "NOT_YET",
        updatedAt: "2026-11-27T10:00:00.000Z",
      },
    ]);
    assert.deepEqual(mergedBody.ui, {
      guide: '{"step":1}',
      seen: ["guide:intro"],
    });
    const invalid = await request(server)
      .patch(path)
      .set("Authorization", `Bearer ${ownerToken}`)
      .send({})
      .expect(400);
    assert.equal((invalid.body as { code: string }).code, "VALIDATION_FAILED");

    const other = await request(server)
      .get(path)
      .set("Authorization", `Bearer ${otherToken}`)
      .expect(200);
    assert.equal((other.body as { revision: number }).revision, 0);

    const commitmentBody = {
      kind: "CONNECT_PERSON",
      minutes: 5,
      dueOn: "2026-11-28",
      source: "CHAT",
    };
    const created = [];
    for (let index = 0; index < 3; index += 1)
      created.push(
        (
          await request(server)
            .post(`${path}/commitments`)
            .set("Authorization", `Bearer ${ownerToken}`)
            .send(commitmentBody)
            .expect(201)
        ).body as { id: string; status: string },
      );
    const limit = await request(server)
      .post(`${path}/commitments`)
      .set("Authorization", `Bearer ${ownerToken}`)
      .send(commitmentBody)
      .expect(409);
    assert.equal((limit.body as { code: string }).code, "COMMITMENT_LIMIT");
    const first = created[0];
    assert.ok(first);
    await request(server)
      .patch(`${path}/commitments/${first.id}`)
      .set("Authorization", `Bearer ${otherToken}`)
      .send({ status: "DONE" })
      .expect(404);
    const done = await request(server)
      .patch(`${path}/commitments/${first.id}`)
      .set("Authorization", `Bearer ${ownerToken}`)
      .send({ status: "DONE" })
      .expect(200);
    assert.equal((done.body as { status: string }).status, "DONE");
    const resolved = await request(server)
      .patch(`${path}/commitments/${first.id}`)
      .set("Authorization", `Bearer ${ownerToken}`)
      .send({ status: "SWAPPED" })
      .expect(409);
    assert.equal(
      (resolved.body as { code: string }).code,
      "COMMITMENT_RESOLVED",
    );

    const raw = await database
      .collection("companion_states")
      .find({})
      .toArray();
    assert.equal(raw.length, 1);
    const serialized = JSON.stringify(raw);
    for (const plaintext of [
      "LONELINESS",
      "CONNECT_PERSON",
      "guide:intro",
      "step",
    ])
      assert.equal(serialized.includes(plaintext), false);
    assert.equal(raw[0]?.revision, 6);

    const raced = await Promise.all(
      ["a", "b", "c"].map((key) =>
        request(server)
          .patch(path)
          .set("Authorization", `Bearer ${racerToken}`)
          .send({ seenAdd: [`race:${key}`] }),
      ),
    );
    for (const response of raced) {
      assert.ok([200, 409].includes(response.status));
      if (response.status === 409)
        assert.equal((response.body as { code: string }).code, "CONFLICT");
    }
    const succeeded = raced.filter((response) => response.status === 200);
    assert.ok(succeeded.length >= 1);
    const racedState = await request(server)
      .get(path)
      .set("Authorization", `Bearer ${racerToken}`)
      .expect(200);
    const racedBody = racedState.body as {
      revision: number;
      ui: { seen: string[] };
    };
    assert.equal(racedBody.revision, succeeded.length);
    assert.equal(racedBody.ui.seen.length, succeeded.length);
    assert.equal(
      await database
        .collection("companion_states")
        .countDocuments({ ownerAccountId: racerId }),
      1,
    );

    await assert.rejects(migration.down(database), /Cannot remove/u);
  } finally {
    await app.close();
    await client.close();
    await mongo.stop();
  }
});
