import { randomUUID } from "node:crypto";
import {
  MongoClient,
  MongoServerError,
  type ClientSession,
  type Collection,
} from "mongodb";

import {
  VOICE_LEASE_MS,
  VOICE_LEASE_RENEWAL_MS,
  VOICE_POLICY_VERSION,
  VOICE_RETENTION_MS,
  VOICE_TIME_ZONE,
  voiceChargedSeconds,
  voiceLimits,
  voiceQuotaDay,
  voiceResetAt,
  type VoicePlan,
} from "./voice-policy.js";

export type { VoicePlan } from "./voice-policy.js";

export type VoiceState =
  | "REQUESTED"
  | "AUTHORIZED"
  | "CONNECTING"
  | "ACTIVE"
  | "RECONNECTING"
  | "ENDING"
  | "ENDED"
  | "FAILED"
  | "EXPIRED";

export type VoiceTerminalReason =
  | "USER_ENDED"
  | "CONSENT_REVOKED"
  | "VOICE_CONSENT_REVOKED"
  | "ENTITLEMENT_LOST"
  | "OWNER_UNAVAILABLE"
  | "IDLE_TIMEOUT"
  | "HARD_DEADLINE"
  | "QUOTA_EXHAUSTED"
  | "RECONNECT_TIMEOUT"
  | "PROVIDER_FAILURE"
  | "AGENT_FAILURE"
  | "AUTHORIZATION_EXPIRED"
  | "ACCOUNT_DELETED";

export type VoiceLedgerErrorCode =
  | "FREE_LOCKED"
  | "SESSION_NOT_FOUND"
  | "SESSION_LIMIT"
  | "IDEMPOTENCY_CONFLICT"
  | "STALE_ATTEMPT"
  | "INVALID_SESSION_STATE"
  | "SESSION_EXPIRED"
  | "INVALID_REQUEST"
  | "GLOBAL_CAPACITY_FULL"
  | "QUOTA_EXHAUSTED"
  | "RATE_LIMITED";

export class VoiceLedgerError extends Error {
  constructor(readonly code: VoiceLedgerErrorCode) {
    super(code);
    this.name = "VoiceLedgerError";
  }
}

export interface VoiceEntitlementSnapshot {
  packageCode: VoicePlan;
  source: "DEFAULT_FREE" | "PAID" | "DEMO";
  entitlementVersion: string;
  checkedAt: Date;
  validUntil?: Date;
}

export interface VoiceSession {
  voiceSessionId: string;
  ownerId: string;
  keyHash: string;
  fingerprint: string;
  state: VoiceState;
  policyVersion: string;
  entitlement: VoiceEntitlementSnapshot;
  quotaDay: string;
  admittedSeconds: number;
  authorizedAt: Date;
  authorityExpiresAt: Date;
  hardStopAt: Date;
  createdAt: Date;
  updatedAt: Date;
  expiresAt: Date;
  deliveredSession: boolean;
  chargedSeconds: number;
  dispatchState: "UNISSUED" | "ISSUED" | "STOPPED";
  capacityGeneration: string;
  attemptId: string | null;
  leaseId: string | null;
  leaseExpiresAt: Date | null;
  lastSequence: number;
  activeMilliseconds: number;
  openActiveSince: Date | null;
  lastActivityAt: Date;
  reconnectDeadline: Date | null;
  connectDeadline: Date | null;
  terminalReason: VoiceTerminalReason | null;
  settledAt: Date | null;
}

export interface ReserveVoiceInput {
  voiceSessionId: string;
  ownerId: string;
  keyHash: string;
  fingerprint: string;
  now: Date;
  entitlement: VoiceEntitlementSnapshot;
  authorityExpiresAt?: Date;
  claimExpiresAt?: Date;
}

export interface VoiceGuard {
  attemptId: string;
  leaseId: string;
  generation: string;
  sequence: number;
}

export interface VoiceQuotaSnapshot {
  policyVersion: string;
  timeZone: string;
  resetAt: Date;
  quotaDay: string;
  plan: VoicePlan;
  usedSessions: number;
  reservedSessions: number;
  usedSeconds: number;
  reservedSeconds: number;
  remainingSessions: number;
  remainingSeconds: number;
  startAttempts: number;
  ownerActive: boolean;
  globalAvailable: number;
  rateLimited: boolean;
}

export interface VoiceAvailability {
  ownerOccupied: boolean;
  globalOccupied: number;
  minuteAttempts: number;
  dailyAttempts: number;
  rateLimited: boolean;
}

export type VoiceStartAttemptClaim =
  | { status: "CLAIMED"; expiresAt: Date }
  | { status: "PENDING" }
  | { status: "ACCEPTED" };

