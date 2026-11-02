import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { createRequire } from "node:module";
import test from "node:test";
import { MongoClient, MongoServerError, type Db } from "mongodb";
import { MongoMemoryReplSet } from "mongodb-memory-server";
import { VoiceAccessError, VoiceAccessService } from "./voice-access.js";
import { VoiceAuthorizationError } from "./voice-authorization.js";
import {
  MongoVoiceLedger,
  VoiceLedgerError,
  type VoiceSession,
} from "./voice-ledger.js";
import {
  MongoVoiceMediaStore,
  type VoiceMediaDocument,
} from "./voice-media-store.js";
import {
  VoiceMediaError,
  type VoiceMediaProvider,
  type VoiceMediaReference,
} from "./voice-media-provider.js";
import {
  VoiceSessionsService,
  voiceWorkerSignalSchema,
} from "./voice-sessions.js";

const require = createRequire(import.meta.url);
const migrations = [
  require("../../migrations/013_companion_voice_quota.cjs"),
  require("../../migrations/014_companion_voice_media.cjs"),
] as { up(db: Db): Promise<void>; down(db: Db): Promise<void> }[];
const rejected = (code: string) => (error: unknown) =>
  error instanceof VoiceLedgerError && error.code === code;
const barrier = () => {
  let release: () => void = () => undefined;
  const wait = new Promise<void>((resolve) => {
    release = resolve;
  });
  return { wait, release };
};

const waitFor = async (ready: () => boolean) => {
  const deadline = Date.now() + 5000;
  while (!ready()) {
    if (Date.now() > deadline)
      throw new Error("Synthetic provider checkpoint was not reached");
    await new Promise<void>((resolve) => {
      setTimeout(resolve, 5);
    });
  }
};

class SyntheticMedia implements VoiceMediaProvider {
  readonly rooms = new Map<
    string,
    { providerDispatchId: string | null; ref: VoiceMediaReference }
  >();
  creates = 0;
  dispatches = 0;
  stops = 0;
  tokens = 0;
  ownerConnected = true;
  agentConnected = true;
  audioPublished = true;
  failStop = false;
  failCreate = false;
  failDispatch = false;
  pauseCreate: Promise<void> | null = null;
  pauseDispatch: Promise<void> | null = null;
  pauseConnection: Promise<void> | null = null;
  pauseInspection: Promise<void> | null = null;
  inspections = 0;
  async createRoom(ref: VoiceMediaReference) {
    this.creates += 1;
    await this.pauseCreate;
    if (this.failCreate) throw new VoiceMediaError();
    this.rooms.set(ref.roomName, { providerDispatchId: null, ref });
  }
  async dispatch(ref: VoiceMediaReference) {
    this.dispatches += 1;
    await this.pauseDispatch;
    if (this.failDispatch) throw new VoiceMediaError();
    const room = this.rooms.get(ref.roomName);
    assert.ok(room);
    room.providerDispatchId = `AD_${ref.dispatchId}`;
    return room.providerDispatchId;
  }
  async inspect(ref: VoiceMediaReference) {
    this.inspections++;
    await this.pauseInspection;
    const room = this.rooms.get(ref.roomName);
    return {
      roomExists: !!room,
      providerDispatchId: room?.providerDispatchId ?? null,
      ownerConnected: this.ownerConnected,
      agentConnected: this.agentConnected,
      agentAudioPublished: this.audioPublished,
    };
  }
  async connection(ref: VoiceMediaReference, now: Date) {
    this.tokens += 1;
    await this.pauseConnection;
    return {
      voiceSessionId: ref.voiceSessionId,
      transport: "LIVEKIT_CLOUD" as const,
      roomName: ref.roomName,
      agentIdentity: ref.agentIdentity,
      serverUrl: "wss://synthetic.livekit.cloud",
      participantToken: "synthetic-memory-only-participant-token",
      tokenExpiresAt: new Date(
        Math.min(now.getTime() + 60_000, ref.hardStopAt.getTime()),
      ).toISOString(),
      hardStopAt: ref.hardStopAt.toISOString(),
      audioOnly: true as const,
    };
  }
  stop(ref: VoiceMediaReference) {
    this.stops += 1;
    if (this.failStop) throw new VoiceMediaError();
    this.rooms.delete(ref.roomName);
    return Promise.resolve();
  }
}

