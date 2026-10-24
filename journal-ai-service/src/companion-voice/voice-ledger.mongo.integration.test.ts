import assert from "node:assert/strict";
import test from "node:test";
import { randomUUID } from "node:crypto";
import { createRequire } from "node:module";
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { createInterface } from "node:readline";
import { MongoClient, MongoServerError, type Db } from "mongodb";
import { MongoMemoryReplSet } from "mongodb-memory-server";

import {
  MongoVoiceLedger,
  VoiceLedgerError,
  type ReserveVoiceInput,
  type VoiceEntitlementSnapshot,
  type VoiceGuard,
  type VoiceSession,
} from "./voice-ledger.js";
import { VOICE_RETENTION_MS } from "./voice-policy.js";

const require = createRequire(import.meta.url);
const migration = require("../../migrations/013_companion_voice_quota.cjs") as {
  up(database: Db): Promise<void>;
};
const start = new Date("2026-10-23T16:59:55.000Z");
const at = (milliseconds: number) => new Date(start.getTime() + milliseconds);
const entitlement = (
  now = start,
  packageCode: "PLUS" | "PREMIUM" = "PLUS",
): VoiceEntitlementSnapshot => ({
  packageCode,
  source: "PAID",
  entitlementVersion: "test-entitlement-v1",
  checkedAt: now,
  validUntil: new Date(now.getTime() + 86_400_000),
});
const input = (ownerId = randomUUID(), now = start): ReserveVoiceInput => ({
  voiceSessionId: randomUUID(),
  ownerId,
  keyHash: Buffer.from(randomUUID()).toString("base64url").slice(0, 43),
  fingerprint: "f".repeat(43),
  now,
  entitlement: entitlement(now),
});
const guard = (session: VoiceSession, sequence: number): VoiceGuard => {
  assert.ok(session.attemptId);
  assert.ok(session.leaseId);
  return {
    attemptId: session.attemptId,
    leaseId: session.leaseId,
    generation: session.capacityGeneration,
    sequence,
  };
};
const code = (expected: string) => (error: unknown) =>
  error instanceof VoiceLedgerError && error.code === expected;

const processRaceProgram = `
import { createInterface } from "node:readline";
import { createRequire } from "node:module";
const lines = createInterface({ input: process.stdin });
const iterator = lines[Symbol.asyncIterator]();
let client;
let LedgerError;
try {
  const first = await iterator.next();
  const data = JSON.parse(first.value);
  const require = createRequire(data.ledgerUrl);
  const { MongoClient } = require("mongodb");
  const { MongoVoiceLedger, VoiceLedgerError } = await import(data.ledgerUrl);
  LedgerError = VoiceLedgerError;
  client = new MongoClient(data.uri);
  await client.connect();
  const ledger = new MongoVoiceLedger(client, data.databaseName);
  process.stdout.write("READY\\n");
  await iterator.next();
  const request = data.request;
  request.now = new Date(request.now);
  request.entitlement.checkedAt = new Date(request.entitlement.checkedAt);
  request.entitlement.validUntil = new Date(request.entitlement.validUntil);
  await ledger.recordStartAttempt(request.ownerId, request.now, request.keyHash);
  const session = await ledger.reserve(request);
  process.stdout.write(JSON.stringify({ id: session.voiceSessionId }) + "\\n");
} catch (error) {
  process.stdout.write(JSON.stringify({ code: LedgerError && error instanceof LedgerError ? error.code : "CHILD_FAILURE" }) + "\\n");
} finally {
  await client?.close();
  lines.close();
}
`;

const startRaceProcess = (payload: object) => {
  const child: ChildProcessWithoutNullStreams = spawn(
    process.execPath,
    ["--input-type=module", "-e", processRaceProgram],
    { shell: false, stdio: "pipe" },
  );
  let markReady: () => void = () => undefined;
  let failReady: (error: Error) => void = () => undefined;
  const ready = new Promise<void>((resolve, reject) => {
    markReady = resolve;
    failReady = reject;
  });
  const completed = new Promise<{ id?: string; code?: string }>(
    (resolve, reject) => {
      let result: { id?: string; code?: string } | undefined;
      const output = createInterface({ input: child.stdout });
      output.on("line", (line) => {
        if (line === "READY") markReady();
        else {
          try {
            result = JSON.parse(line) as { id?: string; code?: string };
          } catch {
            reject(new Error("Invalid child process result"));
          }
        }
      });
      child.stderr.resume();
      child.once("error", () => {
        const error = new Error("Voice race child failed to start");
        failReady(error);
        reject(error);
      });
      child.once("exit", (status) => {
        output.close();
        if (status === 0 && result) resolve(result);
        else reject(new Error("Voice race child did not complete"));
        failReady(new Error("Voice race child exited before ready"));
      });
    },
  );
  child.stdin.write(`${JSON.stringify(payload)}\n`);
  return { child, ready, completed };
};