export type VoiceTeardownProof =
  "NEVER_DISPATCHED" | "PROVIDER_CONFIRMED_STOPPED";

interface StoredSession extends VoiceSession {
  _id: string;
}

interface DailyUsage {
  _id: string;
  ownerId: string;
  quotaDay: string;
  usedSessions: number;
  reservedSessions: number;
  usedSeconds: number;
  reservedSeconds: number;
  startAttempts: number;
  pendingAttempts: { keyHash: string; expiresAt: Date }[];
  minuteStartedAt: Date;
  minuteAttempts: number;
  expiresAt: Date;
}

interface CapacitySlot {
  status: "FREE" | "HELD" | "QUARANTINED";
  generation: string;
  ownerId?: string;
  voiceSessionId?: string;
}

interface Capacity {
  _id: "voice-global-v1";
  slots: CapacitySlot[];
}

const uuid =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const hash = /^[A-Za-z0-9_-]{43}$/;
const terminal = (state: VoiceState) =>
  state === "ENDED" || state === "FAILED" || state === "EXPIRED";
const transitions: Record<VoiceState, readonly VoiceState[]> = {
  REQUESTED: ["AUTHORIZED", "FAILED", "EXPIRED"],
  AUTHORIZED: ["CONNECTING", "ENDING"],
  CONNECTING: ["ACTIVE", "ENDING"],
  ACTIVE: ["RECONNECTING", "ENDING"],
  RECONNECTING: ["ACTIVE", "ENDING"],
  ENDING: ["ENDED", "FAILED", "EXPIRED"],
  ENDED: [],
  FAILED: [],
  EXPIRED: [],
};

const publicSession = ({
  _id: omitted,
  ...session
}: StoredSession): VoiceSession => {
  void omitted;
  return session;
};

const usageId = (ownerId: string, day: string) => `${ownerId}:${day}`;
const freshUsage = (ownerId: string, now: Date): DailyUsage => ({
  _id: usageId(ownerId, voiceQuotaDay(now)),
  ownerId,
  quotaDay: voiceQuotaDay(now),
  usedSessions: 0,
  reservedSessions: 0,
  usedSeconds: 0,
  reservedSeconds: 0,
  startAttempts: 0,
  pendingAttempts: [],
  minuteStartedAt: new Date(Math.floor(now.getTime() / 60_000) * 60_000),
  minuteAttempts: 0,
  expiresAt: new Date(now.getTime() + VOICE_RETENTION_MS),
});

const assertDate = (value: Date) => {
  if (!Number.isFinite(value.getTime()))
    throw new VoiceLedgerError("INVALID_REQUEST");
};

const assertOwner = (ownerId: string) => {
  if (!uuid.test(ownerId)) throw new VoiceLedgerError("INVALID_REQUEST");
};

const assertEntitlement = (value: VoiceEntitlementSnapshot, now: Date) => {
  assertDate(value.checkedAt);
  if (
    !["FREE", "PLUS", "PREMIUM"].includes(value.packageCode) ||
    !value.entitlementVersion.trim() ||
    value.entitlementVersion.length > 128 ||
    value.checkedAt > now ||
    now.getTime() - value.checkedAt.getTime() > 2_000
  )
    throw new VoiceLedgerError("INVALID_REQUEST");
  if (value.packageCode === "FREE") throw new VoiceLedgerError("FREE_LOCKED");
  if (
    !["PAID", "DEMO"].includes(value.source) ||
    !value.validUntil ||
    !Number.isFinite(value.validUntil.getTime()) ||
    value.validUntil <= now
  )
    throw new VoiceLedgerError("INVALID_REQUEST");
};

const closingCut = (session: VoiceSession, now: Date): Date => {
  const cuts = [now.getTime(), session.hardStopAt.getTime()];
  if (session.leaseExpiresAt) cuts.push(session.leaseExpiresAt.getTime());
  if (session.state === "ACTIVE")
    cuts.push(session.lastActivityAt.getTime() + 60_000);
  return new Date(Math.min(...cuts));
};

const closeActive = (session: VoiceSession, at: Date) => {
  if (session.openActiveSince) {
    session.activeMilliseconds += Math.max(
      0,
      at.getTime() - session.openActiveSince.getTime(),
    );
    session.openActiveSince = null;
  }
  session.chargedSeconds = voiceChargedSeconds(
    session.activeMilliseconds,
    session.admittedSeconds,
  );
};

