import assert from "node:assert/strict";
import test from "node:test";

import {
  VoiceAccessError,
  VoiceAccessService,
  type VoiceAccessLedger,
} from "./voice-access.js";
import {
  VoiceAuthorizationError,
  type VoiceOwnerDecision,
} from "./voice-authorization.js";
import {
  VoiceLedgerError,
  type VoiceSession,
  type VoiceQuotaSnapshot,
} from "./voice-ledger.js";

const ownerId = "11111111-1111-4111-8111-111111111111";
const sessionId = "22222222-2222-4222-8222-222222222222";
const key = "33333333-3333-4333-8333-333333333333";
const now = new Date("2026-10-23T16:00:00Z");
const secret = Buffer.alloc(32, 18);
const decision = (): VoiceOwnerDecision => ({
  consent: {
    ownerId,
    voiceSessionId: sessionId,
    purpose: "AI_COMPANION_VOICE",
    aiProcessingGranted: true,
    voiceProcessingGranted: true,
    voiceConsentVersion: "ai-voice-processing-capstone-v1",
    authorized: true,
    checkedAt: now.toISOString(),
  },
  entitlement: {
    ownerId,
    voiceSessionId: sessionId,
    purpose: "AI_COMPANION_VOICE",
    packageCode: "PLUS",
    source: "PAID",
    entitlementVersion: "paid:4",
    validUntil: "2026-11-01T00:00:00Z",
    checkedAt: now.toISOString(),
  },
});

const quota = (): VoiceQuotaSnapshot => ({
  policyVersion: "companion-voice-policy-v1",
  timeZone: "Asia/Ho_Chi_Minh",
  resetAt: new Date("2026-10-23T17:00:00Z"),
  quotaDay: "2026-10-23",
  plan: "PLUS",
  usedSessions: 0,
  reservedSessions: 0,
  usedSeconds: 0,
  reservedSeconds: 0,
  remainingSessions: 2,
  remainingSeconds: 600,
  startAttempts: 0,
  ownerActive: false,
  globalAvailable: 2,
  rateLimited: false,
});

const session = (): VoiceSession => ({
  voiceSessionId: sessionId,
  ownerId,
  keyHash: "opaque",
  fingerprint: "opaque",
  state: "AUTHORIZED",
  policyVersion: "companion-voice-policy-v1",
  entitlement: {
    packageCode: "PLUS",
    source: "PAID",
    entitlementVersion: "paid:4",
    checkedAt: now,
    validUntil: new Date("2026-11-01T00:00:00Z"),
  },
  quotaDay: "2026-10-23",
  admittedSeconds: 300,
  authorizedAt: now,
  authorityExpiresAt: new Date(now.getTime() + 10_000),
  hardStopAt: new Date(now.getTime() + 300_000),
  createdAt: now,
  updatedAt: now,
  expiresAt: new Date(now.getTime() + 30 * 86400_000),
  deliveredSession: false,
  chargedSeconds: 0,
  dispatchState: "UNISSUED",
  capacityGeneration: key,
  attemptId: null,
  leaseId: null,
  leaseExpiresAt: null,
  lastSequence: 0,
  activeMilliseconds: 0,
  openActiveSince: null,
  lastActivityAt: now,
  reconnectDeadline: null,
  connectDeadline: null,
  terminalReason: null,
  settledAt: null,
});

const ledger = (
  overrides: Partial<VoiceAccessLedger> = {},
): VoiceAccessLedger => ({
  snapshot: () => Promise.resolve(quota()),
  replay: () => Promise.resolve(null),
  recordStartAttempt: () =>
    Promise.resolve({
      status: "CLAIMED",
      expiresAt: new Date(now.getTime() + 10_000),
    }),
  releaseStartAttempt: () => Promise.resolve(),
  reserve: () => Promise.resolve(session()),
  findOwned: () => Promise.resolve(session()),
  requestEnd: () => Promise.resolve({ ...session(), state: "ENDING" }),
  renewAuthority: () => Promise.resolve(session()),
  reauthorizationCandidates: () => Promise.resolve([]),
  reconcile: () => Promise.resolve({ ended: 0, purged: 0, quarantined: 0 }),
  ...overrides,
});

