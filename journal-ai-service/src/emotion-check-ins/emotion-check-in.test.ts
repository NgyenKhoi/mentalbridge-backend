import assert from "node:assert/strict";
import test from "node:test";
import {
  BadRequestException,
  ConflictException,
  HttpException,
  NotFoundException,
  ServiceUnavailableException,
} from "@nestjs/common";

import type { ConsentClient } from "../analysis/analysis.js";
import type { ServiceConfiguration } from "../configuration/configuration.js";
import {
  EmotionCheckInCrypto,
  EmotionCheckInService,
  type CheckInCommand,
  type CheckInRevision,
  type EmotionCheckInDocument,
  type EmotionCheckInRepository,
} from "./emotion-check-in.js";

class MemoryRepository implements EmotionCheckInRepository {
  readonly documents: EmotionCheckInDocument[] = [];

  create(value: EmotionCheckInDocument) {
    if (
      this.documents.some(
        (candidate) =>
          candidate.ownerAccountId === value.ownerAccountId &&
          (candidate.localDate === value.localDate ||
            candidate.commands.some((command) =>
              value.commands.some((other) => other.keyHash === command.keyHash),
            )),
      )
    )
      throw new ConflictException();
    this.documents.push(value);
    return Promise.resolve(value);
  }

  find(ownerAccountId: string, localDate: string) {
    return Promise.resolve(
      this.documents.find(
        (candidate) =>
          candidate.ownerAccountId === ownerAccountId &&
          candidate.localDate === localDate,
      ) ?? null,
    );
  }

  findByCommand(ownerAccountId: string, keyHash: string) {
    return Promise.resolve(
      this.documents.find(
        (candidate) =>
          candidate.ownerAccountId === ownerAccountId &&
          candidate.commands.some((command) => command.keyHash === keyHash),
      ) ?? null,
    );
  }

  list(ownerAccountId: string, limit: number, before?: string) {
    const rows = this.documents
      .filter(
        (candidate) =>
          candidate.ownerAccountId === ownerAccountId &&
          !candidate.deleted &&
          (!before || candidate.localDate < before),
      )
      .sort((left, right) => right.localDate.localeCompare(left.localDate));
    return Promise.resolve({
      rows: rows.slice(0, limit),
      hasMore: rows.length > limit,
    });
  }

  progressSnapshot(
    ownerAccountId: string,
    startLocalDate: string,
    endLocalDate: string,
  ) {
    const active = this.documents.filter(
      (candidate) =>
        candidate.ownerAccountId === ownerAccountId && !candidate.deleted,
    );
    return Promise.resolve({
      dates: active.map((candidate) => candidate.localDate).sort(),
      rows: active
        .filter(
          (candidate) =>
            candidate.localDate >= startLocalDate &&
            candidate.localDate <= endLocalDate,
        )
        .sort((left, right) => right.localDate.localeCompare(left.localDate)),
    });
  }

  update(
    ownerAccountId: string,
    localDate: string,
    expectedRevision: number,
    revision: CheckInRevision,
    command: CheckInCommand,
  ) {
    const document = this.documents.find(
      (candidate) =>
        candidate.ownerAccountId === ownerAccountId &&
        candidate.localDate === localDate &&
        !candidate.deleted &&
        candidate.currentRevision === expectedRevision &&
        !candidate.commands.some(
          (existing) => existing.keyHash === command.keyHash,
        ),
    );
    if (!document) return Promise.resolve(null);
    document.currentRevision += 1;
    document.revisions.push(revision);
    document.commands.push(command);
    document.updatedAt = command.recordedAt;
    return Promise.resolve(document);
  }

  tombstone(
    ownerAccountId: string,
    localDate: string,
    deletedAt: Date,
    purgeAfter: Date,
    command: CheckInCommand,
  ) {
    const document = this.documents.find(
      (candidate) =>
        candidate.ownerAccountId === ownerAccountId &&
        candidate.localDate === localDate &&
        !candidate.deleted,
    );
    if (!document) return Promise.resolve(null);
    document.deleted = true;
    document.deletedAt = deletedAt;
    document.purgeAfter = purgeAfter;
    document.updatedAt = deletedAt;
    document.revisions = [];
    document.commands.push(command);
    return Promise.resolve(document);
  }
}