const dueReason = (
  session: VoiceSession,
  now: Date,
): VoiceTerminalReason | null => {
  if (now >= session.hardStopAt) return "HARD_DEADLINE";
  if (
    session.dispatchState === "UNISSUED" &&
    (now >= session.authorityExpiresAt ||
      now.getTime() >= session.authorizedAt.getTime() + 10_000)
  )
    return "AUTHORIZATION_EXPIRED";
  if (session.leaseExpiresAt && now >= session.leaseExpiresAt)
    return "AUTHORIZATION_EXPIRED";
  if (
    session.connectDeadline &&
    session.state === "CONNECTING" &&
    now >= session.connectDeadline
  )
    return "PROVIDER_FAILURE";
  if (
    session.reconnectDeadline &&
    session.state === "RECONNECTING" &&
    now >= session.reconnectDeadline
  )
    return "RECONNECT_TIMEOUT";
  if (
    session.state === "ACTIVE" &&
    now.getTime() >= session.lastActivityAt.getTime() + 60_000
  )
    return "IDLE_TIMEOUT";
  return null;
};

export class MongoVoiceLedger {
  private readonly sessions: Collection<StoredSession>;
  private readonly usage: Collection<DailyUsage>;
  private readonly capacity: Collection<Capacity>;

  constructor(
    private readonly client: MongoClient,
    databaseName: string,
  ) {
    const database = client.db(databaseName);
    this.sessions = database.collection("ai_voice_sessions");
    this.usage = database.collection("ai_voice_daily_usage");
    this.capacity = database.collection("ai_voice_capacity");
  }

  private async atomic<T>(
    action: (transaction: ClientSession) => Promise<T>,
  ): Promise<T> {
    for (let attempt = 0; ; attempt += 1) {
      const transaction = this.client.startSession();
      try {
        return await transaction.withTransaction(() => action(transaction), {
          readConcern: { level: "snapshot" },
          writeConcern: { w: "majority" },
        });
      } catch (error) {
        if (!(
          error instanceof MongoServerError &&
          error.code === 11000 &&
          attempt < 2
        ))
          throw error;
      } finally {
        await transaction.endSession();
      }
    }
  }

  async replay(
    ownerId: string,
    keyHash: string,
    now = new Date(),
  ): Promise<VoiceSession | null> {
    const found = await this.sessions.findOne({
      ownerId,
      keyHash,
      expiresAt: { $gt: now },
    });
    return found ? publicSession(found) : null;
  }

  async recordStartAttempt(
    ownerId: string,
    now: Date,
    keyHash: string,
  ): Promise<VoiceStartAttemptClaim> {
    assertOwner(ownerId);
    assertDate(now);
    if (!hash.test(keyHash)) throw new VoiceLedgerError("INVALID_REQUEST");
    return this.atomic<VoiceStartAttemptClaim>(async (transaction) => {
      const accepted = await this.sessions.findOne(
        { ownerId, keyHash, expiresAt: { $gt: now } },
        { session: transaction },
      );
      if (accepted) return { status: "ACCEPTED" };
      await this.usage.updateMany(
        { ownerId, "pendingAttempts.expiresAt": { $lte: now } },
        { $pull: { pendingAttempts: { expiresAt: { $lte: now } } } },
        { session: transaction },
      );
      const pending = await this.usage.findOne(
        { ownerId, "pendingAttempts.keyHash": keyHash },
        { session: transaction },
      );
      if (pending) return { status: "PENDING" };
      const usage =
        (await this.usage.findOne(
          { _id: usageId(ownerId, voiceQuotaDay(now)) },
          { session: transaction },
        )) ?? freshUsage(ownerId, now);
      const minute = Math.floor(now.getTime() / 60_000) * 60_000;
      if (usage.minuteStartedAt.getTime() !== minute) {
        usage.minuteStartedAt = new Date(minute);
        usage.minuteAttempts = 0;
      }
      if (usage.startAttempts >= 10 || usage.minuteAttempts >= 3)
        throw new VoiceLedgerError("RATE_LIMITED");
      usage.startAttempts += 1;
      const expiresAt = new Date(now.getTime() + 10_000);
      usage.pendingAttempts.push({ keyHash, expiresAt });
      usage.minuteAttempts += 1;
      await this.usage.replaceOne({ _id: usage._id }, usage, {
        upsert: true,
        session: transaction,
      });
      return { status: "CLAIMED", expiresAt };
    });
  }

  async releaseStartAttempt(
    ownerId: string,
    keyHash: string,
    now: Date,
    claimedExpiresAt: Date,
  ): Promise<void> {
    assertOwner(ownerId);
    assertDate(now);
    assertDate(claimedExpiresAt);
    if (!hash.test(keyHash)) throw new VoiceLedgerError("INVALID_REQUEST");
    await this.usage.updateMany(
      {
        ownerId,
        expiresAt: { $gt: now },
        pendingAttempts: {
          $elemMatch: { keyHash, expiresAt: claimedExpiresAt },
        },
      },
      { $pull: { pendingAttempts: { keyHash, expiresAt: claimedExpiresAt } } },
    );
  }

