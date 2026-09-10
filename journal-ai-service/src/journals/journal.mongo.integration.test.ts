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
    JOURNAL_ENCRYPTION_KEY_ID: "integration-v1",
    JOURNAL_IDEMPOTENCY_HMAC_KEY: Buffer.alloc(32, 12),
    IDENTITY_JWT_ISSUER: "https://identity.test.mentalbridge",
    IDENTITY_JWT_AUDIENCE: "mentalbridge-api",
    IDENTITY_JWT_KEY_ID: "integration-key",
    IDENTITY_JWT_PUBLIC_KEY: await exportSPKI(publicKey),
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
    const createBody = {
      clientEntryId: "33333333-3333-4333-8333-333333333333",
      occurredAt: "2026-09-10T09:00:00.000Z",
      content: { text: "plaintext must never be stored" },
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
    };

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
      1,
    );
    const raw = await database
      .collection<{
        _id: string;
        revisions: {
          content: { ciphertext: { _bsontype?: string } };
        }[];
      }>("journal_entries")
      .findOne({ _id: created.id });
    assert.ok(raw);
    assert.equal(JSON.stringify(raw).includes(createBody.content.text), false);
    assert.equal(raw.revisions[0]?.content.ciphertext._bsontype, "Binary");

    await request(server)
      .get(`/api/v1/journals/${created.id}`)
      .set("Authorization", `Bearer ${otherToken}`)
      .expect(404);

    const revisions = await Promise.all([
      request(server)
        .patch(`/api/v1/journals/${created.id}`)
        .set("Authorization", `Bearer ${ownerToken}`)
        .set("Idempotency-Key", "integration-revise-001")
        .set("If-Match-Revision", "1")
        .send({ content: { text: "first revision wins" }, tags: ["first"] }),
      request(server)
        .patch(`/api/v1/journals/${created.id}`)
        .set("Authorization", `Bearer ${ownerToken}`)
        .set("Idempotency-Key", "integration-revise-002")
        .set("If-Match-Revision", "1")
        .send({ content: { text: "second revision loses" }, tags: ["second"] }),
    ]);
    assert.deepEqual(
      revisions.map((response) => response.status).sort(),
      [200, 412],
    );

    const deletedResponse = await request(server)
      .delete(`/api/v1/journals/${created.id}`)
      .set("Authorization", `Bearer ${ownerToken}`)
      .set("Idempotency-Key", "integration-delete-001")
      .expect(200);
    const deleted = deletedResponse.body as { deleted?: unknown };
    assert.equal(deleted.deleted, true);
    await request(server)
      .get(`/api/v1/journals/${created.id}`)
      .set("Authorization", `Bearer ${ownerToken}`)
      .expect(404);
  } finally {
    await app.close();
    if (externalUri) await database.dropDatabase();
    await client.close();
    await mongo?.stop();
  }
});
