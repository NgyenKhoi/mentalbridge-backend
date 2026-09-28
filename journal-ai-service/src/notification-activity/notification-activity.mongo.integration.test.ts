import assert from "node:assert/strict";
import test from "node:test";
import { MongoClient } from "mongodb";
import { MongoMemoryServer } from "mongodb-memory-server";

import type { ServiceConfiguration } from "../configuration/configuration.js";
import {
  MongoNotificationActivityRepository,
  NotificationActivityService,
} from "./notification-activity.js";

const OWNER = "46639739-07ca-44a4-bf18-0fffdbe0f92a";
const OTHER_OWNER = "a67d9b98-1d92-4302-b53e-683ff3142969";

void test("recomputes owner-isolated factual activity from real MongoDB", async () => {
  const externalUri = process.env.JOURNAL_INTEGRATION_MONGODB_URI;
  const mongo = externalUri ? undefined : await MongoMemoryServer.create();
  const uri = externalUri ?? mongo?.getUri();
  assert.ok(uri);
  const databaseName = externalUri
    ? `notification_activity_verify_${String(Date.now())}`
    : "notification_activity_integration";
  const client = new MongoClient(uri);
  const database = client.db(databaseName);
  const repository = new MongoNotificationActivityRepository({
    MONGODB_URI: uri,
    MONGODB_DATABASE: databaseName,
    MONGODB_CONNECTION_TIMEOUT_MS: 2_000,
  } as ServiceConfiguration);

  try {
    await client.connect();
    await database.collection("journal_entries").insertMany([
      {
        ownerAccountId: OWNER,
        occurredAt: new Date("2026-09-20T18:00:00.000Z"),
        deleted: false,
      },
      {
        ownerAccountId: OWNER,
        occurredAt: new Date("2026-09-21T18:00:00.000Z"),
        deleted: false,
        encryptedContent: "must-not-be-returned",
      },
      {
        ownerAccountId: OWNER,
        occurredAt: new Date("2026-09-22T18:00:00.000Z"),
        deleted: false,
      },
      {
        ownerAccountId: OWNER,
        occurredAt: new Date("2026-09-23T01:00:00.000Z"),
        deleted: false,
      },
      {
        ownerAccountId: OWNER,
        occurredAt: new Date("2026-09-23T15:00:00.000Z"),
        deleted: false,
      },
      {
        ownerAccountId: OWNER,
        occurredAt: new Date("2026-09-23T16:00:00.000Z"),
        deleted: true,
      },
      {
        ownerAccountId: OTHER_OWNER,
        occurredAt: new Date("2026-09-20T18:00:00.000Z"),
        deleted: false,
      },
    ]);
    await database.collection("emotion_check_ins").insertMany([
      {
        ownerAccountId: OWNER,
        localDate: "2026-09-22",
        deleted: false,
        encryptedNote: "must-not-be-returned",
      },
      {
        ownerAccountId: OWNER,
        localDate: "2026-09-24",
        deleted: false,
        revisions: [{ revision: 1 }, { revision: 2 }],
      },
      {
        ownerAccountId: OWNER,
        localDate: "2026-09-23",
        deleted: true,
      },
      {
        ownerAccountId: OTHER_OWNER,
        localDate: "2026-09-23",
        deleted: false,
      },
    ]);

    const service = new NotificationActivityService(repository, {
      now: () => new Date("2026-09-24T12:00:00.000Z"),
    });
    const result = await service.get(OWNER, "Asia/Ho_Chi_Minh");

    assert.deepEqual(result, {
      asOfLocalDate: "2026-09-24",
      timezone: "Asia/Ho_Chi_Minh",
      journal: {
        completedToday: false,
        currentStreak: 3,
        longestStreak: 3,
      },
      emotionCheckIn: {
        completedToday: true,
        currentStreak: 1,
        longestStreak: 1,
      },
      interpretation: "FACTUAL_ACTIVITY_NOT_ADHERENCE_OR_RECOVERY",
    });
    assert.equal(
      JSON.stringify(result).includes("must-not-be-returned"),
      false,
    );
  } finally {
    await repository.onApplicationShutdown();
    await database.dropDatabase();
    await client.close();
    await mongo?.stop();
  }
});