for (const [label, change, state] of [
  [
    "FREE",
    (d: VoiceOwnerDecision) => {
      d.entitlement.packageCode = "FREE";
      d.entitlement.source = "DEFAULT_FREE";
    },
    "FREE_LOCKED",
  ],
  [
    "withdrawn AI processing",
    (d: VoiceOwnerDecision) => {
      d.consent.aiProcessingGranted = false;
      d.consent.authorized = false;
    },
    "CONSENT_REQUIRED",
  ],
  [
    "withdrawn Voice processing",
    (d: VoiceOwnerDecision) => {
      d.consent.voiceProcessingGranted = false;
      d.consent.authorized = false;
    },
    "VOICE_CONSENT_REQUIRED",
  ],
  [
    "expired paid entitlement",
    (d: VoiceOwnerDecision) => {
      d.entitlement.validUntil = now.toISOString();
    },
    "FREE_LOCKED",
  ],
] as const) {
  void test(`Voice eligibility denies ${label} without reserving quota or invoking a provider`, async () => {
    const d = decision();
    change(d);
    let mutations = 0;
    const service = new VoiceAccessService(
      ledger({
        reserve: () => {
          mutations += 1;
          throw new Error();
        },
      }),
      { check: () => Promise.resolve(d) },
      secret,
      () => true,
      () => now,
    );
    const result = await service.eligibility(ownerId, key);
    assert.equal(result.state, state);
    assert.equal(result.eligible, false);
    assert.equal(mutations, 0);
    await assert.rejects(
      service.reserve(ownerId, key, key),
      (error: unknown) =>
        error instanceof VoiceAccessError && error.code === state,
    );
    assert.equal(mutations, 0);
  });
}

void test("paid and DEMO PLUS/PREMIUM use current owner package and fresh check-only IDs", async () => {
  for (const packageCode of ["PLUS", "PREMIUM"] as const)
    for (const source of ["DEMO", "PAID"] as const) {
      const d = decision();
      d.entitlement.packageCode = packageCode;
      d.entitlement.source = source;
      const checks: string[] = [];
      const service = new VoiceAccessService(
        ledger(),
        {
          check: (owner, id) => {
            assert.equal(owner, ownerId);
            checks.push(id);
            return Promise.resolve(d);
          },
        },
        secret,
        () => true,
        () => now,
      );
      const result = await service.eligibility(ownerId, key);
      await service.eligibility(ownerId, key);
      assert.equal(result.state, "ELIGIBLE");
      assert.equal(result.packageCode, packageCode);
      assert.notEqual(checks[0], checks[1]);
      assert.equal(JSON.stringify(result).includes("sourceReference"), false);
    }
});

for (const [label, patch, state] of [
  ["same owner on another device", { ownerActive: true }, "SESSION_LIMIT"],
  ["global demo slots", { globalAvailable: 0 }, "GLOBAL_CAPACITY_FULL"],
  ["daily sessions", { remainingSessions: 0 }, "QUOTA_EXHAUSTED"],
  ["daily seconds", { remainingSeconds: 0 }, "QUOTA_EXHAUSTED"],
  ["minute attempts", { rateLimited: true }, "RATE_LIMITED"],
] as const) {
  void test(`eligibility reflects ${label} without changing ledger`, async () => {
    const service = new VoiceAccessService(
      ledger({ snapshot: () => Promise.resolve({ ...quota(), ...patch }) }),
      { check: () => Promise.resolve(decision()) },
      secret,
      () => true,
      () => now,
    );
    assert.equal((await service.eligibility(ownerId, key)).state, state);
  });
}

