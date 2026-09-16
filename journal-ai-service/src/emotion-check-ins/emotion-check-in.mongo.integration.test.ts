import assert from "node:assert/strict";
import test from "node:test";
import type { Server } from "node:http";
import { createRequire } from "node:module";
import { exportSPKI, generateKeyPair, SignJWT } from "jose";
import { MongoClient } from "mongodb";
import { MongoMemoryServer } from "mongodb-memory-server";
import request from "supertest";

import type { ConsentDecision } from "../analysis/analysis.js";
import { createApplication } from "../application.js";
import type { ServiceConfiguration } from "../configuration/configuration.js";

const require = createRequire(import.meta.url);
const migration =
  require("../../migrations/006_daily_emotion_check_ins.cjs") as {
    up(db: unknown): Promise<void>;
  };

void test("persists owner-isolated daily emotion check-ins with real MongoDB", async () => {
  const externalUri = process.env.JOURNAL_INTEGRATION_MONGODB_URI;
  const mongo = externalUri ? undefined : await MongoMemoryServer.create();
  const uri = externalUri ?? mongo?.getUri();
  assert.ok(uri);
  const client = new MongoClient(uri);
  const databaseName = externalUri
    ? `emotion_check_in_verify_${String(Date.now())}`
    : "emotion_check_in_integration";
  assert.match(
    databaseName,
    /^(?:emotion_check_in_integration|emotion_check_in_verify_\d+)$/,
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
    JOURNAL_ENCRYPTION_KEY: Buffer.alloc(32, 41),
    JOURNAL_ENCRYPTION_KEY_ID: "emotion-integration-v1",
    JOURNAL_IDEMPOTENCY_HMAC_KEY: Buffer.alloc(32, 42),
    IDENTITY_JWT_ISSUER: "https://identity.test.mentalbridge",
    IDENTITY_JWT_AUDIENCE: "mentalbridge-api",
    IDENTITY_JWT_KEY_ID: "emotion-integration-key",
    IDENTITY_JWT_PUBLIC_KEY: await exportSPKI(publicKey),
    CARE_BASE_URL: "http://localhost:8081",
    CARE_TIMEOUT_MS: 100,
    ANALYSIS_ENABLED: false,
    ANALYSIS_POLL_INTERVAL_MS: 250,
    ANALYSIS_LEASE_MS: 35_000,
  };
  const tokenFor = (accountId: string) =>
    new SignJWT({ roles: ["USER"] })
      .setProtectedHeader({ alg: "RS256", kid: "emotion-integration-key" })
      .setIssuer(configuration.IDENTITY_JWT_ISSUER)
      .setAudience(configuration.IDENTITY_JWT_AUDIENCE)
      .setSubject(accountId)
      .setIssuedAt()
      .setNotBefore("0s")
      .setExpirationTime("5m")
      .setJti(`emotion-${accountId}`)
      .sign(privateKey);
  const ownerId = "11111111-1111-4111-8111-111111111111";
  const otherOwnerId = "22222222-2222-4222-8222-222222222222";
  const [ownerToken, otherToken] = await Promise.all([
    tokenFor(ownerId),
    tokenFor(otherOwnerId),
  ]);
  let consent: ConsentDecision = {
    authorized: true,
    reason: "GRANTED",
    policyVersion: "ai-processing-capstone-v1",
  };
  let consentFails = false;
  const app = await createApplication(configuration, {
    readinessProbe: { check: () => Promise.resolve() },
    emotionCheckIns: {
      clock: { now: () => new Date("2026-09-16T17:30:00.000Z") },
      consentClient: {
        check: () =>
          consentFails
            ? Promise.reject(new Error("synthetic Care outage"))
            : Promise.resolve(consent),
      },
    },
  });
  await app.init();
  const server = app.getHttpServer() as unknown as Server;
  const body = {
    localDate: "2026-09-17",
    timezone: "Asia/Ho_Chi_Minh",
    emotion: "GOOD",
    intensity: 4,
    note: "synthetic private integration note",
  };

  try {
    const created = await request(server)
      .post("/api/v1/emotion-check-ins")
      .set("Authorization", `Bearer ${ownerToken}`)
      .set("Idempotency-Key", "emotion-integration-create")
      .send(body)
      .expect(201);
    const createdBody = created.body as {
      sourceLabel: string;
      note: string | null;
    };
    assert.equal(createdBody.sourceLabel, "SELF_REPORTED_EMOTION");
    assert.equal(createdBody.note, body.note);
    assert.equal(
      String(created.headers.location),
      "/api/v1/emotion-check-ins/2026-09-17",
    );

    const replay = await request(server)
      .post("/api/v1/emotion-check-ins")
      .set("Authorization", `Bearer ${ownerToken}`)
      .set("Idempotency-Key", "emotion-integration-create")
      .send(body)
      .expect(201);
    assert.deepEqual(replay.body, created.body);
    assert.equal(
      await database.collection("emotion_check_ins").countDocuments(),
      1,
    );
    const raw = await database.collection("emotion_check_ins").findOne({});
    assert.ok(raw);
    assert.equal(JSON.stringify(raw).includes(body.note), false);
    assert.equal(JSON.stringify(raw).includes(body.emotion), false);

    await request(server)
      .get("/api/v1/emotion-check-ins/2026-09-17")
      .set("Authorization", `Bearer ${otherToken}`)
      .expect(404);

    const updates = await Promise.all([
      request(server)
        .patch("/api/v1/emotion-check-ins/2026-09-17")
        .set("Authorization", `Bearer ${ownerToken}`)
        .set("Idempotency-Key", "emotion-integration-update-a")
        .set("If-Match-Revision", "1")
        .send({ emotion: "LOW", intensity: 3, note: null }),
      request(server)
        .patch("/api/v1/emotion-check-ins/2026-09-17")
        .set("Authorization", `Bearer ${ownerToken}`)
        .set("Idempotency-Key", "emotion-integration-update-b")
        .set("If-Match-Revision", "1")
        .send({ emotion: "GREAT", intensity: 5, note: null }),
    ]);
    assert.deepEqual(
      updates.map((response) => response.status).sort(),
      [200, 412],
    );

    const history = await request(server)
      .get("/api/v1/emotion-check-ins?limit=30")
      .set("Authorization", `Bearer ${ownerToken}`)
      .expect(200);
    const historyBody = history.body as { label: string; items: unknown[] };
    assert.equal(historyBody.label, "SELF_REPORTED_EMOTION");
    assert.equal(historyBody.items.length, 1);

    const context = await request(server)
      .get("/api/v1/emotion-check-in-context?purpose=AI_REFLECTION")
      .set("Authorization", `Bearer ${ownerToken}`)
      .expect(200);
    const contextBody = context.body as {
      items: Record<string, unknown>[];
    };
    assert.equal(contextBody.items.length, 1);
    const contextItem = contextBody.items[0];
    assert.ok(contextItem);
    assert.equal("note" in contextItem, false);
    assert.equal("id" in contextItem, false);

    consent = { authorized: false, reason: "REVOKED" };
    await request(server)
      .get("/api/v1/emotion-check-in-context?purpose=AI_REFLECTION")
      .set("Authorization", `Bearer ${ownerToken}`)
      .expect(403);
    consentFails = true;
    await request(server)
      .get("/api/v1/emotion-check-in-context?purpose=AI_REFLECTION")
      .set("Authorization", `Bearer ${ownerToken}`)
      .expect(503);

    const deleted = await request(server)
      .delete("/api/v1/emotion-check-ins/2026-09-17")
      .set("Authorization", `Bearer ${ownerToken}`)
      .set("Idempotency-Key", "emotion-integration-delete")
      .expect(200);
    const deletedBody = deleted.body as { deleted: boolean };
    assert.equal(deletedBody.deleted, true);
    await request(server)
      .delete("/api/v1/emotion-check-ins/2026-09-17")
      .set("Authorization", `Bearer ${ownerToken}`)
      .set("Idempotency-Key", "emotion-integration-delete")
      .expect(200);
    const tombstone = await database
      .collection<{ revisions: unknown[] }>("emotion_check_ins")
      .findOne({ ownerAccountId: ownerId });
    assert.ok(tombstone);
    assert.deepEqual(tombstone.revisions, []);
    await request(server)
      .get("/api/v1/emotion-check-ins/2026-09-17")
      .set("Authorization", `Bearer ${ownerToken}`)
      .expect(404);
  } finally {
    await app.close();
    if (externalUri) await database.dropDatabase();
    await client.close();
    await mongo?.stop();
  }
});