  async reserve(input: ReserveVoiceInput): Promise<VoiceSession> {
    assertOwner(input.ownerId);
    assertDate(input.now);
    if (
      !uuid.test(input.voiceSessionId) ||
      !hash.test(input.keyHash) ||
      !hash.test(input.fingerprint)
    )
      throw new VoiceLedgerError("INVALID_REQUEST");
    return this.atomic(async (transaction) => {
      const prior = await this.sessions.findOne(
        { ownerId: input.ownerId, keyHash: input.keyHash },
        { session: transaction },
      );
      if (prior && prior.expiresAt > input.now) {
        if (prior.fingerprint !== input.fingerprint)
          throw new VoiceLedgerError("IDEMPOTENCY_CONFLICT");
        return publicSession(prior);
      }
      if (prior) throw new VoiceLedgerError("SESSION_EXPIRED");
      if (input.claimExpiresAt) {
        assertDate(input.claimExpiresAt);
        const claim = await this.usage.findOne(
          {
            ownerId: input.ownerId,
            pendingAttempts: {
              $elemMatch: {
                keyHash: input.keyHash,
                expiresAt: input.claimExpiresAt,
              },
            },
          },
          { session: transaction },
        );
        if (input.claimExpiresAt <= input.now || !claim)
          throw new VoiceLedgerError("STALE_ATTEMPT");
      }
      assertEntitlement(input.entitlement, input.now);
      const capacity = await this.capacity.findOne(
        { _id: "voice-global-v1" },
        { session: transaction },
      );
      if (capacity?.slots.length !== 2)
        throw new Error("Voice capacity migration is required");
      if (capacity.slots.some((slot) => slot.ownerId === input.ownerId))
        throw new VoiceLedgerError("SESSION_LIMIT");
      const slot = capacity.slots.find((slot) => slot.status === "FREE");
      if (!slot) throw new VoiceLedgerError("GLOBAL_CAPACITY_FULL");
      const usage =
        (await this.usage.findOne(
          { _id: usageId(input.ownerId, voiceQuotaDay(input.now)) },
          { session: transaction },
        )) ?? freshUsage(input.ownerId, input.now);
      const limits = voiceLimits(input.entitlement.packageCode);
      const seconds =
        limits.dailySeconds - usage.usedSeconds - usage.reservedSeconds;
      if (
        usage.usedSessions + usage.reservedSessions >= limits.sessionsPerDay ||
        seconds <= 0
      )
        throw new VoiceLedgerError("QUOTA_EXHAUSTED");
      const admittedSeconds = Math.min(limits.perSessionSeconds, seconds);
      const authorityExpiresAt = new Date(
        Math.min(
          input.now.getTime() + VOICE_LEASE_MS,
          input.entitlement.checkedAt.getTime() + VOICE_LEASE_MS,
          input.entitlement.validUntil?.getTime() ?? 0,
          input.authorityExpiresAt?.getTime() ?? Number.POSITIVE_INFINITY,
        ),
      );
      if (
        !Number.isFinite(authorityExpiresAt.getTime()) ||
        authorityExpiresAt <= input.now
      )
        throw new VoiceLedgerError("SESSION_EXPIRED");
      const generation = randomUUID();
      slot.status = "HELD";
      slot.generation = generation;
      slot.ownerId = input.ownerId;
      slot.voiceSessionId = input.voiceSessionId;
      const session: StoredSession = {
        _id: input.voiceSessionId,
        voiceSessionId: input.voiceSessionId,
        ownerId: input.ownerId,
        keyHash: input.keyHash,
        fingerprint: input.fingerprint,
        state: "AUTHORIZED",
        policyVersion: VOICE_POLICY_VERSION,
        entitlement: input.entitlement,
        quotaDay: usage.quotaDay,
        admittedSeconds,
        authorizedAt: input.now,
        authorityExpiresAt,
        hardStopAt: new Date(input.now.getTime() + admittedSeconds * 1000),
        createdAt: input.now,
        updatedAt: input.now,
        expiresAt: new Date(input.now.getTime() + VOICE_RETENTION_MS),
        deliveredSession: false,
        chargedSeconds: 0,
        dispatchState: "UNISSUED",
        capacityGeneration: generation,
        attemptId: null,
        leaseId: null,
        leaseExpiresAt: null,
        lastSequence: 0,
        activeMilliseconds: 0,
        openActiveSince: null,
        lastActivityAt: input.now,
        reconnectDeadline: null,
        connectDeadline: null,
        terminalReason: null,
        settledAt: null,
      };
      usage.reservedSessions += 1;
      usage.reservedSeconds += admittedSeconds;
      usage.pendingAttempts = usage.pendingAttempts.filter(
        (attempt) =>
          attempt.keyHash !== input.keyHash && attempt.expiresAt > input.now,
      );
      await this.sessions.insertOne(session, { session: transaction });
      await this.usage.replaceOne({ _id: usage._id }, usage, {
        upsert: true,
        session: transaction,
      });
      await this.usage.updateMany(
        {
          ownerId: input.ownerId,
          _id: { $ne: usage._id },
          "pendingAttempts.keyHash": input.keyHash,
        },
        { $pull: { pendingAttempts: { keyHash: input.keyHash } } },
        { session: transaction },
      );
      await this.capacity.replaceOne({ _id: capacity._id }, capacity, {
        session: transaction,
      });
      return publicSession(session);
    });
  }

