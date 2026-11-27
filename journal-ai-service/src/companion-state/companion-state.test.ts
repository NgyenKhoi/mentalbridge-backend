import assert from "node:assert/strict";
import test from "node:test";
import {
  BadRequestException,
  ConflictException,
  HttpException,
  NotFoundException,
  ServiceUnavailableException,
} from "@nestjs/common";

import type { ServiceConfiguration } from "../configuration/configuration.js";
import {
  CompanionStateCrypto,
  CompanionStateService,
  type CompanionStateDocument,
  type CompanionStateRepository,
} from "./companion-state.js";

class MemoryRepository implements CompanionStateRepository {
  readonly documents = new Map<string, CompanionStateDocument>();
  conflicts = 0;

  find(ownerAccountId: string) {
    const document = this.documents.get(ownerAccountId);
    return Promise.resolve(document ? { ...document } : null);
  }

  save(document: CompanionStateDocument, expectedRevision: number) {
    if (this.conflicts > 0) {
      this.conflicts -= 1;
      return Promise.resolve(false);
    }
    const current = this.documents.get(document.ownerAccountId);
    if ((current?.revision ?? 0) !== expectedRevision)
      return Promise.resolve(false);
    this.documents.set(document.ownerAccountId, document);
    return Promise.resolve(true);
  }
}

const configuration = {
  JOURNAL_ENCRYPTION_KEY: Buffer.alloc(32, 51),
  JOURNAL_ENCRYPTION_KEY_ID: "single-key",
} as ServiceConfiguration;
const ownerId = "11111111-1111-4111-8111-111111111111";
const otherOwnerId = "22222222-2222-4222-8222-222222222222";
const now = new Date("2026-11-27T10:00:00.000Z");

const request = (body: unknown = {}, accountId = ownerId) => ({
  headers: {},
  principal: { accountId, roles: ["USER"] },
  body,
});

const subject = () => {
  const repository = new MemoryRepository();
  const service = new CompanionStateService(
    repository,
    new CompanionStateCrypto(configuration),
    { now: () => new Date(now) },
  );
  return { repository, service };
};

const commitment = {
  kind: "REST",
  minutes: 10,
  dueOn: "2026-11-28",
  source: "CHAT",
};

const hasCode = (code: string) => (error: unknown) =>
  error instanceof HttpException &&
  (error.getResponse() as { code?: string }).code === code;

void test("returns defaults with revision 0 when nothing is stored", async () => {
  const { service } = subject();
  assert.deepEqual(await service.get(request()), {
    focus: null,
    chapters: [],
    commitments: [],
    ui: { guide: null, seen: [] },
    revision: 0,
  });
  await assert.rejects(
    service.get(request({}, "not-a-uuid")),
    (error: unknown) =>
      error instanceof HttpException && error.getStatus() === 401,
  );
});

void test("rejects empty, unknown, unbounded and malformed patches", async () => {
  const { service } = subject();
  for (const body of [
    {},
    { unknown: true },
    { focus: { goal: "SLEEP" } },
    { focus: { goal: "DIAGNOSIS", chosenOn: "2026-11-27" } },
    { chapter: { startedOn: "2026-11-27", rating: "CURED" } },
    { chapter: { startedOn: "2026-11-27", note: "free text" } },
    { guide: "x".repeat(6145) },
    { seenAdd: [] },
    { seenAdd: ["Upper-Case"] },
    {
      seenAdd: Array.from({ length: 51 }, (_, index) => `key-${String(index)}`),
    },
  ])
    await assert.rejects(service.update(request(body)), BadRequestException);
});