const configuration = {
  JOURNAL_ENCRYPTION_KEY: Buffer.alloc(32, 31),
  JOURNAL_ENCRYPTION_KEY_ID: "single-key",
  JOURNAL_IDEMPOTENCY_HMAC_KEY: Buffer.alloc(32, 32),
} as ServiceConfiguration;
const ownerId = "11111111-1111-4111-8111-111111111111";
const otherOwnerId = "22222222-2222-4222-8222-222222222222";
const now = new Date("2026-09-16T17:30:00.000Z");
const grantedConsent: ConsentClient = {
  check: () =>
    Promise.resolve({
      authorized: true,
      reason: "GRANTED",
      policyVersion: "ai-processing-capstone-v1",
      decidedAt: "2026-09-16T09:00:00.000Z",
    }),
};

const request = (
  body: unknown,
  key = "emotion-command-0001",
  revision?: number,
  accountId = ownerId,
) => ({
  id: "emotion-test-correlation",
  headers: {
    authorization: "Bearer synthetic-token",
    "idempotency-key": key,
    ...(revision ? { "if-match-revision": String(revision) } : {}),
  },
  principal: { accountId, roles: ["USER"] },
  body,
});

const createBody = {
  localDate: "2026-09-17",
  timezone: "Asia/Ho_Chi_Minh",
  emotion: "GOOD" as const,
  intensity: 4,
  note: "A synthetic private note",
};

const subject = (consentClient: ConsentClient = grantedConsent) => {
  const repository = new MemoryRepository();
  return {
    repository,
    service: new EmotionCheckInService(
      repository,
      new EmotionCheckInCrypto(configuration),
      consentClient,
      { now: () => new Date(now) },
    ),
  };
};

const seedCheckIn = (
  repository: MemoryRepository,
  localDate: string,
  emotion: "GREAT" | "GOOD" | "OKAY" | "LOW" | "VERY_LOW" = "GOOD",
) => {
  const crypto = new EmotionCheckInCrypto(configuration);
  const recordedAt = new Date(`${localDate}T08:00:00.000Z`);
  repository.documents.push({
    _id: `00000000-0000-4000-8000-${localDate.replaceAll("-", "")}00`,
    ownerAccountId: ownerId,
    localDate,
    timezone: "Asia/Ho_Chi_Minh",
    currentRevision: 1,
    revisions: [
      {
        revision: 1,
        recordedAt,
        payload: crypto.encrypt(ownerId, localDate, 1, {
          emotion,
          intensity: 3,
          note: null,
        }),
      },
    ],
    commands: [],
    createdAt: recordedAt,
    updatedAt: recordedAt,
    deleted: false,
    deletedAt: null,
    purgeAfter: null,
  });
};

void test("creates one encrypted owner-scoped check-in and replays exact retries", async () => {
  const { repository, service } = subject();
  const created = await service.create(request(createBody));
  assert.equal(created.localDate, "2026-09-17");
  assert.equal(created.sourceLabel, "SELF_REPORTED_EMOTION");
  assert.equal(created.note, createBody.note);
  assert.equal(
    JSON.stringify(repository.documents).includes(createBody.note),
    false,
  );
  assert.equal(JSON.stringify(repository.documents).includes("GOOD"), false);
  assert.deepEqual(await service.create(request(createBody)), created);
  await assert.rejects(
    () =>
      service.create(
        request({ ...createBody, intensity: 2 }, "emotion-command-0001"),
      ),
    ConflictException,
  );
  await assert.rejects(
    () =>
      service.get(
        request({}, "unused", undefined, otherOwnerId),
        createBody.localDate,
      ),
    NotFoundException,
  );
});

void test("replays an exact create after the owner's local day rolls over", async () => {
  const repository = new MemoryRepository();
  let clock = new Date(now);
  const service = new EmotionCheckInService(
    repository,
    new EmotionCheckInCrypto(configuration),
    grantedConsent,
    { now: () => new Date(clock) },
  );
  const created = await service.create(request(createBody));

  clock = new Date("2026-09-17T17:30:00.000Z");
  assert.deepEqual(await service.create(request(createBody)), created);
  assert.equal(repository.documents.length, 1);
  await assert.rejects(
    () => service.create(request(createBody, "emotion-command-next-day")),
    BadRequestException,
  );
});