void test("missing prerequisite or provider readiness fails closed", async () => {
  const service = new VoiceAccessService(
    ledger(),
    { check: () => Promise.resolve(decision()) },
    secret,
    undefined,
    () => now,
  );
  assert.equal(
    (await service.eligibility(ownerId, key)).state,
    "PROVIDER_UNAVAILABLE",
  );
  for (const code of [
    "CONSENT_UNAVAILABLE",
    "ENTITLEMENT_UNAVAILABLE",
  ] as const) {
    const missing = new VoiceAccessService(
      ledger(),
      {
        check: () => {
          throw new VoiceAuthorizationError(code);
        },
      },
      secret,
      () => true,
      () => now,
    );
    await assert.rejects(
      missing.eligibility(ownerId, key),
      (error: unknown) =>
        error instanceof VoiceAccessError && error.code === code,
    );
  }
});

void test("admission binds the checked session, protects command keys and freezes authority lifetime", async () => {
  let checkedSession = "";
  let attempts = 0;
  let captured: Parameters<VoiceAccessLedger["reserve"]>[0] | undefined;
  const service = new VoiceAccessService(
    ledger({
      recordStartAttempt: (owner, date, keyHash) => {
        assert.equal(owner, ownerId);
        assert.equal(date, now);
        assert.match(keyHash, /^[A-Za-z0-9_-]{43}$/);
        attempts += 1;
        return Promise.resolve({
          status: "CLAIMED",
          expiresAt: new Date(now.getTime() + 10_000),
        });
      },
      reserve: (input) => {
        captured = input;
        return Promise.resolve({
          ...session(),
          voiceSessionId: input.voiceSessionId,
        });
      },
    }),
    {
      check: (owner, id) => {
        assert.equal(owner, ownerId);
        checkedSession = id;
        return Promise.resolve(decision());
      },
    },
    secret,
    () => true,
    () => now,
  );
  await service.reserve(ownerId, key, key);
  assert.equal(attempts, 1);
  assert.ok(captured);
  assert.equal(captured.voiceSessionId, checkedSession);
  assert.notEqual(captured.keyHash, key);
  assert.equal(captured.authorityExpiresAt?.getTime(), now.getTime() + 10_000);
  assert.equal(captured.claimExpiresAt?.getTime(), now.getTime() + 10_000);
  assert.equal(JSON.stringify(captured).includes(key), false);
});

void test("accepted replay reauthorizes without spending another start attempt", async () => {
  let checks = 0;
  const service = new VoiceAccessService(
    ledger({
      replay: () => Promise.resolve(session()),
      recordStartAttempt: () => {
        throw new Error("must not increment");
      },
    }),
    {
      check: () => {
        checks += 1;
        return Promise.resolve(decision());
      },
    },
    secret,
    () => true,
    () => now,
  );
  assert.equal(
    (await service.reserve(ownerId, key, key)).voiceSessionId,
    sessionId,
  );
  assert.equal(checks, 1);
});

void test("concurrent pending retries never fan out owner reads or release another claim", async () => {
  let claimed = false;
  let checks = 0;
  let releases = 0;
  let complete: ((value: VoiceOwnerDecision) => void) | undefined;
  const waiting = new Promise<VoiceOwnerDecision>((resolve) => {
    complete = resolve;
  });
  const expiry = new Date(now.getTime() + 10_000);
  const service = new VoiceAccessService(
    ledger({
      recordStartAttempt: () => {
        if (claimed) return Promise.resolve({ status: "PENDING" });
        claimed = true;
        return Promise.resolve({ status: "CLAIMED", expiresAt: expiry });
      },
      releaseStartAttempt: (_owner, _key, _now, claimedExpiry) => {
        assert.equal(claimedExpiry, expiry);
        releases += 1;
        return Promise.resolve();
      },
    }),
    {
      check: () => {
        checks += 1;
        return waiting;
      },
    },
    secret,
    () => true,
    () => now,
  );
  const first = service.reserve(ownerId, key, key);
  await Promise.all(
    Array.from({ length: 20 }, () =>
      assert.rejects(
        service.reserve(ownerId, key, key),
        (error: unknown) =>
          error instanceof VoiceAccessError &&
          error.code === "INVALID_SESSION_STATE",
      ),
    ),
  );
  assert.equal(checks, 1);
  assert.equal(releases, 0);
  assert.ok(complete);
  complete(decision());
  await first;
  assert.equal(releases, 1);
});

