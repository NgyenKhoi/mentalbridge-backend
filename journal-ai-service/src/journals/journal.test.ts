import assert from "node:assert/strict";
import test from "node:test";
import {
  BadRequestException,
  ConflictException,
  HttpException,
  NotFoundException,
} from "@nestjs/common";
import {
  JournalEncryption,
  JournalService,
  type Entry,
  type JournalCursor,
  type JournalStore,
  type MutationCommand,
  type Revision,
} from "./journal.js";
import type { ServiceConfiguration } from "../configuration/configuration.js";

class MemoryStore implements JournalStore {
  entries: Entry[] = [];
  create(entry: Entry) {
    this.entries.push(entry);
    return Promise.resolve(entry);
  }
  find(owner: string, id: string) {
    return Promise.resolve(
      this.entries.find(
        (entry) => entry.ownerAccountId === owner && entry._id === id,
      ) ?? null,
    );
  }
  findByClientId(owner: string, id: string) {
    return Promise.resolve(
      this.entries.find(
        (entry) => entry.ownerAccountId === owner && entry.clientEntryId === id,
      ) ?? null,
    );
  }
  findByCommand(owner: string, keyHash: string) {
    return Promise.resolve(
      this.entries.find(
        (entry) =>
          entry.ownerAccountId === owner &&
          entry.commands.some((command) => command.keyHash === keyHash),
      ) ?? null,
    );
  }
  list(owner: string, limit: number, cursor?: JournalCursor) {
    const rows = this.entries
      .filter(
        (entry) =>
          entry.ownerAccountId === owner &&
          !entry.deleted &&
          (!cursor ||
            entry.cursor.sortOccurredAt < cursor.sortOccurredAt ||
            (entry.cursor.sortOccurredAt.getTime() ===
              cursor.sortOccurredAt.getTime() &&
              (entry.cursor.sortCreatedAt < cursor.sortCreatedAt ||
                (entry.cursor.sortCreatedAt.getTime() ===
                  cursor.sortCreatedAt.getTime() &&
                  entry.cursor.entryId > cursor.entryId)))),
      )
      .sort((left, right) => {
        const occurred =
          right.cursor.sortOccurredAt.getTime() -
          left.cursor.sortOccurredAt.getTime();
        if (occurred !== 0) return occurred;
        const created =
          right.cursor.sortCreatedAt.getTime() -
          left.cursor.sortCreatedAt.getTime();
        return created !== 0
          ? created
          : left.cursor.entryId.localeCompare(right.cursor.entryId);
      });
    return Promise.resolve({
      rows: rows.slice(0, limit),
      hasMore: rows.length > limit,
    });
  }
  revise(
    owner: string,
    id: string,
    expected: number,
    revision: Revision,
    tags: string[],
    command: MutationCommand,
  ) {
    const entry = this.entries.find(
      (item) =>
        item.ownerAccountId === owner &&
        item._id === id &&
        !item.deleted &&
        item.currentRevision === expected &&
        !item.commands.some(
          (itemCommand) => itemCommand.keyHash === command.keyHash,
        ),
    );
    if (!entry) return Promise.resolve(null);
    entry.currentRevision += 1;
    entry.revisions.push(revision);
    entry.tags = tags;
    entry.updatedAt = command.recordedAt;
    entry.analysisState = "stale";
    entry.commands.push(command);
    return Promise.resolve(entry);
  }
  tombstone(
    owner: string,
    id: string,
    deletedAt: Date,
    command: MutationCommand,
  ) {
    const entry = this.entries.find(
      (item) =>
        item.ownerAccountId === owner && item._id === id && !item.deleted,
    );
    if (!entry) return Promise.resolve(null);
    entry.deleted = true;
    entry.deletedAt = deletedAt;
    entry.updatedAt = deletedAt;
    entry.commands.push(command);
    return Promise.resolve(entry);
  }
}

