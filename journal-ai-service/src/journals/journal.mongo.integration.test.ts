import assert from "node:assert/strict";
import test from "node:test";
import type { Server } from "node:http";
import { createRequire } from "node:module";
import { exportSPKI, generateKeyPair, SignJWT } from "jose";
import { MongoClient } from "mongodb";
import { MongoMemoryServer } from "mongodb-memory-server";
import request from "supertest";

import { createApplication } from "../application.js";
import type { ServiceConfiguration } from "../configuration/configuration.js";

const require = createRequire(import.meta.url);
const baseline =
  require("../../migrations/001_journal_entries_baseline.cjs") as {
    up(db: unknown): Promise<void>;
  };
const commands =
  require("../../migrations/002_journal_mutation_commands.cjs") as {
    up(db: unknown): Promise<void>;
  };
const replaySnapshots =
  require("../../migrations/003_journal_replay_snapshots_and_cursor_index.cjs") as {
    up(db: unknown): Promise<void>;
  };
const journalMood =
  require("../../migrations/004_journal_revision_mood.cjs") as {
    up(db: unknown): Promise<void>;
    down(db: unknown): Promise<void>;
  };

void test("persists encrypted owner-isolated CRUD with real MongoDB", async () => {
  const externalUri = process.env.JOURNAL_INTEGRATION_MONGODB_URI;
  const mongo = externalUri ? undefined : await MongoMemoryServer.create();
  const uri = externalUri ?? mongo?.getUri();
  assert.ok(uri);
  const client = new MongoClient(uri);
  const databaseName = externalUri
    ? `mb236_verify_${String(Date.now())}`
    : "journal_mb236_integration";
  assert.match(
    databaseName,
    /^(?:journal_mb236_integration|mb236_verify_\d+)$/,
  );
  const database = client.db(databaseName);
  assert.equal((await database.listCollections().toArray()).length, 0);
  await baseline.up(database);
  await commands.up(database);
  await replaySnapshots.up(database);
  await journalMood.up(database);

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
    JOURNAL_ENCRYPTION_KEY: Buffer.alloc(32, 11),
    JOURNAL_ENCRYPTION_KEY_ID: "single-key",
    JOURNAL_IDEMPOTENCY_HMAC_KEY: Buffer.alloc(32, 12),
    IDENTITY_JWT_ISSUER: "https://identity.test.mentalbridge",
    IDENTITY_JWT_AUDIENCE: "mentalbridge-api",
    IDENTITY_JWT_KEY_ID: "integration-key",
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
  };
  const tokenFor = (accountId: string) =>
    new SignJWT({ roles: ["USER"] })
      .setProtectedHeader({ alg: "RS256", kid: "integration-key" })
      .setIssuer(configuration.IDENTITY_JWT_ISSUER)
      .setAudience(configuration.IDENTITY_JWT_AUDIENCE)
      .setSubject(accountId)
      .setIssuedAt()
      .setNotBefore("0s")
      .setExpirationTime("5m")
      .setJti(`integration-${accountId}`)
      .sign(privateKey);
  const ownerId = "11111111-1111-4111-8111-111111111111";
  const otherOwnerId = "22222222-2222-4222-8222-222222222222";
  const [ownerToken, otherToken] = await Promise.all([
    tokenFor(ownerId),
    tokenFor(otherOwnerId),
  ]);
  const app = await createApplication(configuration, {
    readinessProbe: { check: () => Promise.resolve() },
  });
  await app.init();
  const server = app.getHttpServer() as unknown as Server;

  try {
    const paginationEntries = await Promise.all(
      [1, 2, 3].map((number) =>
        request(server)
          .post("/api/v1/journals")
          .set("Authorization", `Bearer ${ownerToken}`)
          .set(
            "Idempotency-Key",
            `integration-pagination-${String(number).padStart(2, "0")}`,
          )
          .send({
            clientEntryId: `33333333-3333-4333-8333-${String(number).padStart(12, "0")}`,
            occurredAt: "2026-09-11T09:00:00.000Z",
            content: { text: `pagination entry ${String(number)}` },
          })
          .expect(201),
      ),
    );
    const paginationIds = paginationEntries.map(
      (response) => (response.body as { id: string }).id,
    );
    const tiedCreatedAt = new Date("2026-09-11T09:01:00.000Z");
    await database.collection<{ _id: string }>("journal_entries").updateMany(
      { _id: { $in: paginationIds } },
      {
        $set: {
          createdAt: tiedCreatedAt,
          "cursor.sortCreatedAt": tiedCreatedAt,
        },
      },
    );
    const firstPage = await request(server)
      .get("/api/v1/journals?limit=2")
      .set("Authorization", `Bearer ${ownerToken}`)
      .expect(200);
    const firstPageBody = firstPage.body as {
      items: { id: string }[];
      page: { hasMore: boolean; nextCursor?: string };
    };
    assert.equal(firstPageBody.items.length, 2);
    assert.equal(firstPageBody.page.hasMore, true);
    assert.ok(firstPageBody.page.nextCursor);
    const secondPage = await request(server)
      .get(
        `/api/v1/journals?limit=2&cursor=${encodeURIComponent(firstPageBody.page.nextCursor)}`,
      )
      .set("Authorization", `Bearer ${ownerToken}`)
      .expect(200);
    const secondPageBody = secondPage.body as {
      items: { id: string }[];
      page: { hasMore: boolean };
    };
    assert.equal(secondPageBody.items.length, 1);
    assert.equal(secondPageBody.page.hasMore, false);
    assert.deepEqual(
      [...firstPageBody.items, ...secondPageBody.items].map((item) => item.id),
      [...paginationIds].sort(),
    );
    const explanation = await database
      .collection("journal_entries")
      .find({ ownerAccountId: ownerId, deleted: false })
      .sort({
        "cursor.sortOccurredAt": -1,
        "cursor.sortCreatedAt": -1,
        "cursor.entryId": 1,
      })
      .hint("journal_entries_owner_cursor_idx")
      .limit(2)
      .explain("queryPlanner");
    assert.match(
      JSON.stringify(explanation),
      /journal_entries_owner_cursor_idx/,
    );

    const createBody = {
      clientEntryId: "33333333-3333-4333-8333-333333333333",
      occurredAt: "2026-09-10T09:00:00.000Z",
      content: { text: "plaintext must never be stored" },
      mood: "GOOD",
      tags: ["private"],
    };
    const createdResponse = await request(server)
      .post("/api/v1/journals")
      .set("Authorization", `Bearer ${ownerToken}`)
      .set("Idempotency-Key", "integration-create-0001")
      .send(createBody);
    assert.equal(
      createdResponse.status,
      201,
      JSON.stringify({
        persisted: await database
          .collection("journal_entries")
          .countDocuments(),
      }),
    );
    assert.match(
      String(createdResponse.headers.location),
      /\/api\/v1\/journals\/[0-9a-f-]+$/,
    );
    const created = createdResponse.body as {
      id: string;
      currentRevision: number;
      mood: string;
    };
    assert.equal(created.mood, "GOOD");

    const replay = await request(server)
      .post("/api/v1/journals")
      .set("Authorization", `Bearer ${ownerToken}`)
      .set("Idempotency-Key", "integration-create-0001")
      .send(createBody)
      .expect(201);
    const replayBody = replay.body as { id?: unknown };
    assert.equal(replayBody.id, created.id);
    assert.equal(
      await database.collection("journal_entries").countDocuments(),
      4,
    );
    const legacyResponse = await request(server)
      .post("/api/v1/journals")
      .set("Authorization", `Bearer ${ownerToken}`)
      .set("Idempotency-Key", "integration-create-legacy")
      .send({
        ...createBody,
        clientEntryId: "44444444-4444-4444-8444-444444444444",
        mood: undefined,
      })
      .expect(201);
    const legacy = legacyResponse.body as { id: string; mood?: unknown };
    assert.equal(legacy.mood, null);
    const legacyDocument = await database
      .collection<{ _id: string; revisions: { mood?: unknown }[] }>(
        "journal_entries",
      )
      .findOne({ _id: legacy.id });
    assert.ok(legacyDocument);
    assert.equal(legacyDocument.revisions[0]?.mood, undefined);
    await request(server)
      .post("/api/v1/journals")
      .set("Authorization", `Bearer ${ownerToken}`)
      .set("Idempotency-Key", "integration-create-0001")
      .send({ ...createBody, content: { text: "conflicting reuse" } })
      .expect(409);
    const raw = await database
      .collection<{
        _id: string;
        revisions: {
          content: { ciphertext: { _bsontype?: string } };
          mood?: { ciphertext: { _bsontype?: string } };
        }[];
      }>("journal_entries")
      .findOne({ _id: created.id });
    assert.ok(raw);
    assert.equal(JSON.stringify(raw).includes(createBody.content.text), false);
    assert.equal(JSON.stringify(raw).includes(createBody.mood), false);
    assert.equal(raw.revisions[0]?.content.ciphertext._bsontype, "Binary");
    assert.equal(raw.revisions[0].mood?.ciphertext._bsontype, "Binary");

    await request(server)
      .get(`/api/v1/journals/${created.id}`)
      .set("Authorization", `Bearer ${otherToken}`)
      .expect(404);

    const originalRevisionBody = {
      content: { text: "first revision wins" },
      mood: "LOW",
      tags: ["first"],
    };
    const originalRevision = await request(server)
      .patch(`/api/v1/journals/${created.id}`)
      .set("Authorization", `Bearer ${ownerToken}`)
      .set("Idempotency-Key", "integration-revise-original")
      .set("If-Match-Revision", "1")
      .send(originalRevisionBody)
      .expect(200);
    await request(server)
      .patch(`/api/v1/journals/${created.id}`)
      .set("Authorization", `Bearer ${ownerToken}`)
      .set("Idempotency-Key", "integration-revise-later")
      .set("If-Match-Revision", "2")
      .send({ content: { text: "later revision" }, tags: ["later"] })
      .expect(200);
    const preservedMood = await request(server)
      .get(`/api/v1/journals/${created.id}`)
      .set("Authorization", `Bearer ${ownerToken}`)
      .expect(200);
    assert.equal((preservedMood.body as { mood?: unknown }).mood, "LOW");

    const revisions = await Promise.all([
      request(server)
        .patch(`/api/v1/journals/${created.id}`)
        .set("Authorization", `Bearer ${ownerToken}`)
        .set("Idempotency-Key", "integration-revise-001")
        .set("If-Match-Revision", "3")
        .send({ content: { text: "concurrent first" }, tags: ["concurrent"] }),
      request(server)
        .patch(`/api/v1/journals/${created.id}`)
        .set("Authorization", `Bearer ${ownerToken}`)
        .set("Idempotency-Key", "integration-revise-002")
        .set("If-Match-Revision", "3")
        .send({ content: { text: "concurrent second" }, tags: ["second"] }),
    ]);
    assert.deepEqual(
      revisions.map((response) => response.status).sort(),
      [200, 412],
    );
    const winningRevision = revisions.find(
      (response) => response.status === 200,
    );
    assert.ok(winningRevision);
    let currentRevision = Number(
      (winningRevision.body as { currentRevision?: unknown }).currentRevision,
    );
    for (let number = 0; number < 33; number += 1) {
      const retained = await request(server)
        .patch(`/api/v1/journals/${created.id}`)
        .set("Authorization", `Bearer ${ownerToken}`)
        .set(
          "Idempotency-Key",
          `integration-retained-${String(number).padStart(3, "0")}`,
        )
        .set("If-Match-Revision", String(currentRevision))
        .send({
          content: { text: `retained revision ${String(number)}` },
          tags: [`retained-${String(number)}`],
        })
        .expect(200);
      currentRevision = Number(
        (retained.body as { currentRevision?: unknown }).currentRevision,
      );
    }
    assert.ok(currentRevision > 32);
    const replayedCreateAfterRevisions = await request(server)
      .post("/api/v1/journals")
      .set("Authorization", `Bearer ${ownerToken}`)
      .set("Idempotency-Key", "integration-create-0001")
      .send(createBody)
      .expect(201);
    assert.deepEqual(replayedCreateAfterRevisions.body, createdResponse.body);
    const replayedRevision = await request(server)
      .patch(`/api/v1/journals/${created.id}`)
      .set("Authorization", `Bearer ${ownerToken}`)
      .set("Idempotency-Key", "integration-revise-original")
      .set("If-Match-Revision", "1")
      .send(originalRevisionBody)
      .expect(200);
    assert.deepEqual(replayedRevision.body, originalRevision.body);
    const retainedDocument = await database
      .collection<{ _id: string; commands: unknown[] }>("journal_entries")
      .findOne({ _id: created.id });
    assert.ok(retainedDocument);
    assert.ok(retainedDocument.commands.length > 32);

    const deletedResponse = await request(server)
      .delete(`/api/v1/journals/${created.id}`)
      .set("Authorization", `Bearer ${ownerToken}`)
      .set("Idempotency-Key", "integration-delete-001")
      .expect(200);
    const deleted = deletedResponse.body as { deleted?: unknown };
    assert.equal(deleted.deleted, true);
    const replayedDelete = await request(server)
      .delete(`/api/v1/journals/${created.id}`)
      .set("Authorization", `Bearer ${ownerToken}`)
      .set("Idempotency-Key", "integration-delete-001")
      .expect(200);
    assert.deepEqual(replayedDelete.body, deletedResponse.body);
    await request(server)
      .get(`/api/v1/journals/${created.id}`)
      .set("Authorization", `Bearer ${ownerToken}`)
      .expect(404);
    await request(server)
      .get("/api/v1/journals?includeDeleted=true")
      .set("Authorization", `Bearer ${ownerToken}`)
      .expect(400);
    await assert.rejects(
      () => journalMood.down(database),
      /Cannot remove encrypted journal mood/,
    );

    const unavailableApp = await createApplication(
      {
        ...configuration,
        MONGODB_URI: "mongodb://127.0.0.1:1",
        MONGODB_DATABASE: "journal_mb236_unavailable",
        MONGODB_CONNECTION_TIMEOUT_MS: 100,
      },
      { readinessProbe: { check: () => Promise.resolve() } },
    );
    await unavailableApp.init();
    const unavailableStartedAt = Date.now();
    try {
      await request(unavailableApp.getHttpServer() as unknown as Server)
        .get("/api/v1/journals")
        .set("Authorization", `Bearer ${ownerToken}`)
        .expect(503);
      assert.ok(Date.now() - unavailableStartedAt < 2_000);
    } finally {
      await unavailableApp.close();
    }
  } finally {
    await app.close();
    if (externalUri) await database.dropDatabase();
    await client.close();
    await mongo?.stop();
  }
});