void test("voice ledger enforces quota and fencing across independent Mongo clients", async (t) => {
  const externalUri = process.env.JOURNAL_INTEGRATION_MONGODB_URI;
  const mongo = externalUri
    ? undefined
    : await MongoMemoryReplSet.create({ replSet: { count: 1 } });
  const uri = externalUri ?? mongo?.getUri();
  assert.ok(uri);
  const clients = [new MongoClient(uri), new MongoClient(uri)];
  await Promise.all(clients.map((client) => client.connect()));
  const databases: Db[] = [];
  t.after(async () => {
    try {
      for (const database of databases) await database.dropDatabase();
    } finally {
      await Promise.all(clients.map((client) => client.close()));
      await mongo?.stop();
    }
  });
  let fixtureNumber = 0;
  const fixture = async () => {
    const databaseName = `voice_ledger_verify_${String(Date.now())}_${String(fixtureNumber++)}`;
    assert.match(databaseName, /^voice_ledger_verify_\d+_\d+$/);
    const first = clients[0];
    const second = clients[1];
    assert.ok(first && second);
    const database = first.db(databaseName);
    databases.push(database);
    assert.equal((await database.listCollections().toArray()).length, 0);
    await migration.up(database);
    return {
      databaseName,
      database,
      a: new MongoVoiceLedger(first, databaseName),
      b: new MongoVoiceLedger(second, databaseName),
    };
  };

  await t.test(
    "atomic capacity permits only two owners and one session per owner",
    async () => {
      const { a, b } = await fixture();
      const requests = [input(), input(), input()];
      const results = await Promise.allSettled(
        requests.map((value, index) => (index % 2 ? a : b).reserve(value)),
      );
      assert.equal(
        results.filter((value) => value.status === "fulfilled").length,
        2,
      );
      const rejected = results.find((value) => value.status === "rejected");
      assert.ok(
        rejected?.status === "rejected" &&
          code("GLOBAL_CAPACITY_FULL")(rejected.reason),
      );
      const occupied = await a.availability(
        requests[0]?.ownerId ?? randomUUID(),
        start,
      );
      assert.equal(occupied.globalOccupied, 2);
      const { a: c, b: d } = await fixture();
      const owner = randomUUID();
      const sameOwner = await Promise.allSettled([
        c.reserve(input(owner)),
        d.reserve(input(owner)),
      ]);
      assert.equal(
        sameOwner.filter((value) => value.status === "fulfilled").length,
        1,
      );
      const denied = sameOwner.find((value) => value.status === "rejected");
      assert.ok(
        denied?.status === "rejected" && code("SESSION_LIMIT")(denied.reason),
      );
    },
  );

  await t.test(
    "two Node processes atomically contend for the final global slot",
    { timeout: 30_000 },
    async (processTest) => {
      const { a, databaseName } = await fixture();
      await a.reserve(input());
      const requests = [input(), input()];
      const processes = requests.map((request) =>
        startRaceProcess({
          uri,
          databaseName,
          request,
          ledgerUrl: new URL("./voice-ledger.js", import.meta.url).href,
        }),
      );
      processTest.after(() => {
        for (const { child } of processes)
          if (child.exitCode === null) child.kill();
      });
      const completion = Promise.all(processes.map((value) => value.completed));
      await Promise.all(processes.map((value) => value.ready));
      for (const { child } of processes) child.stdin.end("GO\n");
      const results = await completion;
      assert.equal(results.filter((value) => value.id).length, 1);
      assert.equal(
        results.filter((value) => value.code === "GLOBAL_CAPACITY_FULL").length,
        1,
      );
      assert.equal(
        (await a.availability(requests[0]?.ownerId ?? randomUUID(), start))
          .globalOccupied,
        2,
      );
    },
  );

  await t.test(
    "accepted idempotency replay does not reserve twice and mismatched intent fails",
    async () => {
      const { a, b, database } = await fixture();
      const request = input();
      const accepted = await Promise.all(
        Array.from({ length: 5 }, async (_, index) => {
          const ledger = index % 2 ? a : b;
          await ledger.recordStartAttempt(
            request.ownerId,
            request.now,
            request.keyHash,
          );
          return ledger.reserve({ ...request, voiceSessionId: randomUUID() });
        }),
      );
      const one = accepted[0];
      const two = accepted[1];
      assert.ok(one && two);
      assert.equal(one.voiceSessionId, two.voiceSessionId);
      assert.equal(
        new Set(accepted.map((value) => value.voiceSessionId)).size,
        1,
      );
      assert.equal(
        await database.collection("ai_voice_sessions").countDocuments(),
        1,
      );
      assert.equal(
        (await a.snapshot(request.ownerId, "PLUS", start)).reservedSeconds,
        300,
      );
      assert.equal(
        (await b.availability(request.ownerId, start)).dailyAttempts,
        1,
      );
      assert.equal(
        (await b.availability(request.ownerId, start)).globalOccupied,
        1,
      );
      await assert.rejects(
        b.reserve({ ...request, fingerprint: "x".repeat(43) }),
        code("IDEMPOTENCY_CONFLICT"),
      );
      assert.equal(
        (await b.replay(request.ownerId, request.keyHash, start))
          ?.voiceSessionId,
        one.voiceSessionId,
      );
      assert.equal(
        await a.findOwned(randomUUID(), one.voiceSessionId, start),
        null,
      );
    },
  );

  await t.test(
    "rate counters include denied attempts and remain atomic across processes",
    async () => {
      const { a, b } = await fixture();
      const owner = randomUUID();
      const rateStart = new Date("2026-10-23T10:00:00.000Z");
      const rateAt = (minutes: number) =>
        new Date(rateStart.getTime() + minutes * 60_000);
      const results = await Promise.allSettled(
        Array.from({ length: 5 }, (_, i) =>
          (i % 2 ? a : b).recordStartAttempt(owner, rateStart, input().keyHash),
        ),
      );
      assert.equal(
        results.filter((result) => result.status === "fulfilled").length,
        3,
      );
      assert.equal(
        results.filter(
          (result) =>
            result.status === "rejected" && code("RATE_LIMITED")(result.reason),
        ).length,
        2,
      );
      assert.equal(
        (await a.snapshot(owner, "PLUS", rateStart)).rateLimited,
        true,
      );
      assert.equal(
        (await a.snapshot(owner, "PLUS", rateAt(1))).rateLimited,
        false,
      );
      for (let i = 1; i <= 7; i += 1)
        await a.recordStartAttempt(owner, rateAt(i), input().keyHash);
      await assert.rejects(
        b.recordStartAttempt(owner, rateAt(8), input().keyHash),
        code("RATE_LIMITED"),
      );
      const state = await b.availability(owner, rateAt(8));
      assert.equal(state.dailyAttempts, 10);
      assert.equal(state.rateLimited, true);
      assert.equal(
        (await a.snapshot(owner, "PLUS", start)).reservedSessions,
        0,
      );
    },
  );

  await t.test(
    "no reply fully refunds quota and exact settlement replay cannot double release",
    async () => {
      const { a, b } = await fixture();
      const request = input();
      const reserved = await a.reserve(request);
      const ending = await b.requestEnd(
        request.ownerId,
        reserved.voiceSessionId,
        "PROVIDER_FAILURE",
        at(100),
      );
      assert.equal(ending.state, "ENDING");
      assert.equal(
        (await a.availability(request.ownerId, at(100))).ownerOccupied,
        true,
      );
      const [one, two] = await Promise.all([
        a.settle(reserved.voiceSessionId, null, "NEVER_DISPATCHED", at(100)),
        b.settle(reserved.voiceSessionId, null, "NEVER_DISPATCHED", at(100)),
      ]);
      assert.equal(one.state, "FAILED");
      assert.equal(two.chargedSeconds, 0);
      const quota = await a.snapshot(request.ownerId, "PLUS", at(100));
      assert.equal(quota.usedSessions, 0);
      assert.equal(quota.reservedSeconds, 0);
      assert.equal(quota.remainingSeconds, 600);
      assert.equal(quota.globalAvailable, 2);
    },
  );

  await t.test(
    "first trusted reply consumes one session and disconnected intervals do not bill",
    async () => {
      const { a, b } = await fixture();
      const request = input();
      const reserved = await a.reserve(request);
      const dispatched = await a.claimDispatch(reserved.voiceSessionId, start);
      await a.transition(
        dispatched.voiceSessionId,
        guard(dispatched, 1),
        "ACTIVE",
        at(100),
      );
      await a.firstReply(
        dispatched.voiceSessionId,
        guard(dispatched, 2),
        at(200),
      );
      await b.firstReply(
        dispatched.voiceSessionId,
        guard(dispatched, 2),
        at(200),
      );
      await a.transition(
        dispatched.voiceSessionId,
        guard(dispatched, 3),
        "RECONNECTING",
        at(400),
      );
      await b.transition(
        dispatched.voiceSessionId,
        guard(dispatched, 4),
        "ACTIVE",
        at(1000),
      );
      await a.requestEnd(
        request.ownerId,
        dispatched.voiceSessionId,
        "USER_ENDED",
        at(1300),
      );
      const settled = await b.settle(
        dispatched.voiceSessionId,
        guard(dispatched, 5),
        "PROVIDER_CONFIRMED_STOPPED",
        at(2000),
      );
      assert.equal(settled.activeMilliseconds, 500);
      assert.equal(settled.chargedSeconds, 1);
      const quota = await a.snapshot(request.ownerId, "PLUS", at(2000));
      assert.equal(quota.usedSessions, 1);
      assert.equal(quota.usedSeconds, 1);
      assert.equal(quota.reservedSeconds, 0);
      await a.settle(
        dispatched.voiceSessionId,
        guard(dispatched, 5),
        "PROVIDER_CONFIRMED_STOPPED",
        at(2100),
      );
      assert.equal(
        (await a.snapshot(request.ownerId, "PLUS", at(2100))).usedSeconds,
        1,
      );
      await assert.rejects(
        a.firstReply(
          dispatched.voiceSessionId,
          { ...guard(dispatched, 6), leaseId: randomUUID() },
          at(2200),
        ),
        code("STALE_ATTEMPT"),
      );
      await assert.rejects(
        a.transition(
          dispatched.voiceSessionId,
          guard(dispatched, 6),
          "ACTIVE",
          at(2200),
        ),
        code("INVALID_SESSION_STATE"),
      );
    },
  );

  await t.test(
    "expired authority holds dispatched capacity, fences progress, and never revives",
    async () => {
      const { a, b } = await fixture();
      const request = input();
      const reserved = await a.reserve(request);
      const dispatched = await a.claimDispatch(reserved.voiceSessionId, start);
      await a.transition(
        dispatched.voiceSessionId,
        guard(dispatched, 1),
        "ACTIVE",
        at(100),
      );
      await a.firstReply(
        dispatched.voiceSessionId,
        guard(dispatched, 2),
        at(200),
      );
      assert.equal((await b.reauthorizationCandidates(at(5000))).length, 1);
      const result = await b.reconcile(at(12_000));
      assert.equal(result.ended, 1);
      const ending = await b.findOwned(
        request.ownerId,
        dispatched.voiceSessionId,
        at(12_000),
      );
      assert.ok(ending);
      assert.equal(ending.state, "ENDING");
      assert.equal(ending.chargedSeconds, 10);
      assert.equal(
        (await a.availability(request.ownerId, at(12_000))).ownerOccupied,
        true,
      );
      await assert.rejects(
        a.renewAuthority(
          request.ownerId,
          dispatched.voiceSessionId,
          at(12_000),
        ),
        code("INVALID_SESSION_STATE"),
      );
      await assert.rejects(
        a.settle(
          dispatched.voiceSessionId,
          null,
          "NEVER_DISPATCHED",
          at(12_000),
        ),
        code("INVALID_SESSION_STATE"),
      );
      const settled = await a.settle(
        dispatched.voiceSessionId,
        guard(dispatched, 3),
        "PROVIDER_CONFIRMED_STOPPED",
        at(13_000),
      );
      assert.equal(settled.chargedSeconds, 10);
      assert.equal(
        (await b.availability(request.ownerId, at(13_000))).ownerOccupied,
        false,
      );
    },
  );

  await t.test(
    "pre-dispatch crash releases safely and privacy expiry quarantines unknown media",
    async () => {
      const { a, b, database } = await fixture();
      const unissued = await a.reserve(input());
      const unknownRequest = input();
      const unknown = await a.reserve(unknownRequest);
      await a.claimDispatch(unknown.voiceSessionId, start);
      await b.reconcile(at(11_000));
      assert.equal(
        (
          await a.findOwned(
            unissued.ownerId,
            unissued.voiceSessionId,
            at(11_000),
          )
        )?.state,
        "EXPIRED",
      );
      assert.equal(
        (await a.availability(unissued.ownerId, at(11_000))).globalOccupied,
        1,
      );
      const expiredAt = at(VOICE_RETENTION_MS);
      const result = await b.reconcile(expiredAt);
      assert.equal(result.quarantined, 1);
      assert.equal(
        await database.collection("ai_voice_sessions").countDocuments(),
        0,
      );
      assert.equal(
        await database.collection("ai_voice_daily_usage").countDocuments(),
        0,
      );
      const capacity = await database
        .collection("ai_voice_capacity")
        .findOne({});
      const serialized = JSON.stringify(capacity);
      assert.equal(serialized.includes(unknownRequest.ownerId), false);
      assert.equal(serialized.includes(unknown.voiceSessionId), false);
      assert.match(serialized, /QUARANTINED/);
      assert.equal(
        (await a.availability(unknownRequest.ownerId, expiredAt))
          .globalOccupied,
        1,
      );
      const second = await b.reconcile(at(VOICE_RETENTION_MS + 1000));
      assert.deepEqual(second, { ended: 0, purged: 0, quarantined: 0 });
    },
  );

  await t.test(
    "accepted replay and fresh authority cannot extend the unissued dispatch cutoff",
    async () => {
      const { a, b } = await fixture();
      const request = input();
      const reserved = await a.reserve(request);
      const replay = await b.replay(request.ownerId, request.keyHash, at(9000));
      assert.equal(replay?.voiceSessionId, reserved.voiceSessionId);
      const renewed = await a.renewAuthority(
        request.ownerId,
        reserved.voiceSessionId,
        at(9000),
        at(19_000),
      );
      assert.equal(renewed.authorityExpiresAt.getTime(), at(19_000).getTime());
      await assert.rejects(
        b.claimDispatch(reserved.voiceSessionId, at(10_000)),
        code("INVALID_SESSION_STATE"),
      );
      await assert.rejects(
        b.renewAuthority(request.ownerId, reserved.voiceSessionId, at(10_000)),
        code("SESSION_EXPIRED"),
      );
      await b.reconcile(at(10_000));
      const ended = await a.findOwned(
        request.ownerId,
        reserved.voiceSessionId,
        at(10_000),
      );
      assert.equal(ended?.state, "EXPIRED");
      const quota = await a.snapshot(request.ownerId, "PLUS", at(10_000));
      assert.equal(quota.reservedSessions, 0);
      assert.equal(quota.reservedSeconds, 0);
      assert.equal(quota.usedSessions, 0);
      assert.equal(quota.globalAvailable, 2);
    },
  );

  await t.test(
    "entitlement versions accept the full owner contract length and reject overflow",
    async () => {
      const { a } = await fixture();
      const request = input();
      request.entitlement.entitlementVersion = "v".repeat(128);
      const reserved = await a.reserve(request);
      assert.equal(reserved.entitlement.entitlementVersion.length, 128);
      const overflow = input();
      overflow.entitlement.entitlementVersion = "v".repeat(129);
      await assert.rejects(a.reserve(overflow), code("INVALID_REQUEST"));
      assert.equal(
        (await a.availability(request.ownerId, start)).globalOccupied,
        1,
      );
    },
  );

  await t.test(
    "failed final capacity write rolls back session and quota reservation",
    async () => {
      const { a, database } = await fixture();
      await database.command({
        collMod: "ai_voice_capacity",
        validator: { _id: "reject-all-updates" },
        validationLevel: "strict",
        validationAction: "error",
      });
      const request = input();
      await assert.rejects(
        a.reserve(request),
        (error: unknown) =>
          error instanceof MongoServerError && error.code === 121,
      );
      assert.equal(
        await database.collection("ai_voice_sessions").countDocuments(),
        0,
      );
      assert.equal(
        await database.collection("ai_voice_daily_usage").countDocuments(),
        0,
      );
      await migration.up(database);
      assert.equal(
        (await a.availability(request.ownerId, start)).globalOccupied,
        0,
      );
      await a.reserve(request);
      assert.equal(
        (await a.snapshot(request.ownerId, "PLUS", start)).reservedSessions,
        1,
      );
    },
  );

  await t.test(
    "daily successful-session ceilings and remaining seconds survive new reservations",
    async () => {
      const { a, database } = await fixture();
      const owner = randomUUID();
      for (let i = 0; i < 2; i += 1) {
        const offset = i * 1000;
        const reserved = await a.reserve(input(owner, at(offset)));
        const dispatched = await a.claimDispatch(
          reserved.voiceSessionId,
          at(offset),
        );
        await a.transition(
          dispatched.voiceSessionId,
          guard(dispatched, 1),
          "ACTIVE",
          at(offset + 100),
        );
        await a.firstReply(
          dispatched.voiceSessionId,
          guard(dispatched, 2),
          at(offset + 200),
        );
        await a.requestEnd(
          owner,
          dispatched.voiceSessionId,
          "USER_ENDED",
          at(offset + 201),
        );
        await a.settle(
          dispatched.voiceSessionId,
          guard(dispatched, 3),
          "PROVIDER_CONFIRMED_STOPPED",
          at(offset + 202),
        );
      }
      await assert.rejects(
        a.reserve(input(owner, at(2500))),
        code("QUOTA_EXHAUSTED"),
      );
      const upgraded = input(owner, at(2500));
      upgraded.entitlement = entitlement(at(2500), "PREMIUM");
      const third = await a.reserve(upgraded);
      assert.equal(third.admittedSeconds, 600);
      assert.equal(
        (await a.snapshot(owner, "PREMIUM", at(2500))).usedSessions,
        2,
      );
      const remainingOwner = randomUUID();
      await a.recordStartAttempt(remainingOwner, start, input().keyHash);
      await database
        .collection("ai_voice_daily_usage")
        .updateOne(
          { ownerId: remainingOwner },
          { $set: { usedSessions: 1, usedSeconds: 550 } },
        );
      const remaining = await a.reserve(input(remainingOwner));
      assert.equal(remaining.admittedSeconds, 50);
      assert.equal(remaining.hardStopAt.getTime() - start.getTime(), 50_000);
    },
  );

  await t.test(
    "authority renewal is independently fenced and does not consume worker sequence",
    async () => {
      const { a, b } = await fixture();
      const request = input();
      request.authorityExpiresAt = at(3000);
      const reserved = await a.reserve(request);
      const dispatched = await a.claimDispatch(
        reserved.voiceSessionId,
        at(1000),
      );
      assert.equal(dispatched.leaseExpiresAt?.getTime(), at(3000).getTime());
      const renewed = await b.renewAuthority(
        request.ownerId,
        dispatched.voiceSessionId,
        at(2000),
        at(5000),
      );
      assert.equal(renewed.leaseExpiresAt?.getTime(), at(5000).getTime());
      assert.equal(renewed.lastSequence, 0);
      await a.transition(
        dispatched.voiceSessionId,
        guard(dispatched, 1),
        "ACTIVE",
        at(2500),
      );
      await assert.rejects(
        a.renewAuthority(request.ownerId, dispatched.voiceSessionId, at(5000)),
        code("SESSION_EXPIRED"),
      );
      await b.reconcile(at(5000));
      assert.equal(
        (
          await a.findOwned(
            request.ownerId,
            dispatched.voiceSessionId,
            at(5000),
          )
        )?.state,
        "ENDING",
      );
      await assert.rejects(
        a.renewAuthority(request.ownerId, dispatched.voiceSessionId, at(5001)),
        code("INVALID_SESSION_STATE"),
      );
    },
  );

  await t.test(
    "idle and reconnect deadlines close accounting while holding possible media",
    async () => {
      const { a } = await fixture();
      const reserved = await a.reserve(input());
      const dispatched = await a.claimDispatch(reserved.voiceSessionId, start);
      await a.transition(
        dispatched.voiceSessionId,
        guard(dispatched, 1),
        "ACTIVE",
        at(100),
      );
      await a.firstReply(
        dispatched.voiceSessionId,
        guard(dispatched, 2),
        at(200),
      );
      for (let offset = 5000; offset <= 60_000; offset += 5000)
        await a.renewAuthority(
          reserved.ownerId,
          dispatched.voiceSessionId,
          at(offset),
        );
      await a.reconcile(at(61_000));
      const ended = await a.findOwned(
        reserved.ownerId,
        dispatched.voiceSessionId,
        at(61_000),
      );
      assert.ok(ended);
      assert.equal(ended.terminalReason, "IDLE_TIMEOUT");
      assert.equal(ended.chargedSeconds, 60);
      assert.equal(
        (await a.availability(reserved.ownerId, at(61_000))).ownerOccupied,
        true,
      );
      const second = await a.reserve(input());
      const disconnected = await a.claimDispatch(second.voiceSessionId, start);
      await a.transition(
        disconnected.voiceSessionId,
        guard(disconnected, 1),
        "ACTIVE",
        at(100),
      );
      await a.firstReply(
        disconnected.voiceSessionId,
        guard(disconnected, 2),
        at(200),
      );
      await a.transition(
        disconnected.voiceSessionId,
        guard(disconnected, 3),
        "RECONNECTING",
        at(400),
      );
      for (let offset = 5000; offset <= 30_000; offset += 5000)
        await a.renewAuthority(
          second.ownerId,
          disconnected.voiceSessionId,
          at(offset),
        );
      await a.reconcile(at(31_000));
      const expired = await a.findOwned(
        second.ownerId,
        disconnected.voiceSessionId,
        at(31_000),
      );
      assert.ok(expired);
      assert.equal(expired.terminalReason, "RECONNECT_TIMEOUT");
      assert.equal(expired.chargedSeconds, 1);
      assert.equal(
        (await a.availability(second.ownerId, at(31_000))).globalOccupied,
        2,
      );
    },
  );

  await t.test(
    "midnight and plan upgrade preserve existing admission and charge original day",
    async () => {
      const { a } = await fixture();
      const request = input();
      const reserved = await a.reserve(request);
      const dispatched = await a.claimDispatch(reserved.voiceSessionId, start);
      await a.transition(
        dispatched.voiceSessionId,
        guard(dispatched, 1),
        "ACTIVE",
        at(100),
      );
      await a.firstReply(
        dispatched.voiceSessionId,
        guard(dispatched, 2),
        at(200),
      );
      const renewed = await a.renewLease(
        dispatched.voiceSessionId,
        guard(dispatched, 3),
        at(4000),
        entitlement(at(4000), "PREMIUM"),
      );
      assert.equal(renewed.admittedSeconds, 300);
      assert.equal(
        renewed.hardStopAt.toISOString(),
        reserved.hardStopAt.toISOString(),
      );
      const nextDay = await a.snapshot(request.ownerId, "PREMIUM", at(6000));
      assert.equal(nextDay.quotaDay, "2026-10-24");
      assert.equal(nextDay.remainingSeconds, 1800);
      assert.equal(nextDay.ownerActive, true);
      await a.requestEnd(
        request.ownerId,
        dispatched.voiceSessionId,
        "USER_ENDED",
        at(6100),
      );
      await a.settle(
        dispatched.voiceSessionId,
        guard(dispatched, 4),
        "PROVIDER_CONFIRMED_STOPPED",
        at(6200),
      );
      assert.equal(
        (await a.snapshot(request.ownerId, "PREMIUM", start)).usedSeconds,
        6,
      );
      assert.equal(
        (await a.snapshot(request.ownerId, "PREMIUM", at(6200))).usedSeconds,
        0,
      );
    },
  );

  await t.test(
    "migration is repeatable and rejects content fields without TTL capacity release",
    async () => {
      const { a, database } = await fixture();
      const reserved = await a.reserve(input());
      await migration.up(database);
      const indexes = await database.collection("ai_voice_capacity").indexes();
      assert.equal(
        indexes.some((index) => index.expireAfterSeconds !== undefined),
        false,
      );
      const sessionIndexes = await database
        .collection("ai_voice_sessions")
        .indexes();
      assert.equal(
        sessionIndexes.some((index) => index.expireAfterSeconds !== undefined),
        false,
      );
      await assert.rejects(
        database
          .collection<{ _id: string }>("ai_voice_sessions")
          .updateOne(
            { _id: reserved.voiceSessionId },
            { $set: { transcript: "sensitive-content" } },
          ),
        (error: unknown) =>
          error instanceof MongoServerError && error.code === 121,
      );
      await assert.rejects(
        database
          .collection("ai_voice_capacity")
          .updateOne({}, { $set: { "slots.0.audio": "sensitive-content" } }),
        (error: unknown) =>
          error instanceof MongoServerError && error.code === 121,
      );
      assert.equal(
        (await a.availability(reserved.ownerId, start)).ownerOccupied,
        true,
      );
    },
  );
});