const configuration = {
  JOURNAL_ENCRYPTION_KEY: Buffer.alloc(32, 1),
  JOURNAL_ENCRYPTION_KEY_ID: "test-v1",
  JOURNAL_IDEMPOTENCY_HMAC_KEY: Buffer.alloc(32, 2),
} as ServiceConfiguration;
const owner = "11111111-1111-4111-8111-111111111111";
const otherOwner = "22222222-2222-4222-8222-222222222222";
const request = (
  body: unknown,
  account = owner,
  key = "command-key-00001",
  revision?: number,
) => ({
  headers: {
    "idempotency-key": key,
    ...(revision ? { "if-match-revision": String(revision) } : {}),
  },
  principal: { accountId: account, roles: ["USER"] },
  body,
});
const payload = {
  clientEntryId: "33333333-3333-4333-8333-333333333333",
  occurredAt: "2026-01-01T00:00:00.000Z",
  content: { text: "private entry" },
  tags: ["daily"],
};
const service = (store = new MemoryStore()) => ({
  store,
  value: new JournalService(store, new JournalEncryption(configuration)),
});

void test("encrypts at rest and supports exact mutation retries", async () => {
  const subject = service();
  const created = await subject.value.create(request(payload));
  assert.equal(created.content.text, "private entry");
  const ciphertext = subject.store.entries[0]?.revisions[0]?.content.ciphertext;
  assert.ok(Buffer.isBuffer(ciphertext));
  assert.equal(ciphertext.includes(Buffer.from("private entry")), false);
  const duplicate = await subject.value.create(request(payload));
  assert.equal(duplicate.id, created.id);
  const revised = await subject.value.revise(
    request({ content: { text: "revised" } }, owner, "revision-key-001", 1),
    created.id,
  );
  assert.equal(revised.currentRevision, 2);
  await subject.value.revise(
    request(
      { content: { text: "later" }, tags: ["later"] },
      owner,
      "revision-key-004",
      2,
    ),
    created.id,
  );
  const retry = await subject.value.revise(
    request({ content: { text: "revised" } }, owner, "revision-key-001", 1),
    created.id,
  );
  assert.deepEqual(retry, revised);
  assert.deepEqual(await subject.value.create(request(payload)), created);
  await assert.rejects(
    () =>
      subject.value.revise(
        request({ content: { text: "changed" } }, owner, "revision-key-001", 1),
        created.id,
      ),
    ConflictException,
  );
});

void test("allows only one concurrent writer for a revision", async () => {
  const subject = service();
  const created = await subject.value.create(request(payload));
  const results = await Promise.allSettled([
    subject.value.revise(
      request({ content: { text: "first" } }, owner, "revision-key-002", 1),
      created.id,
    ),
    subject.value.revise(
      request({ content: { text: "second" } }, owner, "revision-key-003", 1),
      created.id,
    ),
  ]);
  assert.equal(
    results.filter((result) => result.status === "fulfilled").length,
    1,
  );
  const rejected = results.find((result) => result.status === "rejected");
  assert.ok(
    rejected?.status === "rejected" &&
      rejected.reason instanceof HttpException &&
      rejected.reason.getStatus() === 412,
  );
});

void test("fails closed across owners and hides tombstones", async () => {
  const subject = service();
  const created = await subject.value.create(request(payload));
  await assert.rejects(
    () => subject.value.detail(request({}, otherOwner), created.id),
    NotFoundException,
  );
  const tombstone = await subject.value.remove(
    request({}, owner, "deletion-key-001"),
    created.id,
  );
  assert.equal(tombstone.deleted, true);
  assert.deepEqual(
    await subject.value.remove(
      request({}, owner, "deletion-key-001"),
      created.id,
    ),
    tombstone,
  );
  assert.equal((await subject.value.list(request({}), {})).items.length, 0);
  await assert.rejects(
    () => subject.value.list(request({}), { includeDeleted: "true" }),
    BadRequestException,
  );
  await assert.rejects(
    () => subject.value.detail(request({}), created.id),
    NotFoundException,
  );
});

void test("rejects a revision beyond the bounded history before persistence", async () => {
  const subject = service();
  const created = await subject.value.create(request(payload));
  const entry = subject.store.entries[0];
  assert.ok(entry);
  entry.currentRevision = 200;

  await assert.rejects(
    () =>
      subject.value.revise(
        request(
          { content: { text: "one revision too many" } },
          owner,
          "revision-limit-001",
          200,
        ),
        created.id,
      ),
    (error: unknown) =>
      error instanceof ConflictException && error.getStatus() === 409,
  );
});