void test("enforces IANA timezone and server-derived local-day boundaries", async () => {
  const { service } = subject();
  await assert.rejects(
    () => service.create(request({ ...createBody, timezone: "Mars/Olympus" })),
    BadRequestException,
  );
  await assert.rejects(
    () => service.create(request({ ...createBody, localDate: "2026-09-16" })),
    BadRequestException,
  );
  await assert.rejects(
    () => service.create(request({ ...createBody, note: "   " })),
    BadRequestException,
  );
});

void test("supports idempotent optimistic updates and rejects concurrent writers", async () => {
  const { service } = subject();
  await service.create(request(createBody));
  const updateBody = { emotion: "LOW" as const, intensity: 3, note: null };
  const results = await Promise.allSettled([
    service.update(
      request(updateBody, "emotion-update-0001", 1),
      createBody.localDate,
    ),
    service.update(
      request(
        { emotion: "GREAT", intensity: 5, note: null },
        "emotion-update-0002",
        1,
      ),
      createBody.localDate,
    ),
  ]);
  assert.equal(
    results.filter((result) => result.status === "fulfilled").length,
    1,
  );
  const rejected = results.find((result) => result.status === "rejected");
  assert.ok(rejected && rejected.reason instanceof HttpException);
  assert.equal(rejected.reason.getStatus(), 412);
  const replay = await service.update(
    request(updateBody, "emotion-update-0001", 1),
    createBody.localDate,
  );
  assert.equal(replay.revision, 2);
  assert.equal(replay.note, null);
});

void test("deletion removes encrypted revisions and keeps bounded replay evidence", async () => {
  const { repository, service } = subject();
  await service.create(request(createBody));
  const deleted = await service.remove(
    request({}, "emotion-delete-0001"),
    createBody.localDate,
  );
  assert.equal(deleted.deleted, true);
  assert.deepEqual(
    await service.remove(
      request({}, "emotion-delete-0001"),
      createBody.localDate,
    ),
    deleted,
  );
  const document = repository.documents[0];
  assert.ok(document);
  assert.ok(document.purgeAfter);
  assert.equal(document.revisions.length, 0);
  assert.equal(document.purgeAfter.toISOString(), "2026-10-16T17:30:00.000Z");
  await assert.rejects(
    () => service.get(request({}), createBody.localDate),
    NotFoundException,
  );
});

void test("returns note-free context only under current consent", async () => {
  const { service } = subject();
  await service.create(request(createBody));
  const context = await service.context(request({}), {
    purpose: "AI_REFLECTION",
    limit: "14",
  });
  assert.equal(context.items.length, 1);
  const projection = context.items[0];
  assert.ok(projection);
  assert.equal("note" in projection, false);
  assert.equal("id" in projection, false);
  assert.equal(context.consent.policyVersion, "ai-processing-capstone-v1");
  assert.equal(context.consent.decidedAt, "2026-09-16T09:00:00.000Z");

  const withdrawn = subject({
    check: () => Promise.resolve({ authorized: false, reason: "REVOKED" }),
  });
  await withdrawn.service.create(request(createBody));
  await assert.rejects(
    () =>
      withdrawn.service.context(request({}), {
        purpose: "AI_REFLECTION",
      }),
    (error: unknown) =>
      error instanceof HttpException && error.getStatus() === 403,
  );

  const unavailable = subject({
    check: () => Promise.reject(new Error("Care unavailable")),
  });
  await unavailable.service.create(request(createBody));
  await assert.rejects(
    () =>
      unavailable.service.context(request({}), {
        purpose: "AI_REFLECTION",
      }),
    ServiceUnavailableException,
  );
});

void test("calculates factual current/longest streaks and 7/14/30-day coverage", async () => {
  const { repository, service } = subject();
  for (const localDate of [
    "2026-09-05",
    "2026-09-06",
    "2026-09-07",
    "2026-09-08",
    "2026-09-09",
  ])
    seedCheckIn(repository, localDate, "OKAY");
  seedCheckIn(repository, "2026-09-14", "LOW");
  seedCheckIn(repository, "2026-09-15", "GOOD");
  seedCheckIn(repository, "2026-09-16", "GREAT");
  seedCheckIn(repository, "2026-09-17", "GOOD");

  const progress = await service.progress(request({}), {
    timezone: "Asia/Ho_Chi_Minh",
  });

  assert.equal(progress.asOfLocalDate, "2026-09-17");
  assert.equal(progress.currentEmotion, "GOOD");
  assert.equal(progress.currentStreak, 4);
  assert.equal(progress.longestStreak, 5);
  assert.deepEqual(
    progress.windows.map((window) => [window.days, window.checkedInDays]),
    [
      [7, 4],
      [14, 9],
      [30, 9],
    ],
  );
  assert.deepEqual(progress.windows[0]?.distribution, {
    GREAT: 1,
    GOOD: 2,
    OKAY: 0,
    LOW: 1,
    VERY_LOW: 0,
  });
  assert.equal(
    progress.interpretation,
    "FACTUAL_COUNTS_NOT_DIAGNOSIS_OR_RECOVERY",
  );
});

