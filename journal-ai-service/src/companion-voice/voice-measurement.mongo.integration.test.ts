import assert from "node:assert/strict";
import test from "node:test";
import { randomUUID } from "node:crypto";
import { createRequire } from "node:module";
import { MongoClient, type Db } from "mongodb";
import { MongoMemoryReplSet } from "mongodb-memory-server";
import { MongoVoiceMeasurementStore } from "./voice-measurement-store.js";
import { voicePilotSchema } from "./voice-pilot.js";
import { MongoVoiceLedger, VoiceLedgerError } from "./voice-ledger.js";
const require = createRequire(import.meta.url);
const capacityMigration =
  require("../../migrations/013_companion_voice_quota.cjs") as {
    up(db: Db): Promise<void>;
  };
const migration =
  require("../../migrations/020_voice_measurement_campaigns.cjs") as {
    up(db: Db): Promise<void>;
    down(db: Db): Promise<void>;
  };
const denied = (e: unknown) =>
  e instanceof VoiceLedgerError && e.code === "QUOTA_EXHAUSTED";
const at = new Date("2030-01-01T00:00:00Z");
const pilot = () =>
  voicePilotSchema.parse({
    startsAt: at.toISOString(),
    endsAt: "2030-01-01T00:30:00Z",
    ownerIds: [randomUUID(), randomUUID()],
    maxTurns: 6,
    maxSessionSeconds: 240,
    measurement: {
      campaignId: randomUUID(),
      maxSessions: 2,
      maxConcurrentSessions: 1,
      maxTurns: 12,
      maxVoiceSeconds: 480,
      maxTtsCharacters: 4000,
      voiceId: "8e8f222d-c817-4cc5-822b-8bf76ca7e98d",
    },
  });

