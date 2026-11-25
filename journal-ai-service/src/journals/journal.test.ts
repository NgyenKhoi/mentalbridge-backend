import assert from "node:assert/strict";
import test from "node:test";
import {
  BadRequestException,
  ConflictException,
  HttpException,
  NotFoundException,
  ServiceUnavailableException,
  UnauthorizedException,
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
  deleteAnalyses() {
    return Promise.resolve();
  }
}

const configuration = {
  JOURNAL_ENCRYPTION_KEY: Buffer.alloc(32, 1),
  JOURNAL_ENCRYPTION_KEY_ID: "single-key",
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
  mood: "GOOD" as const,
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
  assert.equal(created.mood, "GOOD");
  const ciphertext = subject.store.entries[0]?.revisions[0]?.content.ciphertext;
  assert.ok(Buffer.isBuffer(ciphertext));
  assert.equal(ciphertext.includes(Buffer.from("private entry")), false);
  const encryptedMood =
    subject.store.entries[0]?.revisions[0]?.mood?.ciphertext;
  assert.ok(Buffer.isBuffer(encryptedMood));
  assert.equal(encryptedMood.includes(Buffer.from("GOOD")), false);
  const duplicate = await subject.value.create(request(payload));
  assert.equal(duplicate.id, created.id);
  const revised = await subject.value.revise(
    request(
      { content: { text: "revised" }, mood: "LOW" },
      owner,
      "revision-key-001",
      1,
    ),
    created.id,
  );
  assert.equal(revised.currentRevision, 2);
  assert.equal(revised.mood, "LOW");
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
    request(
      { content: { text: "revised" }, mood: "LOW" },
      owner,
      "revision-key-001",
      1,
    ),
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

void test("keeps legacy clients compatible and preserves mood when revise omits it", async () => {
  const subject = service();
  const legacyPayload = { ...payload, mood: undefined };
  const legacy = await subject.value.create(request(legacyPayload));
  assert.equal(legacy.mood, null);

  const created = await subject.value.create(
    request(
      { ...payload, clientEntryId: "44444444-4444-4444-8444-444444444444" },
      owner,
      "command-key-00002",
    ),
  );
  const revised = await subject.value.revise(
    request(
      { content: { text: "mood stays encrypted" } },
      owner,
      "revision-key-005",
      1,
    ),
    created.id,
  );
  assert.equal(revised.mood, "GOOD");
});

void test("rejects blank journal text and unsupported moods", async () => {
  const subject = service();
  await assert.rejects(
    () =>
      subject.value.create(request({ ...payload, content: { text: "   " } })),
    BadRequestException,
  );
  await assert.rejects(
    () => subject.value.create(request({ ...payload, mood: "EXCITED" })),
    BadRequestException,
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

void test("Journal completion evidence is content-free, read-only and uses server creation time", async () => {
  const store = new MemoryStore();
  const encryption = new JournalEncryption(configuration);
  const value = new JournalService(store, encryption);
  const created = await value.create(request(payload));
  const before = JSON.stringify(store.entries);
  encryption.decrypt = () => {
    throw new Error("Completion evidence must not decrypt Journal text");
  };
  encryption.decryptMood = () => {
    throw new Error("Completion evidence must not decrypt mood");
  };
  const evidence = await value.completionEvidence(request({}), created.id);
  assert.deepEqual(evidence, {
    schemaVersion: "journal-entry-completion-evidence-v1",
    subjectUserId: owner,
    journalId: created.id,
    state: "RECORDED",
    fact: {
      ownerDomain: "JOURNAL_AI",
      kind: "JOURNAL_ENTRY",
      factId: created.id,
      factRevision: "1",
      occurredAt: created.createdAt,
    },
    verifiedAt: evidence.verifiedAt,
  });
  assert.notEqual(evidence.fact.occurredAt, payload.occurredAt);
  assert.ok(new Date(evidence.verifiedAt) >= new Date(created.createdAt));
  assert.equal(JSON.stringify(store.entries), before);
  for (const privateValue of [payload.content.text, payload.mood, "daily"])
    assert.equal(JSON.stringify(evidence).includes(privateValue), false);
  assert.deepEqual(Object.keys(evidence).sort(), [
    "fact",
    "journalId",
    "schemaVersion",
    "state",
    "subjectUserId",
    "verifiedAt",
  ]);
});

void test("Journal edits and save retries preserve one immutable creation fact", async () => {
  const subject = service();
  const created = await subject.value.create(request(payload));
  const first = await subject.value.completionEvidence(request({}), created.id);
  const retry = await subject.value.create(request(payload));
  assert.equal(retry.id, created.id);
  await subject.value.revise(
    request(
      { content: { text: "private edited reflection" }, mood: "LOW" },
      owner,
      "journal-evidence-revise-001",
      1,
    ),
    created.id,
  );
  const edited = await subject.value.completionEvidence(
    request({}),
    created.id,
  );
  assert.deepEqual(edited.fact, first.fact);
  assert.equal(edited.fact?.factRevision, "1");
  assert.equal(subject.store.entries[0]?.currentRevision, 2);
  assert.equal(subject.store.entries.length, 1);
});

void test("Journal completion distinguishes owned deletion without revealing another owner's entry", async () => {
  const subject = service();
  const missingId = "44444444-4444-4444-8444-444444444444";
  const missing = await subject.value.completionEvidence(
    request({}),
    missingId,
  );
  assert.equal(missing.state, "MISSING");
  assert.equal(missing.fact, null);
  const created = await subject.value.create(request(payload));
  const other = await subject.value.completionEvidence(
    request({}, otherOwner),
    created.id,
  );
  assert.equal(other.state, "MISSING");
  assert.equal(other.subjectUserId, otherOwner);
  assert.equal(other.fact, null);
  await subject.value.remove(
    request({}, owner, "journal-evidence-delete-001"),
    created.id,
  );
  const deleted = await subject.value.completionEvidence(
    request({}),
    created.id,
  );
  assert.equal(deleted.state, "DELETED");
  assert.equal(deleted.fact, null);
  assert.equal(JSON.stringify(deleted).includes(payload.content.text), false);
});

void test("Journal completion validates the exact ID and authenticated owner before a store read", async () => {
  const subject = service();
  let reads = 0;
  subject.store.find = () => {
    reads += 1;
    throw new Error("Must not read for invalid context");
  };
  await assert.rejects(
    subject.value.completionEvidence(request({}), "invalid"),
    BadRequestException,
  );
  await assert.rejects(
    subject.value.completionEvidence(
      { headers: {}, body: {} },
      payload.clientEntryId,
    ),
    UnauthorizedException,
  );
  assert.equal(reads, 0);
});

void test("Journal completion owner outage is unavailable rather than false absence", async () => {
  const subject = service();
  subject.store.find = () =>
    Promise.reject(new Error("synthetic store outage"));
  await assert.rejects(
    subject.value.completionEvidence(request({}), payload.clientEntryId),
    ServiceUnavailableException,
  );
});

void test("Journal completion rejects unattributed, wrong-ID, invalid or future creation metadata", async () => {
  const subject = service();
  const created = await subject.value.create(request(payload));
  const entry = subject.store.entries[0];
  assert.ok(entry);
  subject.store.find = () => Promise.resolve(entry);
  entry.ownerAccountId = otherOwner;
  await assert.rejects(
    subject.value.completionEvidence(request({}), created.id),
    ServiceUnavailableException,
  );
  entry.ownerAccountId = owner;
  entry._id = payload.clientEntryId;
  await assert.rejects(
    subject.value.completionEvidence(request({}), created.id),
    ServiceUnavailableException,
  );
  entry._id = created.id;
  entry.createdAt = new Date(Number.NaN);
  await assert.rejects(
    subject.value.completionEvidence(request({}), created.id),
    ServiceUnavailableException,
  );
  entry.createdAt = new Date(Date.now() + 60_000);
  await assert.rejects(
    subject.value.completionEvidence(request({}), created.id),
    ServiceUnavailableException,
  );
});