  async snapshot(
    ownerId: string,
    plan: VoicePlan,
    now: Date,
  ): Promise<VoiceQuotaSnapshot> {
    const [usage, capacity] = await Promise.all([
      this.usage.findOne({
        _id: usageId(ownerId, voiceQuotaDay(now)),
        expiresAt: { $gt: now },
      }),
      this.capacity.findOne({ _id: "voice-global-v1" }),
    ]);
    const value = usage ?? freshUsage(ownerId, now);
    const limits = voiceLimits(plan);
    return {
      policyVersion: VOICE_POLICY_VERSION,
      timeZone: VOICE_TIME_ZONE,
      resetAt: voiceResetAt(now),
      quotaDay: value.quotaDay,
      plan,
      usedSessions: value.usedSessions,
      reservedSessions: value.reservedSessions,
      usedSeconds: value.usedSeconds,
      reservedSeconds: value.reservedSeconds,
      remainingSessions: Math.max(
        0,
        limits.sessionsPerDay - value.usedSessions - value.reservedSessions,
      ),
      remainingSeconds: Math.max(
        0,
        limits.dailySeconds - value.usedSeconds - value.reservedSeconds,
      ),
      startAttempts: value.startAttempts,
      ownerActive:
        capacity?.slots.some((slot) => slot.ownerId === ownerId) ?? false,
      globalAvailable:
        capacity?.slots.filter((slot) => slot.status === "FREE").length ?? 0,
      rateLimited:
        value.startAttempts >= 10 ||
        (value.minuteStartedAt.getTime() ===
          Math.floor(now.getTime() / 60_000) * 60_000 &&
          value.minuteAttempts >= 3),
    };
  }

  async availability(ownerId: string, now: Date): Promise<VoiceAvailability> {
    const [usage, capacity] = await Promise.all([
      this.usage.findOne({
        _id: usageId(ownerId, voiceQuotaDay(now)),
        expiresAt: { $gt: now },
      }),
      this.capacity.findOne({ _id: "voice-global-v1" }),
    ]);
    const dailyAttempts = usage?.startAttempts ?? 0;
    const minuteAttempts =
      usage?.minuteStartedAt.getTime() ===
      Math.floor(now.getTime() / 60_000) * 60_000
        ? usage.minuteAttempts
        : 0;
    return {
      ownerOccupied:
        capacity?.slots.some((slot) => slot.ownerId === ownerId) ?? false,
      globalOccupied:
        capacity?.slots.filter((slot) => slot.status !== "FREE").length ?? 2,
      minuteAttempts,
      dailyAttempts,
      rateLimited: dailyAttempts >= 10 || minuteAttempts >= 3,
    };
  }

  async findOwned(
    ownerId: string,
    voiceSessionId: string,
    now = new Date(),
  ): Promise<VoiceSession | null> {
    const found = await this.sessions.findOne({
      _id: voiceSessionId,
      ownerId,
      expiresAt: { $gt: now },
    });
    return found ? publicSession(found) : null;
  }

  private async load(
    voiceSessionId: string,
    transaction: ClientSession,
  ): Promise<StoredSession> {
    const found = await this.sessions.findOne(
      { _id: voiceSessionId },
      { session: transaction },
    );
    if (!found) throw new VoiceLedgerError("SESSION_NOT_FOUND");
    return found;
  }

  private async save(
    value: StoredSession,
    transaction: ClientSession,
  ): Promise<VoiceSession> {
    await this.sessions.replaceOne({ _id: value._id }, value, {
      session: transaction,
    });
    return publicSession(value);
  }

