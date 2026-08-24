import assert from "node:assert/strict";
import test from "node:test";
import { NotFoundException, UnauthorizedException } from "@nestjs/common";
import { JournalService, type Entry, type JournalStore } from "./journal.js";

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
  async list(
    owner: string,
    limit: number,
    cursor?: string,
    includeDeleted = false,
  ) {
    const rows = this.entries
      .filter(
        (entry) =>
          entry.ownerAccountId === owner &&
          (includeDeleted || !entry.deleted) &&
          (!cursor || entry._id < cursor),
      )
      .sort((left, right) => right._id.localeCompare(left._id));
    return Promise.resolve({
      rows: rows.slice(0, limit),
      hasMore: rows.length > limit,
    });
  }
  update(owner: string, id: string, update: Partial<Entry>) {
    const entry =
      this.entries.find(
        (candidate) =>
          candidate.ownerAccountId === owner && candidate._id === id,
      ) ?? null;
    if (!entry) return Promise.resolve(null);
    Object.assign(entry, update);
    return Promise.resolve(entry);
  }
}
const owner = "11111111-1111-4111-8111-111111111111";
const otherOwner = "22222222-2222-4222-8222-222222222222";
const request = (body: unknown, account = owner, key?: string) => ({
  headers: { ...(key ? { "idempotency-key": key } : {}) },
  principal: { accountId: account, roles: ["USER"] },
  body,
});
const payload = {
  clientEntryId: "33333333-3333-4333-8333-333333333333",
  occurredAt: "2026-01-01T00:00:00.000Z",
  content: "private entry",
  tags: ["daily"],
};

void test("creates, revises, deletes and deduplicates commands", async () => {
  const service = new JournalService(new MemoryStore());
  const created = await service.create(request(payload, owner, "create-1"));
  const duplicate = await service.create(request(payload, owner, "create-1"));
  assert.equal(duplicate.id, created.id);
  const revised = await service.revise(
    request({ content: "revised" }, owner, "revise-1"),
    created.id,
  );
  assert.equal(revised.currentRevision, 2);
  const retry = await service.revise(
    request({ content: "different retry body" }, owner, "revise-1"),
    created.id,
  );
  assert.equal(retry.currentRevision, 2);
  const tombstone = await service.remove(
    request({}, owner, "delete-1"),
    created.id,
  );
  assert.equal(tombstone.deleted, true);
  await assert.rejects(
    () => service.detail(request({}, owner), created.id),
    NotFoundException,
  );
});
void test("fails closed for missing or foreign owners", async () => {
  const service = new JournalService(new MemoryStore());
  await assert.rejects(
    () => service.create({ headers: {}, body: payload }),
    UnauthorizedException,
  );
  const created = await service.create(request(payload));
  await assert.rejects(
    () => service.detail(request({}, otherOwner), created.id),
    NotFoundException,
  );
});
void test("does not expose deleted entries in the default list", async () => {
  const service = new JournalService(new MemoryStore());
  const created = await service.create(request(payload));
  await service.remove(request({}, owner, "delete-2"), created.id);
  const hidden = await service.list(request({}, owner), {});
  assert.equal(hidden.items.length, 0);
  const visible = await service.list(request({}), { includeDeleted: "true" });
  assert.equal(visible.items.length, 1);
});
void test("preserves persistence failures instead of returning a false success", async () => {
  const failingStore = new MemoryStore();
  failingStore.create = () => Promise.reject(new Error("Mongo unavailable"));
  const service = new JournalService(failingStore);
  await assert.rejects(
    () => service.create(request(payload)),
    /Mongo unavailable/,
  );
});