void test("same-day edit changes distribution without incrementing coverage or streak", async () => {
  const { service } = subject();
  await service.create(request(createBody));
  await service.update(
    request(
      { emotion: "LOW", intensity: 2, note: null },
      "emotion-progress-update",
      1,
    ),
    createBody.localDate,
  );

  const progress = await service.progress(request({}), {
    timezone: "Asia/Ho_Chi_Minh",
  });

  assert.equal(progress.currentStreak, 1);
  assert.equal(progress.windows[0]?.checkedInDays, 1);
  assert.equal(progress.windows[0].distribution.LOW, 1);
  assert.equal(progress.windows[0].distribution.GOOD, 0);
});

void test("missing and deleted local days break streaks while longest is recomputed", async () => {
  const { repository, service } = subject();
  seedCheckIn(repository, "2026-09-14");
  seedCheckIn(repository, "2026-09-15");
  seedCheckIn(repository, "2026-09-17");

  let progress = await service.progress(request({}), {
    timezone: "Asia/Ho_Chi_Minh",
  });
  assert.equal(progress.currentStreak, 1);
  assert.equal(progress.longestStreak, 2);

  const current = repository.documents.find(
    (document) => document.localDate === "2026-09-17",
  );
  assert.ok(current);
  current.deleted = true;
  progress = await service.progress(request({}), {
    timezone: "Asia/Ho_Chi_Minh",
  });
  assert.equal(progress.currentEmotion, null);
  assert.equal(progress.currentStreak, 0);
  assert.equal(progress.longestStreak, 2);
});

void test("anchors progress windows to the requested valid IANA timezone", async () => {
  const { repository, service } = subject();
  seedCheckIn(repository, "2026-09-16", "OKAY");
  seedCheckIn(repository, "2026-09-17", "GREAT");

  const vietnam = await service.progress(request({}), {
    timezone: "Asia/Ho_Chi_Minh",
  });
  const newYork = await service.progress(request({}), {
    timezone: "America/New_York",
  });

  assert.equal(vietnam.asOfLocalDate, "2026-09-17");
  assert.equal(vietnam.currentEmotion, "GREAT");
  assert.equal(vietnam.currentStreak, 2);
  assert.equal(newYork.asOfLocalDate, "2026-09-16");
  assert.equal(newYork.currentEmotion, "OKAY");
  assert.equal(newYork.currentStreak, 1);
  await assert.rejects(
    () => service.progress(request({}), { timezone: "Mars/Olympus" }),
    BadRequestException,
  );
});

void test("keeps yesterday's streak current until the open local day is complete", async () => {
  const { repository, service } = subject();
  seedCheckIn(repository, "2026-09-15", "OKAY");
  seedCheckIn(repository, "2026-09-16", "GOOD");

  const progress = await service.progress(request({}), {
    timezone: "Asia/Ho_Chi_Minh",
  });

  assert.equal(progress.asOfLocalDate, "2026-09-17");
  assert.equal(progress.currentEmotion, null);
  assert.equal(progress.currentStreak, 2);
  assert.equal(progress.longestStreak, 2);
});

void test("returns truthful zero progress when no check-ins exist", async () => {
  const { service } = subject();
  const progress = await service.progress(request({}), {
    timezone: "Asia/Ho_Chi_Minh",
  });
  assert.equal(progress.currentEmotion, null);
  assert.equal(progress.currentStreak, 0);
  assert.equal(progress.longestStreak, 0);
  assert.ok(progress.windows.every((window) => window.checkedInDays === 0));
  assert.deepEqual(progress.windows[2]?.distribution, {
    GREAT: 0,
    GOOD: 0,
    OKAY: 0,
    LOW: 0,
    VERY_LOW: 0,
  });
});