void test("merges focus, chapters, guide and seen keys and stores them encrypted", async () => {
  const { repository, service } = subject();
  await service.update(
    request({
      focus: { goal: "SLEEP", chosenOn: "2026-11-27" },
      chapter: { startedOn: "2026-11-27", goal: "SLEEP" },
      guide: '{"step":2}',
      seenAdd: ["guide:intro", "guide:intro", "moment:focus"],
    }),
  );
  const merged = await service.update(
    request({
      chapter: { startedOn: "2026-11-27", rating: "LIGHTER" },
      seenAdd: ["moment:focus", "moment:day-14"],
    }),
  );
  assert.equal(merged.revision, 2);
  assert.deepEqual(merged.focus, { goal: "SLEEP", chosenOn: "2026-11-27" });
  assert.deepEqual(merged.chapters, [
    {
      startedOn: "2026-11-27",
      goal: "SLEEP",
      rating: "LIGHTER",
      updatedAt: now.toISOString(),
    },
  ]);
  assert.equal(merged.ui.guide, '{"step":2}');
  assert.deepEqual(merged.ui.seen, [
    "guide:intro",
    "moment:focus",
    "moment:day-14",
  ]);
  assert.deepEqual(await service.get(request()), merged);

  const raw = JSON.stringify([...repository.documents.values()]);
  for (const plaintext of ["SLEEP", "LIGHTER", "guide:intro", "step"])
    assert.equal(raw.includes(plaintext), false);

  const cleared = await service.update(request({ focus: null, guide: null }));
  assert.equal(cleared.focus, null);
  assert.equal(cleared.ui.guide, null);
  assert.equal(cleared.chapters.length, 1);

  assert.equal((await service.get(request({}, otherOwnerId))).revision, 0);
});

void test("keeps only the 12 most recent chapters and 300 most recent seen keys", async () => {
  const { service } = subject();
  for (let day = 1; day <= 13; day += 1)
    await service.update(
      request({
        chapter: { startedOn: `2026-01-${String(day).padStart(2, "0")}` },
      }),
    );
  for (let batch = 0; batch < 7; batch += 1)
    await service.update(
      request({
        seenAdd: Array.from(
          { length: 50 },
          (_, index) => `seen:${String(batch)}-${String(index)}`,
        ),
      }),
    );
  const state = await service.get(request());
  assert.equal(state.chapters.length, 12);
  assert.equal(state.chapters[0]?.startedOn, "2026-01-02");
  assert.equal(state.ui.seen.length, 300);
  assert.equal(state.ui.seen[0], "seen:1-0");
  assert.equal(state.ui.seen.at(-1), "seen:6-49");
});

void test("fails closed when the stored payload is tampered with", async () => {
  const { repository, service } = subject();
  await service.update(request({ guide: "state" }));
  const document = repository.documents.get(ownerId);
  assert.ok(document);
  repository.documents.set(ownerId, { ...document, revision: 9 });
  await assert.rejects(service.get(request()), ServiceUnavailableException);
});

void test("fails closed when another owner's payload is swapped in, on read and on patch", async () => {
  const { repository, service } = subject();
  await service.update(request({ guide: "owner-a" }));
  await service.update(request({ guide: "owner-b" }, otherOwnerId));
  const ownerDocument = repository.documents.get(ownerId);
  const otherDocument = repository.documents.get(otherOwnerId);
  assert.ok(ownerDocument && otherDocument);
  const swapped = {
    ...otherDocument,
    encryptedPayload: ownerDocument.encryptedPayload,
    iv: ownerDocument.iv,
    tag: ownerDocument.tag,
  };
  repository.documents.set(otherOwnerId, swapped);
  await assert.rejects(
    service.get(request({}, otherOwnerId)),
    ServiceUnavailableException,
  );
  await assert.rejects(
    service.update(request({ guide: "reset" }, otherOwnerId)),
    ServiceUnavailableException,
  );
  assert.equal(repository.documents.get(otherOwnerId), swapped);
});

void test("bounds focus and chapter dates to UTC today -400 to +2 days", async () => {
  const { service } = subject();
  for (const body of [
    { focus: { goal: "SLEEP", chosenOn: "2025-10-22" } },
    { focus: { goal: "SLEEP", chosenOn: "2026-11-30" } },
    { chapter: { startedOn: "2025-10-22" } },
    { chapter: { startedOn: "2026-11-30" } },
  ])
    await assert.rejects(service.update(request(body)), BadRequestException);
  const state = await service.update(
    request({
      focus: { goal: "SLEEP", chosenOn: "2026-11-29" },
      chapter: { startedOn: "2025-10-23" },
    }),
  );
  assert.equal(state.focus?.chosenOn, "2026-11-29");
  assert.equal(state.chapters[0]?.startedOn, "2025-10-23");
});