  async claimDispatch(
    voiceSessionId: string,
    now: Date,
  ): Promise<VoiceSession> {
    return this.atomic(async (transaction) => {
      const value = await this.load(voiceSessionId, transaction);
      if (value.dispatchState === "ISSUED") {
        this.live(value, now);
        return publicSession(value);
      }
      if (
        value.state !== "AUTHORIZED" ||
        now >= value.hardStopAt ||
        now.getTime() >= value.authorizedAt.getTime() + 10_000 ||
        now >= value.authorityExpiresAt
      )
        throw new VoiceLedgerError("INVALID_SESSION_STATE");
      value.state = "CONNECTING";
      value.dispatchState = "ISSUED";
      value.attemptId = randomUUID();
      value.leaseId = randomUUID();
      value.leaseExpiresAt = new Date(
        Math.min(
          value.authorityExpiresAt.getTime(),
          value.hardStopAt.getTime(),
        ),
      );
      value.connectDeadline = new Date(
        Math.min(now.getTime() + 45_000, value.hardStopAt.getTime()),
      );
      value.updatedAt = now;
      return this.save(value, transaction);
    });
  }

  private guard(value: VoiceSession, guard: VoiceGuard): boolean {
    if (
      value.attemptId !== guard.attemptId ||
      value.leaseId !== guard.leaseId ||
      value.capacityGeneration !== guard.generation
    )
      throw new VoiceLedgerError("STALE_ATTEMPT");
    if (
      !Number.isSafeInteger(guard.sequence) ||
      guard.sequence < 1 ||
      guard.sequence > 2_147_483_647
    )
      throw new VoiceLedgerError("INVALID_REQUEST");
    return guard.sequence > value.lastSequence;
  }

  private live(value: VoiceSession, now: Date) {
    assertDate(now);
    if (now < value.updatedAt) throw new VoiceLedgerError("STALE_ATTEMPT");
    if (terminal(value.state) || value.state === "ENDING")
      throw new VoiceLedgerError("INVALID_SESSION_STATE");
    if (value.expiresAt <= now || dueReason(value, now))
      throw new VoiceLedgerError("SESSION_EXPIRED");
  }

  async transition(
    voiceSessionId: string,
    guard: VoiceGuard,
    state: "ACTIVE" | "RECONNECTING",
    now: Date,
  ): Promise<VoiceSession> {
    return this.atomic(async (transaction) => {
      const value = await this.load(voiceSessionId, transaction);
      if (!this.guard(value, guard)) return publicSession(value);
      this.live(value, now);
      if (!transitions[value.state].includes(state))
        throw new VoiceLedgerError("INVALID_SESSION_STATE");
      closeActive(value, closingCut(value, now));
      value.state = state;
      value.lastSequence = guard.sequence;
      value.updatedAt = now;
      if (state === "RECONNECTING")
        value.reconnectDeadline = new Date(
          Math.min(now.getTime() + 30_000, value.hardStopAt.getTime()),
        );
      else {
        value.reconnectDeadline = null;
        value.connectDeadline = null;
        value.lastActivityAt = now;
        if (value.deliveredSession) value.openActiveSince = now;
      }
      return this.save(value, transaction);
    });
  }

  async firstReply(
    voiceSessionId: string,
    guard: VoiceGuard,
    now: Date,
  ): Promise<VoiceSession> {
    return this.atomic(async (transaction) => {
      const value = await this.load(voiceSessionId, transaction);
      if (!this.guard(value, guard)) return publicSession(value);
      this.live(value, now);
      if (value.state !== "ACTIVE")
        throw new VoiceLedgerError("INVALID_SESSION_STATE");
      if (!value.deliveredSession) {
        const updated = await this.usage.updateOne(
          {
            _id: usageId(value.ownerId, value.quotaDay),
            reservedSessions: { $gte: 1 },
          },
          { $inc: { reservedSessions: -1, usedSessions: 1 } },
          { session: transaction },
        );
        if (updated.modifiedCount !== 1)
          throw new Error("Voice session reservation is missing");
        value.deliveredSession = true;
        value.openActiveSince = now;
      }
      value.lastActivityAt = now;
      value.lastSequence = guard.sequence;
      value.updatedAt = now;
      return this.save(value, transaction);
    });
  }

  async activity(
    voiceSessionId: string,
    guard: VoiceGuard,
    now: Date,
  ): Promise<VoiceSession> {
    return this.atomic(async (transaction) => {
      const value = await this.load(voiceSessionId, transaction);
      if (!this.guard(value, guard)) return publicSession(value);
      this.live(value, now);
      if (value.state !== "ACTIVE")
        throw new VoiceLedgerError("INVALID_SESSION_STATE");
      value.lastActivityAt = now;
      value.lastSequence = guard.sequence;
      value.updatedAt = now;
      return this.save(value, transaction);
    });
  }