void test("measurement reservations use real replica-set transactions", async (t) => {
  const external = process.env.JOURNAL_INTEGRATION_MONGODB_URI;
  const server = external
    ? undefined
    : await MongoMemoryReplSet.create({ replSet: { count: 1 } });
  const uri = external ?? server?.getUri();
  assert.ok(uri);
  const clients = [new MongoClient(uri), new MongoClient(uri)];
  await Promise.all(clients.map((c) => c.connect()));
  const names: string[] = [];
  t.after(async () => {
    try {
      for (const name of names) await clients[0]?.db(name).dropDatabase();
    } finally {
      await Promise.all(clients.map((c) => c.close()));
      await server?.stop();
    }
  });
  const fixture = async () => {
    const [first, second] = clients;
    assert.ok(first && second);
    const name = `voice_measurement_verify_${String(Date.now())}_${String(names.length)}`;
    names.push(name);
    const db = first.db(name);
    await capacityMigration.up(db);
    await migration.up(db);
    await (
      require("../../migrations/025_companion_voice_minutes_quota.cjs") as {
        up(db: Db): Promise<void>;
      }
    ).up(db);
    const config = pilot();
    const a = new MongoVoiceMeasurementStore(first, name, config);
    const b = new MongoVoiceMeasurementStore(second, name, config);
    const reserve = async (
      store: MongoVoiceMeasurementStore,
      id: string,
      now = at,
    ) => {
      const transaction = (store === b ? second : first).startSession();
      try {
        await transaction.withTransaction(() =>
          store.reserve(id, now, transaction),
        );
      } finally {
        await transaction.endSession();
      }
    };
    const release = async (id: string) => {
      const transaction = first.startSession();
      try {
        await transaction.withTransaction(() =>
          a.release(
            {
              voiceSessionId: id,
              state: "FAILED",
              dispatchState: "STOPPED",
              settledAt: at,
            },
            at,
            transaction,
          ),
        );
      } finally {
        await transaction.endSession();
      }
    };
    return { a, b, config, db, reserve, release, first, name };
  };
  await t.test(
    "missing campaign denies; provision is immutable/idempotent and absent config cannot bypass it",
    async () => {
      const f = await fixture();
      await assert.rejects(f.reserve(f.a, randomUUID()), denied);
      await f.a.provision(at);
      await f.a.provision(at);
      const evidence = await f.a.preflight(at);
      assert.equal(evidence.reservedSessions, 0);
      for (const owner of f.config.ownerIds)
        assert.equal(JSON.stringify(evidence).includes(owner), false);
      assert.equal(
        await f.db
          .collection("ai_voice_measurement_campaigns")
          .countDocuments(),
        1,
      );
      const altered = new MongoVoiceMeasurementStore(f.first, f.name, {
        ...f.config,
        maxTurns: 5,
      });
      await assert.rejects(altered.provision(at), denied);
      await assert.rejects(f.reserve(altered, randomUUID()), denied);
      await assert.rejects(
        f.reserve(
          new MongoVoiceMeasurementStore(f.first, f.name),
          randomUUID(),
        ),
        denied,
      );
      await assert.rejects(migration.down(f.db));
      await f.db
        .collection<{ _id: string }>("ai_voice_measurement_campaigns")
        .updateOne(
          {
            _id:
              evidence.configHash === f.a.policy?.configHash
                ? f.a.policy.campaignId
                : "",
          },
          { $set: { "caps.ttsCharacters": 3999 } },
        );
      await assert.rejects(f.a.preflight(at), denied);
    },
  );
  await t.test(
    "campaign admission, product quota and concurrency commit atomically and reconcile only confirmed settlement",
    async () => {
      const f = await fixture();
      await f.a.provision(at);
      const [left, right] = clients;
      assert.ok(left && right);
      const ledgers = [
        new MongoVoiceLedger(left, f.name, f.config),
        new MongoVoiceLedger(right, f.name, f.config),
      ];
      const input = (owner: string, now = at) => ({
        voiceSessionId: randomUUID(),
        ownerId: owner,
        keyHash: Buffer.from(randomUUID()).toString("base64url").slice(0, 43),
        fingerprint: "f".repeat(43),
        now,
        entitlement: {
          packageCode: "PLUS" as const,
          source: "DEMO" as const,
          entitlementVersion: "synthetic-v1",
          checkedAt: now,
          validUntil: new Date(now.getTime() + 3600000),
        },
      });
      const requests = f.config.ownerIds.map((owner) => input(owner));
      const results = await Promise.allSettled(
        requests.map((request, i) => {
          const ledger = ledgers[i];
          assert.ok(ledger);
          return ledger.reserve(request);
        }),
      );
      assert.equal(results.filter((r) => r.status === "fulfilled").length, 1);
      for (const result of results)
        if (result.status === "rejected")
          assert.equal(denied(result.reason), true);
      const accepted = results.find((r) => r.status === "fulfilled");
      assert.ok(accepted?.status === "fulfilled");
      const session = accepted.value;
      assert.equal(session.admittedSeconds, 240);
      const ledger = ledgers[0];
      assert.ok(ledger);
      const retry = requests.find((r) => r.ownerId === session.ownerId);
      assert.ok(retry);
      assert.equal(
        (await ledger.reserve(retry)).voiceSessionId,
        session.voiceSessionId,
      );
      const changed = new MongoVoiceLedger(right, f.name, {
        ...f.config,
        maxTurns: 5,
      });
      await assert.rejects(
        changed.renewAuthority(session.ownerId, session.voiceSessionId, at),
        denied,
      );
      await ledger.requestEnd(
        session.ownerId,
        session.voiceSessionId,
        "PROVIDER_FAILURE",
        at,
      );
      await assert.rejects(
        ledger.settle(
          session.voiceSessionId,
          null,
          "PROVIDER_CONFIRMED_STOPPED",
          at,
        ),
      );
      const remainingOwner = f.config.ownerIds.find(
        (owner) => owner !== session.ownerId,
      );
      assert.ok(remainingOwner);
      await assert.rejects(ledger.reserve(input(remainingOwner)), denied);
      await ledger.reconcile(at);
      const snapshot = await ledger.snapshot(session.ownerId, "PLUS", at);
      assert.equal(snapshot.remainingSessions, 10);
      assert.equal(snapshot.remainingSeconds, 600);
      const second = await ledger.reserve(input(remainingOwner));
      assert.equal(second.admittedSeconds, 240);
      await ledger.claimDispatch(second.voiceSessionId, at);
      const old = new MongoVoiceLedger(right, f.name);
      await assert.rejects(
        old.renewAuthority(remainingOwner, second.voiceSessionId, at),
        denied,
      );
      await ledger.requestEnd(
        remainingOwner,
        second.voiceSessionId,
        "AGENT_FAILURE",
        at,
      );
      await ledger.reconcile(new Date(at.getTime() + 20000));
      assert.equal(
        (await f.a.snapshot(at))?.reservations.filter(
          (r) => r.releasedAt === null,
        ).length,
        1,
      );
      const rows = (await f.a.snapshot(at))?.reservations;
      assert.ok(rows);
      assert.equal(rows.length, 2);
      assert.equal(
        rows.reduce((n, r) => n + r.ttsCharacters, 0),
        4000,
      );
    },
  );
  await t.test(
    "independent clients race for concurrency one; duplicate reserve consumes once",
    async () => {
      const f = await fixture();
      await f.a.provision(at);
      const ids = [randomUUID(), randomUUID()];
      assert.ok(ids[0] && ids[1]);
      const results = await Promise.allSettled([
        f.reserve(f.a, ids[0]),
        f.reserve(f.b, ids[1]),
      ]);
      assert.equal(results.filter((r) => r.status === "fulfilled").length, 1);
      const row = await f.a.snapshot(at);
      assert.ok(row?.reservations[0]);
      const winner = row.reservations[0].voiceSessionId;
      await Promise.all([f.reserve(f.a, winner), f.reserve(f.b, winner)]);
      assert.equal((await f.a.snapshot(at))?.reservations.length, 1);
      await assert.rejects(
        f.reserve(f.b, randomUUID(), new Date(at.getTime() + 250000)),
        denied,
      );
      await f.release(winner);
      const second = randomUUID();
      await f.reserve(f.b, second);
      await f.release(second);
      await assert.rejects(f.reserve(f.a, randomUUID()), denied);
      const spent = (await f.a.snapshot(at))?.reservations;
      assert.ok(spent);
      assert.equal(
        spent.reduce((n, r) => n + r.turns, 0),
        12,
      );
      assert.equal(
        spent.reduce((n, r) => n + r.voiceSeconds, 0),
        480,
      );
      assert.equal(
        spent.reduce((n, r) => n + r.ttsCharacters, 0),
        4000,
      );
    },
  );
  await t.test(
    "worker restart, turn races and cancelled speech cannot restore reservations",
    async () => {
      const f = await fixture();
      await f.a.provision(at);
      const id = randomUUID();
      await f.reserve(f.a, id);
      const claims = await Promise.allSettled([
        f.a.claimWorker(id, at),
        f.b.claimWorker(id, at),
      ]);
      assert.equal(claims.filter((r) => r.status === "fulfilled").length, 1);
      await assert.rejects(f.b.claimWorker(id, at), denied);
      for (let sequence = 1; sequence <= 6; sequence++) {
        const turn = randomUUID();
        const turns = await Promise.allSettled([
          f.a.reserveTurn(id, turn, sequence, at),
          f.b.reserveTurn(id, randomUUID(), sequence, at),
        ]);
        assert.equal(turns.filter((r) => r.status === "fulfilled").length, 1);
        const row = await f.a.snapshot(at);
        const held = row?.reservations[0]?.turnReservations.find(
          (r) => r.sequence === sequence,
        );
        assert.ok(held);
        if (sequence <= 4)
          await f.a.reserveSpeech(id, held.turnId, randomUUID(), 500, at);
        else
          await assert.rejects(
            f.a.reserveSpeech(id, held.turnId, randomUUID(), 1, at),
            denied,
          );
      }
      await assert.rejects(f.b.reserveTurn(id, randomUUID(), 7, at), denied);
      const row = await f.a.snapshot(at);
      assert.equal(row?.reservations[0]?.speechReservations.length, 4);
      assert.equal(row.reservations[0].turnReservations.length, 6);
      await f.release(id);
      await assert.rejects(
        f.a.reserveSpeech(id, randomUUID(), randomUUID(), 1, at),
        denied,
      );
    },
  );
  await t.test(
    "schema rejects private fields and expired windows never reserve even before TTL",
    async () => {
      const f = await fixture();
      await f.a.provision(at);
      const row = await f.a.snapshot(at);
      assert.ok(row);
      await assert.rejects(
        f.db
          .collection<{ _id: string }>("ai_voice_measurement_campaigns")
          .updateOne(
            { _id: row._id },
            { $set: { transcript: "synthetic-canary" } },
          ),
      );
      await assert.rejects(
        f.reserve(f.a, randomUUID(), f.config.endsAt),
        denied,
      );
      assert.equal((await f.a.snapshot(at))?.reservations.length, 0);
    },
  );
});