void test("a re-added seen key counts as most recently added", async () => {
  const { service } = subject();
  await service.update(request({ seenAdd: ["a", "b", "c"] }));
  const state = await service.update(request({ seenAdd: ["a"] }));
  assert.deepEqual(state.ui.seen, ["b", "c", "a"]);
});

void test("retries once on a revision conflict, then returns 409", async () => {
  const { repository, service } = subject();
  repository.conflicts = 1;
  assert.equal((await service.update(request({ guide: "a" }))).revision, 1);
  repository.conflicts = 2;
  await assert.rejects(
    service.update(request({ guide: "b" })),
    (error: unknown) =>
      error instanceof ConflictException &&
      (error.getResponse() as { code?: string }).code === undefined,
  );
  assert.equal((await service.get(request())).ui.guide, "a");
});

void test("validates commitment fields and the due-date window", async () => {
  const { service } = subject();
  for (const body of [
    { ...commitment, minutes: 20 },
    { ...commitment, kind: "DIAGNOSE" },
    { ...commitment, source: "AI" },
    { ...commitment, dueOn: "2026-11-24" },
    { ...commitment, dueOn: "2026-12-06" },
    { ...commitment, status: "DONE" },
    { kind: "REST", dueOn: "2026-11-28", source: "CHAT" },
  ])
    await assert.rejects(
      service.createCommitment(request(body)),
      BadRequestException,
    );
  for (const dueOn of ["2026-11-25", "2026-12-05"])
    assert.equal(
      (await service.createCommitment(request({ ...commitment, dueOn })))
        .status,
      "OPEN",
    );
  assert.equal(
    (await service.createCommitment(request({ ...commitment, minutes: null })))
      .minutes,
    null,
  );
});

void test("limits open commitments to three and resolves each once", async () => {
  const { service } = subject();
  const created = [];
  for (let index = 0; index < 3; index += 1)
    created.push(await service.createCommitment(request(commitment)));
  const first = created[0];
  assert.ok(first);
  assert.deepEqual(first, {
    id: first.id,
    ...commitment,
    status: "OPEN",
    createdAt: now.toISOString(),
    resolvedAt: null,
  });
  await assert.rejects(
    service.createCommitment(request(commitment)),
    hasCode("COMMITMENT_LIMIT"),
  );

  await assert.rejects(
    service.resolveCommitment(request({ status: "OPEN" }), first.id),
    BadRequestException,
  );
  await assert.rejects(
    service.resolveCommitment(request({ status: "DONE" }), "not-a-uuid"),
    BadRequestException,
  );
  const done = await service.resolveCommitment(
    request({ status: "DONE" }),
    first.id,
  );
  assert.equal(done.status, "DONE");
  assert.equal(done.resolvedAt, now.toISOString());
  await assert.rejects(
    service.resolveCommitment(request({ status: "NOT_YET" }), first.id),
    hasCode("COMMITMENT_RESOLVED"),
  );
  await assert.rejects(
    service.resolveCommitment(
      request({ status: "DONE" }, otherOwnerId),
      first.id,
    ),
    NotFoundException,
  );
  await assert.rejects(
    service.resolveCommitment(
      request({ status: "DONE" }),
      "33333333-3333-4333-8333-333333333333",
    ),
    NotFoundException,
  );
  assert.equal(
    (await service.createCommitment(request(commitment))).status,
    "OPEN",
  );
});

void test("keeps the 30 most recent commitments by dropping the oldest resolved first", async () => {
  const { service } = subject();
  const open = await service.createCommitment(request(commitment));
  const resolvedIds: string[] = [];
  for (let index = 0; index < 30; index += 1) {
    const created = await service.createCommitment(request(commitment));
    await service.resolveCommitment(request({ status: "SWAPPED" }), created.id);
    resolvedIds.push(created.id);
  }
  const state = await service.get(request());
  assert.equal(state.commitments.length, 30);
  assert.equal(state.commitments[0]?.id, open.id);
  assert.equal(
    state.commitments.some((item) => item.id === resolvedIds[0]),
    false,
  );
  assert.equal(state.commitments.at(-1)?.id, resolvedIds.at(-1));
});
