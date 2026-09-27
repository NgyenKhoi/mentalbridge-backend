import assert from "node:assert/strict";
import test from "node:test";

import {
  NotificationActivityService,
  factualStreak,
  type NotificationActivityRepository,
} from "./notification-activity.js";

const OWNER = "3e2ae4c2-faa9-42c2-8a73-fae1c9362ca8";

void test("calculates current and longest factual streaks without double-counting a local day", () => {
  assert.deepEqual(
    factualStreak(
      ["2026-09-20", "2026-09-20", "2026-09-22", "2026-09-23", "2026-09-24"],
      "2026-09-24",
    ),
    { completedToday: true, currentStreak: 3, longestStreak: 3 },
  );
});

void test("keeps yesterday's streak current while today is still open and resets after a missing completed day", () => {
  assert.deepEqual(factualStreak(["2026-09-22", "2026-09-23"], "2026-09-24"), {
    completedToday: false,
    currentStreak: 2,
    longestStreak: 2,
  });
  assert.deepEqual(factualStreak(["2026-09-21", "2026-09-22"], "2026-09-24"), {
    completedToday: false,
    currentStreak: 0,
    longestStreak: 2,
  });
});

void test("keeps journal and emotion activity independent across a timezone boundary", async () => {
  const repository: NotificationActivityRepository = {
    snapshot(ownerAccountId) {
      assert.equal(ownerAccountId, OWNER);
      return Promise.resolve({
        journalOccurredAt: [
          new Date("2026-09-23T17:30:00.000Z"),
          new Date("2026-09-24T18:00:00.000Z"),
        ],
        emotionLocalDates: ["2026-09-23", "2026-09-24"],
      });
    },
  };
  const service = new NotificationActivityService(repository, {
    now: () => new Date("2026-09-24T18:30:00.000Z"),
  });

  const vietnam = await service.get(OWNER, "Asia/Ho_Chi_Minh");
  assert.equal(vietnam.asOfLocalDate, "2026-09-25");
  assert.deepEqual(vietnam.journal, {
    completedToday: true,
    currentStreak: 2,
    longestStreak: 2,
  });
  assert.deepEqual(vietnam.emotionCheckIn, {
    completedToday: false,
    currentStreak: 2,
    longestStreak: 2,
  });

  const utc = await service.get(OWNER, "UTC");
  assert.equal(utc.asOfLocalDate, "2026-09-24");
  assert.deepEqual(utc.journal, {
    completedToday: true,
    currentStreak: 2,
    longestStreak: 2,
  });
});

void test("rejects an invalid timezone before reading activity", async () => {
  let reads = 0;
  const service = new NotificationActivityService(
    {
      snapshot() {
        reads += 1;
        return Promise.resolve({
          journalOccurredAt: [],
          emotionLocalDates: [],
        });
      },
    },
    { now: () => new Date("2026-09-24T00:00:00.000Z") },
  );

  await assert.rejects(service.get(OWNER, "Not/A_Timezone"));
  assert.equal(reads, 0);
});