void test("Voice media lifecycle remains atomic, fenced and content-free on real MongoDB", async (t) => {
  const mongo = process.env.JOURNAL_INTEGRATION_MONGODB_URI
    ? undefined
    : await MongoMemoryReplSet.create({ replSet: { count: 1 } });
  const uri = process.env.JOURNAL_INTEGRATION_MONGODB_URI ?? mongo?.getUri();
  assert.ok(uri);
  const a = new MongoClient(uri);
  const b = new MongoClient(uri);
  await Promise.all([a.connect(), b.connect()]);
  const databases: Db[] = [];
  t.after(async () => {
    try {
      for (const db of databases) await db.dropDatabase();
    } finally {
      await Promise.all([a.close(), b.close()]);
      await mongo?.stop();
    }
  });
  let count = 0;
  const fixture = async () => {
    const name = `voice_media_verify_${String(Date.now())}_${String(count++)}`;
    assert.match(name, /^voice_media_verify_\d+_\d+$/);
    const db = a.db(name);
    databases.push(db);
    assert.equal((await db.listCollections().toArray()).length, 0);
    for (const migration of migrations) await migration.up(db);
    let now = new Date();
    const clock = () => new Date(now);
    let aiConsent = true;
    let voiceConsent = true;
    let paid = true;
    let pauseAuthority: Promise<void> | null = null;
    let entitlementMilliseconds = 86_400_000;
    let consentDecisionAge = 0;
    let entitlementDecisionAge = 0;
    const media = new SyntheticMedia();
    const secret = Buffer.alloc(32, 72);
    const checks: string[] = [];
    const build = (client: MongoClient) => {
      const ledger = new MongoVoiceLedger(client, name);
      const store = new MongoVoiceMediaStore(client, name);
      const access = new VoiceAccessService(
        ledger,
        {
          check: async (ownerId, voiceSessionId) => {
            checks.push(voiceSessionId);
            const binding = {
              ownerId,
              voiceSessionId,
              purpose: "AI_COMPANION_VOICE" as const,
              checkedAt: clock().toISOString(),
            };
            const decision = {
              consent: {
                ...binding,
                checkedAt: new Date(
                  now.getTime() - consentDecisionAge,
                ).toISOString(),
                aiProcessingGranted: aiConsent,
                voiceProcessingGranted: voiceConsent,
                authorized: aiConsent && voiceConsent,
                voiceConsentVersion: "ai-voice-processing-capstone-v2" as const,
              },
              entitlement: {
                ...binding,
                checkedAt: new Date(
                  now.getTime() - entitlementDecisionAge,
                ).toISOString(),
                packageCode: paid ? ("PLUS" as const) : ("FREE" as const),
                source: paid ? ("PAID" as const) : ("DEFAULT_FREE" as const),
                entitlementVersion: "synthetic-owner-v1",
                ...(paid
                  ? {
                      validUntil: new Date(
                        now.getTime() + entitlementMilliseconds,
                      ).toISOString(),
                    }
                  : {}),
              },
            };
            await pauseAuthority;
            return decision;
          },
        },
        secret,
        () => true,
        clock,
      );
      return {
        ledger,
        store,
        access,
        service: new VoiceSessionsService(
          ledger,
          access,
          store,
          media,
          secret,
          clock,
        ),
      };
    };
    const left = build(a);
    const right = build(b);
    const owner = randomUUID();
    const current = async (id: string) => {
      const value = await left.ledger.findSession(id, clock());
      assert.ok(value);
      return value;
    };
    const document = async (id: string) => {
      const value = await left.store.find(id, clock());
      assert.ok(value);
      return value;
    };
    const signal = (
      session: VoiceSession,
      sequence: number,
      kind: "MEDIA_READY" | "HEARTBEAT" | "FINISHED" | "PLAYBACK_STARTED",
      payload: Record<string, unknown> = {},
    ) =>
      voiceWorkerSignalSchema.parse({
        schemaVersion: 1,
        kind,
        commandId: randomUUID(),
        voiceSessionId: session.voiceSessionId,
        attemptId: session.attemptId,
        sequence,
        issuedAt: clock().toISOString(),
        expiresAt: session.leaseExpiresAt?.toISOString(),
        payload: {
          leaseId: session.leaseId,
          ...(kind === "HEARTBEAT" ? { observedState: session.state } : {}),
          ...payload,
        },
      });
    const start = async () =>
      left.service.start(owner, randomUUID(), randomUUID());
    const claim = async (id: string) => {
      const doc = await document(id);
      await left.service.claim(id, doc.attemptId, doc.dispatchId, randomUUID());
      return current(id);
    };
    return {
      ...left,
      right,
      db,
      owner,
      media,
      clock,
      checks,
      current,
      document,
      signal,
      start,
      claim,
      advance: (ms: number) => {
        now = new Date(now.getTime() + ms);
      },
      revoke: () => {
        voiceConsent = false;
      },
      revokeAi: () => {
        aiConsent = false;
      },
      downgrade: () => {
        paid = false;
      },
      pauseAuthority: (pending: Promise<void> | null) => {
        pauseAuthority = pending;
      },
      entitlementMilliseconds: (remaining: number) => {
        entitlementMilliseconds = remaining;
      },
      decisionAges: (consent: number, entitlement: number) => {
        consentDecisionAge = consent;
        entitlementDecisionAge = entitlement;
      },
    };
  };

  const activate = async (f: Awaited<ReturnType<typeof fixture>>) => {
    const started = await f.start();
    const session = await f.claim(started.voiceSessionId);
    await f.service.control(
      session.voiceSessionId,
      f.signal(session, 1, "MEDIA_READY"),
      randomUUID(),
    );
    return f.current(session.voiceSessionId);
  };

  await t.test(
    "terminal NEW media reconciliation purges pending RAM context before marking STOPPED",
    async () => {
      const f = await fixture();
      const reserved = await f.access.reserve(
        f.owner,
        randomUUID(),
        randomUUID(),
      );
      const session = await f.ledger.claimDispatch(
        reserved.voiceSessionId,
        f.clock(),
      );
      await f.store.ensure(session, f.clock());
      const pendingContext = new Map([
        [session.voiceSessionId, ["SYNTHETIC_PENDING_TEXT_CONTEXT"]],
      ]);
      const terminated: string[] = [];
      f.service.onTermination((id) => {
        terminated.push(id);
        pendingContext.delete(id);
      });
      await f.ledger.requestEnd(
        f.owner,
        session.voiceSessionId,
        "PROVIDER_FAILURE",
        f.clock(),
      );
      assert.ok(session.attemptId && session.leaseId);
      await f.ledger.settle(
        session.voiceSessionId,
        {
          attemptId: session.attemptId,
          leaseId: session.leaseId,
          generation: session.capacityGeneration,
          sequence: session.lastSequence + 1,
        },
        "PROVIDER_CONFIRMED_STOPPED",
        f.clock(),
      );
      assert.equal((await f.current(session.voiceSessionId)).state, "FAILED");
      assert.equal((await f.document(session.voiceSessionId)).phase, "NEW");
      assert.equal(pendingContext.size, 1);
      await f.service.reconcile();
      assert.deepEqual(terminated, [session.voiceSessionId]);
      assert.equal(pendingContext.size, 0);
      assert.equal((await f.document(session.voiceSessionId)).phase, "STOPPED");
      assert.equal(f.media.creates, 0);
      assert.equal(f.media.dispatches, 0);
      await f.service.reconcile();
      assert.deepEqual(terminated, [session.voiceSessionId]);
    },
  );

  await t.test(
    "turn authorization overlaps independent owner and media reads but requires both",
    async () => {
      const f = await fixture();
      const session = await activate(f);
      const owner = barrier();
      const inspection = barrier();
      f.pauseAuthority(owner.wait);
      f.media.pauseInspection = inspection.wait;
      const checks = f.checks.length;
      const inspections = f.media.inspections;
      let resolved = false;
      const pending = f.service
        .authorizeTurn(
          session.voiceSessionId,
          session.attemptId ?? "",
          session.leaseId ?? "",
          randomUUID(),
        )
        .then((value) => {
          resolved = true;
          return value;
        });
      try {
        await waitFor(
          () =>
            f.checks.length === checks + 1 &&
            f.media.inspections === inspections + 1,
        );
        assert.equal(resolved, false);
        owner.release();
        await new Promise<void>((resolve) => setImmediate(resolve));
        assert.equal(resolved, false);
        inspection.release();
        const authority = await pending;
        assert.equal(authority.session.voiceSessionId, session.voiceSessionId);
        assert.equal(authority.consent.authorized, true);
        assert.equal(authority.entitlement.packageCode, "PLUS");
        assert.equal(f.checks.length, checks + 1);
        assert.equal(f.media.inspections, inspections + 1);
      } finally {
        owner.release();
        inspection.release();
        await pending.catch(() => undefined);
      }
    },
  );

  await t.test(
    "a slow media read cannot turn a once-fresh owner decision into authority",
    async () => {
      const f = await fixture();
      const session = await activate(f);
      const inspection = barrier();
      f.media.pauseInspection = inspection.wait;
      const checks = f.checks.length;
      const pending = f.service.authorizeTurn(
        session.voiceSessionId,
        session.attemptId ?? "",
        session.leaseId ?? "",
        randomUUID(),
      );
      const denied = assert.rejects(
        pending,
        (error: unknown) =>
          error instanceof VoiceAuthorizationError &&
          error.code === "CONSENT_UNAVAILABLE",
      );
      try {
        await waitFor(() => f.checks.length === checks + 1);
        f.advance(2001);
      } finally {
        inspection.release();
      }
      await denied;
      assert.equal((await f.current(session.voiceSessionId)).state, "FAILED");
      assert.equal(f.media.rooms.size, 0);
    },
  );

  await t.test(
    "the final persisted state check cannot extend owner decision freshness",
    async () => {
      const f = await fixture();
      const session = await activate(f);
      const findSession = f.ledger.findSession.bind(f.ledger);
      let reads = 0;
      f.ledger.findSession = async (...args) => {
        const found = await findSession(...args);
        if (++reads === 2) f.advance(2001);
        return found;
      };
      await assert.rejects(
        f.service.authorizeTurn(
          session.voiceSessionId,
          session.attemptId ?? "",
          session.leaseId ?? "",
          randomUUID(),
        ),
        (error: unknown) =>
          error instanceof VoiceAuthorizationError &&
          error.code === "CONSENT_UNAVAILABLE",
      );
      assert.equal((await f.current(session.voiceSessionId)).state, "FAILED");
      assert.equal(f.media.rooms.size, 0);
    },
  );

  for (const [consentAge, entitlementAge, code] of [
    [2001, 0, "CONSENT_UNAVAILABLE"],
    [0, 2001, "ENTITLEMENT_UNAVAILABLE"],
    [-1, 0, "CONSENT_UNAVAILABLE"],
    [0, -1, "ENTITLEMENT_UNAVAILABLE"],
  ] as const)
    await t.test(
      `parallel-read common return rejects owner ages ${String(consentAge)}/${String(entitlementAge)}`,
      async () => {
        const f = await fixture();
        const session = await activate(f);
        f.decisionAges(consentAge, entitlementAge);
        await assert.rejects(
          f.service.authorizeTurn(
            session.voiceSessionId,
            session.attemptId ?? "",
            session.leaseId ?? "",
            randomUUID(),
          ),
          (error: unknown) =>
            error instanceof VoiceAuthorizationError && error.code === code,
        );
        assert.equal((await f.current(session.voiceSessionId)).state, "FAILED");
        assert.equal(f.media.rooms.size, 0);
      },
    );

  await t.test(
    "parallel owner reads retain the exact two-second freshness boundary",
    async () => {
      const f = await fixture();
      const session = await activate(f);
      f.decisionAges(2000, 2000);
      const authority = await f.service.authorizeTurn(
        session.voiceSessionId,
        session.attemptId ?? "",
        session.leaseId ?? "",
        randomUUID(),
      );
      assert.equal(authority.session.voiceSessionId, session.voiceSessionId);
      assert.equal((await f.current(session.voiceSessionId)).state, "ACTIVE");
    },
  );

  for (const reason of ["lease", "entitlement"] as const)
    await t.test(
      `parallel reads cannot outlive ${reason} authority while media is pending`,
      async () => {
        const f = await fixture();
        const session = await activate(f);
        if (reason === "lease") f.advance(8500);
        else f.entitlementMilliseconds(1000);
        const inspection = barrier();
        f.media.pauseInspection = inspection.wait;
        const checks = f.checks.length;
        const pending = f.service.authorizeTurn(
          session.voiceSessionId,
          session.attemptId ?? "",
          session.leaseId ?? "",
          randomUUID(),
        );
        const denied = assert.rejects(pending, (error: unknown) =>
          reason === "lease"
            ? error instanceof VoiceLedgerError &&
              error.code === "SESSION_EXPIRED"
            : error instanceof VoiceAuthorizationError &&
              error.code === "ENTITLEMENT_UNAVAILABLE",
        );
        try {
          await waitFor(() => f.checks.length === checks + 1);
          f.advance(1500);
        } finally {
          inspection.release();
        }
        await denied;
        assert.notEqual(
          (await f.current(session.voiceSessionId)).state,
          "ACTIVE",
        );
        assert.equal(f.media.rooms.size, 0);
      },
    );

  for (const denial of ["consent", "room", "owner", "agent"] as const)
    await t.test(
      `parallel reads still deny ${denial} loss and clean up`,
      async () => {
        const f = await fixture();
        const session = await activate(f);
        if (denial === "consent") f.revoke();
        if (denial === "room") f.media.rooms.clear();
        if (denial === "owner") f.media.ownerConnected = false;
        if (denial === "agent") f.media.agentConnected = false;
        await assert.rejects(
          f.service.authorizeTurn(
            session.voiceSessionId,
            session.attemptId ?? "",
            session.leaseId ?? "",
            randomUUID(),
          ),
          (error: unknown) =>
            denial === "consent"
              ? error instanceof VoiceAccessError &&
                error.code === "VOICE_CONSENT_REQUIRED"
              : error instanceof VoiceLedgerError &&
                error.code === "INVALID_SESSION_STATE",
        );
        assert.equal((await f.current(session.voiceSessionId)).state, "FAILED");
        assert.equal(f.media.rooms.size, 0);
      },
    );

  await t.test(
    "two independent service instances never duplicate room, dispatch or quota on same-key retries",
    async () => {
      const f = await fixture();
      const key = randomUUID();
      const results = await Promise.allSettled([
        f.service.start(f.owner, key, randomUUID()),
        f.right.service.start(f.owner, key, randomUUID()),
      ]);
      const success = results.find((result) => result.status === "fulfilled");
      assert.ok(success?.status === "fulfilled");
      const retried = await f.right.service.start(f.owner, key, randomUUID());
      assert.equal(retried.voiceSessionId, success.value.voiceSessionId);
      assert.equal(f.media.creates, 1);
      assert.equal(f.media.dispatches, 1);
      assert.equal(
        (await f.ledger.snapshot(f.owner, "PLUS", f.clock())).reservedSessions,
        1,
      );
      assert.equal(
        (await f.ledger.availability(f.owner, f.clock())).dailyAttempts,
        1,
      );
      await assert.rejects(
        f.service.status(randomUUID(), retried.voiceSessionId),
        rejected("SESSION_NOT_FOUND"),
      );
    },
  );

  await t.test(
    "end remains available after revoked consent, waits for cleanup, and refunds exactly once",
    async () => {
      const f = await fixture();
      const started = await f.start();
      const key = randomUUID();
      f.revoke();
      f.media.failStop = true;
      assert.equal(
        (await f.service.end(f.owner, started.voiceSessionId, key)).state,
        "ENDING",
      );
      assert.equal(
        (await f.ledger.availability(f.owner, f.clock())).globalOccupied,
        1,
      );
      f.media.failStop = false;
      await f.right.service.reconcile();
      assert.equal((await f.current(started.voiceSessionId)).state, "ENDED");
      assert.equal(
        (await f.ledger.snapshot(f.owner, "PLUS", f.clock())).reservedSeconds,
        0,
      );
      const replay = await f.service.end(f.owner, started.voiceSessionId, key);
      assert.equal(replay.state, "ENDED");
      assert.equal(replay.chargedSeconds, 0);
      assert.equal(
        (await f.ledger.availability(f.owner, f.clock())).globalOccupied,
        0,
      );
    },
  );

  await t.test(
    "late room creation after end cannot dispatch or release capacity before confirmed cleanup",
    async () => {
      const f = await fixture();
      const hold = barrier();
      f.media.pauseCreate = hold.wait;
      const start = f.start();
      void start.catch(() => undefined);
      await waitFor(() => f.media.creates > 0);
      const pending = (await f.store.pending(f.clock()))[0];
      assert.ok(pending);
      assert.equal(
        (
          await f.right.service.end(
            f.owner,
            pending.voiceSessionId,
            randomUUID(),
          )
        ).state,
        "ENDING",
      );
      assert.equal(
        (await f.ledger.availability(f.owner, f.clock())).globalOccupied,
        1,
      );
      hold.release();
      await assert.rejects(start, VoiceMediaError);
      await f.service.reconcile();
      assert.equal(f.media.dispatches, 0);
      assert.equal(f.media.rooms.size, 0);
      assert.equal(
        (await f.ledger.availability(f.owner, f.clock())).globalOccupied,
        0,
      );
    },
  );

  await t.test(
    "unknown dispatch result is reconciled by its original identity without another agent",
    async () => {
      const f = await fixture();
      f.media.failDispatch = true;
      await assert.rejects(f.start(), VoiceMediaError);
      const doc = (await f.store.pending(f.clock()))[0];
      assert.ok(doc);
      assert.equal(doc.phase, "DISPATCHING");
      assert.equal(
        (await f.ledger.availability(f.owner, f.clock())).globalOccupied,
        1,
      );
      await f.service.reconcile();
      assert.equal(f.media.dispatches, 1);
      const room = f.media.rooms.get(doc.roomName);
      assert.ok(room);
      room.providerDispatchId = "AD_late_synthetic";
      await f.right.service.reconcile();
      assert.equal((await f.current(doc.voiceSessionId)).state, "FAILED");
      assert.equal(
        (await f.ledger.availability(f.owner, f.clock())).globalOccupied,
        0,
      );
      assert.equal(f.media.dispatches, 1);
    },
  );

  await t.test(
    "worker claim is dispatch-bound, callback replay cannot double bill or replay allow after revocation",
    async () => {
      const f = await fixture();
      const started = await f.start();
      await assert.rejects(
        f.service.claim(
          started.voiceSessionId,
          randomUUID(),
          randomUUID(),
          randomUUID(),
        ),
        rejected("STALE_ATTEMPT"),
      );
      let session = await f.claim(started.voiceSessionId);
      const ready = f.signal(session, 1, "MEDIA_READY");
      await f.service.control(session.voiceSessionId, ready, randomUUID());
      session = await f.current(session.voiceSessionId);
      assert.equal(session.state, "ACTIVE");
      const turnId = randomUUID();
      const replyId = randomUUID();
      const playback = f.signal(session, 2, "PLAYBACK_STARTED", {
        turnId,
        replyId,
      });
      await assert.rejects(
        f.service.control(session.voiceSessionId, playback, randomUUID()),
        rejected("INVALID_SESSION_STATE"),
      );
      assert.equal(
        (await f.current(session.voiceSessionId)).deliveredSession,
        false,
      );
      await f.service.registerReply(
        session.voiceSessionId,
        session.attemptId ?? "",
        turnId,
        replyId,
        randomUUID(),
      );
      const first = await f.service.control(
        session.voiceSessionId,
        playback,
        randomUUID(),
      );
      const same = await f.right.service.control(
        session.voiceSessionId,
        playback,
        randomUUID(),
      );
      assert.deepEqual(same, first);
      assert.equal(
        (await f.ledger.snapshot(f.owner, "PLUS", f.clock())).usedSessions,
        1,
      );
      f.revoke();
      const stopped = await f.service.control(
        session.voiceSessionId,
        playback,
        randomUUID(),
      );
      assert.equal(stopped.action, "STOP");
      assert.equal(stopped.control.payload.reason, "VOICE_CONSENT_REVOKED");
      assert.equal((await f.current(session.voiceSessionId)).state, "FAILED");
    },
  );

  await t.test(
    "a pre-ready heartbeat renews checked authority without activating input or bypassing consent",
    async () => {
      const f = await fixture();
      const started = await f.start();
      let session = await f.claim(started.voiceSessionId);
      assert.equal(session.state, "CONNECTING");
      assert.ok(f.media.ownerConnected && f.media.agentConnected);
      const originalLease = session.leaseExpiresAt?.getTime() ?? 0;
      f.advance(2500);
      const checks = f.checks.length;
      await assert.rejects(
        f.service.control(
          session.voiceSessionId,
          f.signal(session, 1, "HEARTBEAT", { leaseId: randomUUID() }),
          randomUUID(),
        ),
        rejected("STALE_ATTEMPT"),
      );
      assert.equal(f.checks.length, checks);
      const heartbeat = await f.service.control(
        session.voiceSessionId,
        f.signal(session, 1, "HEARTBEAT"),
        randomUUID(),
      );
      assert.equal(heartbeat.action, "CONTINUE");
      assert.equal(f.checks.length, checks + 1);
      session = await f.current(session.voiceSessionId);
      assert.equal(session.state, "CONNECTING");
      assert.ok((session.leaseExpiresAt?.getTime() ?? 0) > originalLease);
      assert.equal(session.activeMilliseconds, 0);
      await f.service.control(
        session.voiceSessionId,
        f.signal(session, 2, "MEDIA_READY"),
        randomUUID(),
      );
      assert.equal((await f.current(session.voiceSessionId)).state, "ACTIVE");
      assert.equal(f.checks.length, checks + 2);

      const revoked = await fixture();
      const pending = await revoked.start();
      const claimed = await revoked.claim(pending.voiceSessionId);
      revoked.revoke();
      const denied = await revoked.service.control(
        claimed.voiceSessionId,
        revoked.signal(claimed, 1, "HEARTBEAT"),
        randomUUID(),
      );
      assert.equal(denied.action, "STOP");
      assert.equal(denied.control.payload.reason, "VOICE_CONSENT_REVOKED");
      assert.equal(
        (await revoked.current(claimed.voiceSessionId)).state,
        "FAILED",
      );
      assert.equal(revoked.media.rooms.size, 0);
    },
  );

  await t.test(
    "reply registration rechecks authority without extending the acknowledged worker lease",
    async () => {
      const f = await fixture();
      const started = await f.start();
      let session = await f.claim(started.voiceSessionId);
      await f.service.control(
        session.voiceSessionId,
        f.signal(session, 1, "MEDIA_READY"),
        randomUUID(),
      );
      f.advance(2000);
      session = await f.current(session.voiceSessionId);
      const heartbeat = await f.service.control(
        session.voiceSessionId,
        f.signal(session, 2, "HEARTBEAT"),
        randomUUID(),
      );
      assert.equal(heartbeat.action, "CONTINUE");
      const deadline = heartbeat.control.expiresAt;
      f.advance(2000);
      const checks = f.checks.length;
      const turnId = randomUUID();
      const replyId = randomUUID();
      await f.service.registerReply(
        session.voiceSessionId,
        session.attemptId ?? "",
        turnId,
        replyId,
        randomUUID(),
      );
      assert.equal(f.checks.length, checks + 1);
      const registered = await f.current(session.voiceSessionId);
      assert.equal(registered.leaseExpiresAt?.toISOString(), deadline);
      assert.equal(registered.authorityExpiresAt.toISOString(), deadline);
      const document = await f.document(session.voiceSessionId);
      assert.equal(document.expectedTurnId, turnId);
      assert.equal(document.expectedReplyId, replyId);
    },
  );

  for (const denial of [
    "AI_CONSENT",
    "VOICE_CONSENT",
    "ENTITLEMENT",
    "EXPIRED",
  ] as const)
    await t.test(
      `reply registration rejects current ${denial} denial`,
      async () => {
        const f = await fixture();
        const started = await f.start();
        let session = await f.claim(started.voiceSessionId);
        await f.service.control(
          session.voiceSessionId,
          f.signal(session, 1, "MEDIA_READY"),
          randomUUID(),
        );
        session = await f.current(session.voiceSessionId);
        const code = {
          AI_CONSENT: "CONSENT_REQUIRED",
          VOICE_CONSENT: "VOICE_CONSENT_REQUIRED",
          ENTITLEMENT: "FREE_LOCKED",
          EXPIRED: "STALE_ATTEMPT",
        }[denial];
        if (denial === "AI_CONSENT") f.revokeAi();
        if (denial === "VOICE_CONSENT") f.revoke();
        if (denial === "ENTITLEMENT") f.downgrade();
        if (denial === "EXPIRED") f.advance(10_000);
        await assert.rejects(
          f.service.registerReply(
            session.voiceSessionId,
            session.attemptId ?? "",
            randomUUID(),
            randomUUID(),
            randomUUID(),
          ),
          (error: unknown) =>
            (error instanceof VoiceAccessError ||
              error instanceof VoiceLedgerError) &&
            error.code === code,
        );
        assert.notEqual(
          (await f.current(session.voiceSessionId)).state,
          "ACTIVE",
        );
        const document = await f.document(session.voiceSessionId);
        assert.equal(document.expectedTurnId, null);
        assert.equal(document.expectedReplyId, null);
      },
    );

  await t.test(
    "pre-claim setup can renew within45s; polling never extends a claimed worker lease",
    async () => {
      const f = await fixture();
      const started = await f.start();
      f.advance(5000);
      await f.service.reconcile();
      assert.ok(
        (await f.current(started.voiceSessionId)).leaseExpiresAt?.getTime() ===
          f.clock().getTime() + 10_000,
      );
      await f.claim(started.voiceSessionId);
      const issued = await f.current(started.voiceSessionId);
      const deadline = issued.leaseExpiresAt?.getTime();
      f.advance(5000);
      await f.service.reconcile();
      assert.equal(
        (await f.current(started.voiceSessionId)).leaseExpiresAt?.getTime(),
        deadline,
      );
      f.advance(5000);
      await f.right.service.reconcile();
      assert.equal((await f.current(started.voiceSessionId)).state, "EXPIRED");
      assert.equal(f.media.rooms.size, 0);
    },
  );

  await t.test(
    "reconnect reuses session and hard deadline; connection keys and late tokens stay bound",
    async () => {
      const f = await fixture();
      const started = await f.start();
      let session = await f.claim(started.voiceSessionId);
      await f.service.control(
        session.voiceSessionId,
        f.signal(session, 1, "MEDIA_READY"),
        randomUUID(),
      );
      f.media.ownerConnected = false;
      session = await f.current(session.voiceSessionId);
      await f.service.control(
        session.voiceSessionId,
        f.signal(session, 2, "HEARTBEAT"),
        randomUUID(),
      );
      assert.equal(
        (await f.current(session.voiceSessionId)).state,
        "RECONNECTING",
      );
      const key = randomUUID();
      const token = await f.service.connection(
        f.owner,
        session.voiceSessionId,
        key,
        randomUUID(),
      );
      assert.equal(token.hardStopAt, started.hardStopAt);
      f.media.ownerConnected = true;
      session = await f.current(session.voiceSessionId);
      await f.service.control(
        session.voiceSessionId,
        f.signal(session, 3, "HEARTBEAT"),
        randomUUID(),
      );
      assert.equal((await f.current(session.voiceSessionId)).state, "ACTIVE");
      const hold = barrier();
      f.media.pauseConnection = hold.wait;
      const issuing = f.service.connection(
        f.owner,
        session.voiceSessionId,
        randomUUID(),
        randomUUID(),
      );
      await waitFor(() => f.media.tokens >= 2);
      await f.right.service.end(f.owner, session.voiceSessionId, randomUUID());
      hold.release();
      await assert.rejects(issuing, rejected("SESSION_EXPIRED"));
      const next = await f.start();
      await assert.rejects(
        f.service.connection(f.owner, next.voiceSessionId, key, randomUUID()),
        rejected("IDEMPOTENCY_CONFLICT"),
      );
    },
  );

  await t.test(
    "missing initialization is a fenced no-effect proof; initialized media cannot use that release",
    async () => {
      const f = await fixture();
      const key = randomUUID();
      let bare = await f.access.reserve(f.owner, key, randomUUID());
      bare = await f.ledger.claimDispatch(bare.voiceSessionId, f.clock());
      await f.ledger.requestEnd(
        f.owner,
        bare.voiceSessionId,
        "PROVIDER_FAILURE",
        f.clock(),
      );
      await f.service.reconcile();
      assert.equal((await f.current(bare.voiceSessionId)).state, "FAILED");
      assert.equal(f.media.creates, 0);
      const initialized = await f.start();
      await f.ledger.requestEnd(
        f.owner,
        initialized.voiceSessionId,
        "USER_ENDED",
        f.clock(),
      );
      await assert.rejects(
        f.ledger.settle(
          initialized.voiceSessionId,
          null,
          "NO_MEDIA_INITIALIZATION",
          f.clock(),
        ),
        rejected("INVALID_SESSION_STATE"),
      );
      await f.service.reconcile();
    },
  );

  await t.test(
    "closed metadata validation, creation-bound retention and rollback never discard live mapping",
    async () => {
      const f = await fixture();
      const started = await f.start();
      const doc = await f.document(started.voiceSessionId);
      await assert.rejects(
        f.db
          .collection<VoiceMediaDocument>("ai_voice_media")
          .updateOne(
            { _id: doc._id },
            { $set: { transcript: "synthetic-forbidden-text" } },
          ),
        (error: unknown) =>
          error instanceof MongoServerError && error.code === 121,
      );
      await assert.rejects(
        migrations[1]?.down(f.db) ??
          Promise.reject(new Error("missing migration")),
      );
      f.media.failStop = true;
      f.advance(30 * 86_400_000);
      await f.service.reconcile();
      assert.equal(await f.db.collection("ai_voice_media").countDocuments(), 0);
      const capacity = await f.db
        .collection<{ _id: string; slots: unknown[] }>("ai_voice_capacity")
        .findOne({ _id: "voice-global-v1" });
      assert.ok(capacity);
      const json = JSON.stringify(capacity);
      assert.equal(json.includes(f.owner), false);
      assert.equal(json.includes(started.voiceSessionId), false);
      assert.ok(json.includes("QUARANTINED"));
      assert.equal(
        json.includes("synthetic-memory-only-participant-token"),
        false,
      );
    },
  );

  await t.test(
    "heartbeats preserve the60-second idle cutoff and charge only confirmed ACTIVE time",
    async () => {
      const f = await fixture();
      const started = await f.start();
      let session = await f.claim(started.voiceSessionId);
      await f.service.control(
        session.voiceSessionId,
        f.signal(session, 1, "MEDIA_READY"),
        randomUUID(),
      );
      session = await f.current(session.voiceSessionId);
      const turnId = randomUUID();
      const replyId = randomUUID();
      await f.service.registerReply(
        session.voiceSessionId,
        session.attemptId ?? "",
        turnId,
        replyId,
        randomUUID(),
      );
      await f.service.control(
        session.voiceSessionId,
        f.signal(session, 2, "PLAYBACK_STARTED", { turnId, replyId }),
        randomUUID(),
      );
      for (let sequence = 3; sequence <= 13; sequence += 1) {
        f.advance(5000);
        session = await f.current(session.voiceSessionId);
        await f.service.control(
          session.voiceSessionId,
          f.signal(session, sequence, "HEARTBEAT"),
          randomUUID(),
        );
      }
      f.advance(5000);
      await f.service.reconcile();
      const ended = await f.current(session.voiceSessionId);
      assert.equal(ended.state, "EXPIRED");
      assert.equal(ended.terminalReason, "IDLE_TIMEOUT");
      assert.equal(ended.chargedSeconds, 60);
      assert.equal(
        (await f.ledger.snapshot(f.owner, "PLUS", f.clock())).usedSeconds,
        60,
      );
      assert.equal(f.media.rooms.size, 0);
    },
  );

  await t.test(
    "pre-claim setup and reconnect grace stop at45s and30s despite fresh owner checks",
    async () => {
      const setup = await fixture();
      const started = await setup.start();
      for (let step = 0; step < 9; step += 1) {
        setup.advance(5000);
        await setup.service.reconcile();
      }
      assert.equal(
        (await setup.current(started.voiceSessionId)).state,
        "FAILED",
      );
      assert.equal(setup.media.rooms.size, 0);
      const f = await fixture();
      const connected = await f.start();
      let session = await f.claim(connected.voiceSessionId);
      await f.service.control(
        session.voiceSessionId,
        f.signal(session, 1, "MEDIA_READY"),
        randomUUID(),
      );
      f.media.ownerConnected = false;
      session = await f.current(session.voiceSessionId);
      await f.service.control(
        session.voiceSessionId,
        f.signal(session, 2, "HEARTBEAT"),
        randomUUID(),
      );
      for (let sequence = 3; sequence <= 7; sequence += 1) {
        f.advance(5000);
        session = await f.current(session.voiceSessionId);
        await f.service.control(
          session.voiceSessionId,
          f.signal(session, sequence, "HEARTBEAT"),
          randomUUID(),
        );
      }
      f.advance(5000);
      await f.service.reconcile();
      const ended = await f.current(session.voiceSessionId);
      assert.equal(ended.state, "EXPIRED");
      assert.equal(ended.terminalReason, "RECONNECT_TIMEOUT");
      assert.equal(ended.hardStopAt.toISOString(), connected.hardStopAt);
    },
  );

  for (const change of ["ai-consent", "entitlement"] as const) {
    await t.test(
      `worker renewal observes current ${change} and cuts media`,
      async () => {
        const f = await fixture();
        const started = await f.start();
        const session = await f.claim(started.voiceSessionId);
        if (change === "ai-consent") f.revokeAi();
        else f.downgrade();
        const response = await f.service.control(
          session.voiceSessionId,
          f.signal(session, 1, "MEDIA_READY"),
          randomUUID(),
        );
        assert.equal(response.action, "STOP");
        assert.equal(
          response.control.payload.reason,
          change === "ai-consent" ? "CONSENT_REVOKED" : "ENTITLEMENT_LOST",
        );
        assert.equal(f.media.rooms.size, 0);
        assert.equal(
          (await f.ledger.snapshot(f.owner, "PLUS", f.clock()))
            .remainingSessions,
          2,
        );
      },
    );
  }

  await t.test(
    "a crash after progress commit cannot renew authority by replaying an uncached old signal",
    async () => {
      const f = await fixture();
      const started = await f.start();
      let session = await f.claim(started.voiceSessionId);
      const signal = f.signal(session, 1, "MEDIA_READY");
      await f.service.control(session.voiceSessionId, signal, randomUUID());
      await f.db.collection<VoiceMediaDocument>("ai_voice_media").updateOne(
        { _id: session.voiceSessionId },
        {
          $set: {
            workerSequence: 0,
            workerFingerprint: null,
            lastControl: null,
          },
        },
      );
      session = await f.current(session.voiceSessionId);
      const expiry = session.leaseExpiresAt?.getTime();
      f.advance(1000);
      await assert.rejects(
        f.service.control(session.voiceSessionId, signal, randomUUID()),
        rejected("STALE_ATTEMPT"),
      );
      assert.equal(
        (await f.current(session.voiceSessionId)).leaseExpiresAt?.getTime(),
        expiry,
      );
    },
  );
});