  async renewLease(
    voiceSessionId: string,
    guard: VoiceGuard,
    now: Date,
    entitlement: VoiceEntitlementSnapshot,
  ): Promise<VoiceSession> {
    assertEntitlement(entitlement, now);
    return this.atomic(async (transaction) => {
      const value = await this.load(voiceSessionId, transaction);
      if (!this.guard(value, guard)) return publicSession(value);
      this.live(value, now);
      value.authorityExpiresAt = new Date(
        Math.min(
          entitlement.checkedAt.getTime() + VOICE_LEASE_MS,
          value.hardStopAt.getTime(),
          entitlement.validUntil?.getTime() ?? 0,
        ),
      );
      value.leaseExpiresAt = value.authorityExpiresAt;
      value.lastSequence = guard.sequence;
      value.updatedAt = now;
      return this.save(value, transaction);
    });
  }

  async renewAuthority(
    ownerId: string,
    voiceSessionId: string,
    now: Date,
    validUntil?: Date,
  ): Promise<VoiceSession> {
    return this.atomic(async (transaction) => {
      const value = await this.load(voiceSessionId, transaction);
      if (value.ownerId !== ownerId)
        throw new VoiceLedgerError("SESSION_NOT_FOUND");
      this.live(value, now);
      const expiresAt = new Date(
        Math.min(
          now.getTime() + VOICE_LEASE_MS,
          value.hardStopAt.getTime(),
          validUntil?.getTime() ?? Number.POSITIVE_INFINITY,
        ),
      );
      if (!Number.isFinite(expiresAt.getTime()) || expiresAt <= now)
        throw new VoiceLedgerError("SESSION_EXPIRED");
      value.authorityExpiresAt = expiresAt;
      if (value.dispatchState === "ISSUED") value.leaseExpiresAt = expiresAt;
      value.updatedAt = now;
      return this.save(value, transaction);
    });
  }

  async requestEnd(
    ownerId: string,
    voiceSessionId: string,
    reason: VoiceTerminalReason,
    now: Date,
  ): Promise<VoiceSession> {
    return this.atomic(async (transaction) => {
      const value = await this.load(voiceSessionId, transaction);
      if (value.ownerId !== ownerId)
        throw new VoiceLedgerError("SESSION_NOT_FOUND");
      if (value.expiresAt <= now) throw new VoiceLedgerError("SESSION_EXPIRED");
      if (terminal(value.state) || value.state === "ENDING")
        return publicSession(value);
      closeActive(value, closingCut(value, now));
      value.state = "ENDING";
      value.terminalReason = reason;
      value.updatedAt = now;
      return this.save(value, transaction);
    });
  }

  private async settleInTransaction(
    value: StoredSession,
    proof: VoiceTeardownProof,
    now: Date,
    transaction: ClientSession,
  ): Promise<VoiceSession> {
    if (value.settledAt) return publicSession(value);
    if (
      proof === "NEVER_DISPATCHED"
        ? value.dispatchState !== "UNISSUED"
        : value.dispatchState !== "ISSUED"
    )
      throw new VoiceLedgerError("INVALID_SESSION_STATE");
    closeActive(value, closingCut(value, now));
    const capacity = await this.capacity.findOne(
      { _id: "voice-global-v1" },
      { session: transaction },
    );
    const slot = capacity?.slots.find(
      (slot) =>
        slot.voiceSessionId === value.voiceSessionId &&
        slot.generation === value.capacityGeneration &&
        slot.status === "HELD",
    );
    if (!capacity || !slot) throw new VoiceLedgerError("STALE_ATTEMPT");
    slot.status = "FREE";
    slot.generation = randomUUID();
    delete slot.ownerId;
    delete slot.voiceSessionId;
    const usedSeconds = value.deliveredSession ? value.chargedSeconds : 0;
    const usage = await this.usage.updateOne(
      {
        _id: usageId(value.ownerId, value.quotaDay),
        reservedSeconds: { $gte: value.admittedSeconds },
        reservedSessions: { $gte: value.deliveredSession ? 0 : 1 },
      },
      {
        $inc: {
          reservedSeconds: -value.admittedSeconds,
          reservedSessions: value.deliveredSession ? 0 : -1,
          usedSeconds,
        },
      },
      { session: transaction },
    );
    if (usage.modifiedCount !== 1)
      throw new Error("Voice quota reservation is missing");
    value.state =
      value.terminalReason === "USER_ENDED"
        ? "ENDED"
        : value.terminalReason === "HARD_DEADLINE" ||
            value.terminalReason === "AUTHORIZATION_EXPIRED" ||
            value.terminalReason === "RECONNECT_TIMEOUT" ||
            value.terminalReason === "IDLE_TIMEOUT"
          ? "EXPIRED"
          : "FAILED";
    value.dispatchState = "STOPPED";
    value.settledAt = now;
    value.updatedAt = now;
    value.chargedSeconds = usedSeconds;
    await this.capacity.replaceOne({ _id: capacity._id }, capacity, {
      session: transaction,
    });
    return this.save(value, transaction);
  }