void test("a denied start releases only its fenced pending claim", async () => {
  let releases = 0;
  const expiry = new Date(now.getTime() + 10_000);
  const service = new VoiceAccessService(
    ledger({
      releaseStartAttempt: (_owner, _key, _now, claimedExpiry) => {
        assert.equal(claimedExpiry.getTime(), expiry.getTime());
        releases += 1;
        return Promise.resolve();
      },
    }),
    {
      check: () => {
        throw new VoiceAuthorizationError("CONSENT_UNAVAILABLE");
      },
    },
    secret,
    () => true,
    () => now,
  );
  await assert.rejects(service.reserve(ownerId, key, key), VoiceAccessError);
  assert.equal(releases, 1);
});

void test("every renewal/turn/reconnect authority request observes both consents and ends on revocation", async () => {
  let checks = 0;
  const reasons: string[] = [];
  const d = decision();
  const service = new VoiceAccessService(
    ledger({
      requestEnd: (_owner, _id, reason) => {
        reasons.push(reason);
        return Promise.resolve({ ...session(), state: "ENDING" });
      },
    }),
    {
      check: () => {
        checks += 1;
        return Promise.resolve(d);
      },
    },
    secret,
    () => true,
    () => now,
  );
  await service.authorizeSession(ownerId, sessionId, key);
  d.consent.voiceProcessingGranted = false;
  d.consent.authorized = false;
  await assert.rejects(
    service.authorizeSession(ownerId, sessionId, key),
    VoiceAccessError,
  );
  assert.equal(checks, 2);
  assert.deepEqual(reasons, ["VOICE_CONSENT_REVOKED"]);
});

void test("owner uncertainty terminates authority; a wrong owner and expired session never trigger new reads", async () => {
  let reason = "";
  const service = new VoiceAccessService(
    ledger({
      requestEnd: (_owner, _id, value) => {
        reason = value;
        return Promise.resolve(session());
      },
    }),
    {
      check: () => {
        throw new VoiceAuthorizationError("ENTITLEMENT_UNAVAILABLE");
      },
    },
    secret,
    () => true,
    () => now,
  );
  await assert.rejects(
    service.authorizeSession(ownerId, sessionId, key),
    VoiceAccessError,
  );
  assert.equal(reason, "OWNER_UNAVAILABLE");
  for (const found of [null, { ...session(), state: "ENDED" as const }]) {
    const missing = new VoiceAccessService(
      ledger({ findOwned: () => Promise.resolve(found) }),
      {
        check: () => {
          throw new Error("must not read");
        },
      },
      secret,
    );
    await assert.rejects(
      missing.authorizeSession(ownerId, sessionId, key),
      VoiceAccessError,
    );
  }
});

void test("malformed start and storage errors produce bounded sanitized outcomes", async () => {
  const service = new VoiceAccessService(
    ledger({
      snapshot: () => {
        throw new Error("private database URI");
      },
    }),
    { check: () => Promise.resolve(decision()) },
    secret,
    () => true,
    () => now,
  );
  await assert.rejects(
    service.reserve(ownerId, "bad-key", key),
    (error: unknown) =>
      error instanceof VoiceAccessError && error.code === "INVALID_REQUEST",
  );
  await assert.rejects(
    service.eligibility(ownerId, key),
    (error: unknown) =>
      error instanceof VoiceAccessError &&
      error.code === "PROVIDER_UNAVAILABLE" &&
      error.cause === undefined,
  );
  const full = new VoiceAccessService(
    ledger({
      reserve: () => {
        throw new VoiceLedgerError("GLOBAL_CAPACITY_FULL");
      },
    }),
    { check: () => Promise.resolve(decision()) },
    secret,
    () => true,
    () => now,
  );
  await assert.rejects(
    full.reserve(ownerId, key, key),
    (error: unknown) =>
      error instanceof VoiceAccessError &&
      error.code === "GLOBAL_CAPACITY_FULL",
  );
});