  async settle(
    voiceSessionId: string,
    guard: VoiceGuard | null,
    proof: VoiceTeardownProof,
    now: Date,
  ): Promise<VoiceSession> {
    if (!["NEVER_DISPATCHED", "PROVIDER_CONFIRMED_STOPPED"].includes(proof))
      throw new VoiceLedgerError("INVALID_REQUEST");
    return this.atomic(async (transaction) => {
      const value = await this.load(voiceSessionId, transaction);
      if (value.expiresAt <= now) throw new VoiceLedgerError("SESSION_EXPIRED");
      if (value.settledAt) return publicSession(value);
      if (proof === "PROVIDER_CONFIRMED_STOPPED") {
        if (!guard || !this.guard(value, guard))
          throw new VoiceLedgerError("STALE_ATTEMPT");
        value.lastSequence = guard.sequence;
      }
      if (value.state !== "ENDING")
        throw new VoiceLedgerError("INVALID_SESSION_STATE");
      return this.settleInTransaction(value, proof, now, transaction);
    });
  }

  async reauthorizationCandidates(now: Date): Promise<VoiceSession[]> {
    const found = await this.sessions
      .find({
        dispatchState: "ISSUED",
        state: { $in: ["CONNECTING", "ACTIVE", "RECONNECTING"] },
        expiresAt: { $gt: now },
        leaseExpiresAt: {
          $lte: new Date(now.getTime() + VOICE_LEASE_RENEWAL_MS),
        },
      })
      .toArray();
    return found.map(publicSession);
  }

  async reconcile(
    now: Date,
  ): Promise<{ ended: number; purged: number; quarantined: number }> {
    const candidates = await this.sessions
      .find({ $or: [{ settledAt: null }, { expiresAt: { $lte: now } }] })
      .project<{ _id: string }>({ _id: 1 })
      .toArray();
    const result = { ended: 0, purged: 0, quarantined: 0 };
    for (const candidate of candidates) {
      const change = await this.atomic(async (transaction) => {
        const value = await this.sessions.findOne(
          { _id: candidate._id },
          { session: transaction },
        );
        if (!value) return { ended: 0, purged: 0, quarantined: 0 };
        if (value.expiresAt <= now) {
          const capacity = await this.capacity.findOne(
            { _id: "voice-global-v1" },
            { session: transaction },
          );
          const slot = capacity?.slots.find(
            (slot) =>
              slot.voiceSessionId === value.voiceSessionId &&
              slot.generation === value.capacityGeneration,
          );
          let quarantined = 0;
          if (capacity && slot) {
            if (value.dispatchState === "UNISSUED") {
              slot.status = "FREE";
              slot.generation = randomUUID();
            } else {
              slot.status = "QUARANTINED";
              quarantined = 1;
            }
            delete slot.ownerId;
            delete slot.voiceSessionId;
            await this.capacity.replaceOne({ _id: capacity._id }, capacity, {
              session: transaction,
            });
          }
          await this.sessions.deleteOne(
            { _id: value._id },
            { session: transaction },
          );
          return { ended: 0, purged: 1, quarantined };
        }
        if (value.settledAt) return { ended: 0, purged: 0, quarantined: 0 };
        if (value.state === "ENDING") {
          if (value.dispatchState === "UNISSUED") {
            await this.settleInTransaction(
              value,
              "NEVER_DISPATCHED",
              now,
              transaction,
            );
            return { ended: 1, purged: 0, quarantined: 0 };
          }
          return { ended: 0, purged: 0, quarantined: 0 };
        }
        const unissuedExpired =
          value.dispatchState === "UNISSUED" &&
          (now >= value.authorityExpiresAt ||
            now.getTime() >= value.authorizedAt.getTime() + 10_000);
        const reason = unissuedExpired
          ? "AUTHORIZATION_EXPIRED"
          : dueReason(value, now);
        if (!reason) return { ended: 0, purged: 0, quarantined: 0 };
        closeActive(value, closingCut(value, now));
        value.state = "ENDING";
        value.terminalReason = reason;
        value.updatedAt = now;
        if (value.dispatchState === "UNISSUED")
          await this.settleInTransaction(
            value,
            "NEVER_DISPATCHED",
            now,
            transaction,
          );
        else await this.save(value, transaction);
        return { ended: 1, purged: 0, quarantined: 0 };
      });
      result.ended += change.ended;
      result.purged += change.purged;
      result.quarantined += change.quarantined;
    }
    await this.usage.deleteMany({ expiresAt: { $lte: now } });
    return result;
  }
}
