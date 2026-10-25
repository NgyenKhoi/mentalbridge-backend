import { createHmac, randomUUID } from "node:crypto";
import { z } from "zod";
import { VoiceAccessError, VoiceAccessService } from "./voice-access.js";
import {
  MongoVoiceLedger,
  VoiceLedgerError,
  voiceSessionExpiryReason,
  type VoiceGuard,
  type VoiceSession,
} from "./voice-ledger.js";
import {
  VoiceMediaError,
  type VoiceMediaProvider,
} from "./voice-media-provider.js";
import {
  MongoVoiceMediaStore,
  type VoiceControl,
  type VoiceMediaDocument,
} from "./voice-media-store.js";

const signalBase = {
  schemaVersion: z.literal(1),
  commandId: z.uuid(),
  voiceSessionId: z.uuid(),
  attemptId: z.uuid(),
  sequence: z.number().int().min(1).max(2147483647),
  issuedAt: z.iso.datetime({ offset: true }),
  expiresAt: z.iso.datetime({ offset: true }),
};
const lease = { leaseId: z.uuid() };
const reply = { ...lease, turnId: z.uuid(), replyId: z.uuid() };
const reason = z.enum([
  "USER_ENDED",
  "CONSENT_REVOKED",
  "VOICE_CONSENT_REVOKED",
  "ENTITLEMENT_LOST",
  "OWNER_UNAVAILABLE",
  "IDLE_TIMEOUT",
  "HARD_DEADLINE",
  "QUOTA_EXHAUSTED",
  "RECONNECT_TIMEOUT",
  "PROVIDER_FAILURE",
  "AGENT_FAILURE",
  "AUTHORIZATION_EXPIRED",
  "ACCOUNT_DELETED",
]);
export const voiceWorkerSignalSchema = z.discriminatedUnion("kind", [
  z
    .object({
      ...signalBase,
      kind: z.literal("MEDIA_READY"),
      payload: z.object(lease).strict(),
    })
    .strict(),
  z
    .object({
      ...signalBase,
      kind: z.literal("PLAYBACK_STARTED"),
      payload: z.object(reply).strict(),
    })
    .strict(),
  z
    .object({
      ...signalBase,
      kind: z.literal("INTERRUPTED"),
      payload: z.object(reply).strict(),
    })
    .strict(),
  z
    .object({
      ...signalBase,
      kind: z.literal("HEARTBEAT"),
      payload: z
        .object({
          ...lease,
          observedState: z.enum([
            "CONNECTING",
            "ACTIVE",
            "RECONNECTING",
            "ENDING",
          ]),
        })
        .strict(),
    })
    .strict(),
  z
    .object({
      ...signalBase,
      kind: z.literal("FINISHED"),
      payload: z.object({ ...lease, reason }).strict(),
    })
    .strict(),
]);
export type VoiceWorkerSignal = z.infer<typeof voiceWorkerSignalSchema>;
const terminal = (session: VoiceSession) =>
  ["ENDED", "FAILED", "EXPIRED"].includes(session.state);
const closed = (session: VoiceSession) =>
  terminal(session) || session.state === "ENDING";

export const voiceSessionView = (session: VoiceSession) => ({
  voiceSessionId: session.voiceSessionId,
  policyVersion: session.policyVersion,
  state: session.state,
  createdAt: session.createdAt.toISOString(),
  authorizedAt: session.authorizedAt.toISOString(),
  hardStopAt: session.hardStopAt.toISOString(),
  quotaDay: session.quotaDay,
  admittedSeconds: session.admittedSeconds,
  chargedSeconds: session.chargedSeconds,
  deliveredSession: session.deliveredSession,
  ...(session.terminalReason ? { terminalReason: session.terminalReason } : {}),
});

export class VoiceSessionsService {
  constructor(
    private readonly ledger: MongoVoiceLedger,
    private readonly access: VoiceAccessService,
    private readonly store: MongoVoiceMediaStore,
    private readonly media: VoiceMediaProvider,
    private readonly secret: Buffer,
    private readonly clock: () => Date = () => new Date(),
    private readonly telemetry: (outcome: string) => void = () => undefined,
  ) {}

  private digest(value: string) {
    return createHmac("sha256", this.secret).update(value).digest("base64url");
  }
  private async current(id: string): Promise<VoiceSession> {
    const session = await this.ledger.findSession(id, this.clock());
    if (!session) throw new VoiceLedgerError("SESSION_NOT_FOUND");
    return this.expire(session);
  }
  private async owned(owner: string, id: string): Promise<VoiceSession> {
    const session = await this.ledger.findOwned(owner, id, this.clock());
    if (!session) throw new VoiceLedgerError("SESSION_NOT_FOUND");
    return this.expire(session);
  }

  private async expire(session: VoiceSession): Promise<VoiceSession> {
    const reason = closed(session)
      ? null
      : voiceSessionExpiryReason(session, this.clock());
    return reason
      ? this.ledger.requestEnd(
          session.ownerId,
          session.voiceSessionId,
          reason,
          this.clock(),
        )
      : session;
  }
  private guard(session: VoiceSession, sequence: number): VoiceGuard {
    if (!session.attemptId || !session.leaseId)
      throw new VoiceLedgerError("STALE_ATTEMPT");
    return {
      attemptId: session.attemptId,
      leaseId: session.leaseId,
      generation: session.capacityGeneration,
      sequence,
    };
  }

  async start(owner: string, key: string, correlationId: string) {
    let session: VoiceSession;
    try {
      session = await this.access.reserve(owner, key, correlationId);
    } catch (error) {
      if (
        !(error instanceof VoiceAccessError) ||
        error.code !== "SESSION_EXPIRED"
      )
        throw error;
      const prior = await this.ledger.replay(
        owner,
        this.digest(`voice:start:${owner}:${key}`),
        this.clock(),
      );
      if (!prior) throw new VoiceLedgerError("INVALID_SESSION_STATE");
      session = await this.expire(prior);
      if (!closed(session)) throw new VoiceLedgerError("INVALID_SESSION_STATE");
      await this.cleanup(session);
    }
    if (!closed(session)) {
      session = await this.ledger.claimDispatch(
        session.voiceSessionId,
        this.clock(),
      );
      const document = await this.store.ensure(session, this.clock());
      try {
        await this.establish(document, correlationId);
      } catch {
        await this.ledger.requestEnd(
          owner,
          session.voiceSessionId,
          "PROVIDER_FAILURE",
          this.clock(),
        );
        await this.cleanup(await this.current(session.voiceSessionId));
        throw new VoiceMediaError();
      }
    }
    return voiceSessionView(await this.current(session.voiceSessionId));
  }

  private async establish(
    initial: VoiceMediaDocument,
    correlationId: string,
  ): Promise<void> {
    let document = initial;
    if (["CREATING", "DISPATCHING"].includes(document.phase)) {
      const observed = await this.media.inspect(document);
      if (document.phase === "CREATING" && observed.roomExists) {
        document =
          (await this.store.advance(document, "ROOM_READY", this.clock())) ??
          document;
      } else if (
        document.phase === "DISPATCHING" &&
        observed.providerDispatchId
      ) {
        await this.store.advance(
          document,
          "READY",
          this.clock(),
          observed.providerDispatchId,
        );
        return;
      } else {
        return;
      }
    }
    if (document.phase === "NEW") {
      const session = await this.current(document.voiceSessionId);
      await this.access.authorizeSession(
        session.ownerId,
        session.voiceSessionId,
        correlationId,
      );
      const claimed = await this.store.advance(
        document,
        "CREATING",
        this.clock(),
      );
      if (!claimed) return;
      await this.media.createRoom(claimed);
      const completed = await this.store.advance(
        claimed,
        "ROOM_READY",
        this.clock(),
      );
      if (!completed) return;
      document = completed;
    }
    if (document.phase === "ROOM_READY") {
      const session = await this.current(document.voiceSessionId);
      await this.access.authorizeSession(
        session.ownerId,
        session.voiceSessionId,
        correlationId,
      );
      const claimed = await this.store.advance(
        document,
        "DISPATCHING",
        this.clock(),
      );
      if (!claimed) return;
      const providerId = await this.media.dispatch(claimed);
      await this.store.advance(claimed, "READY", this.clock(), providerId);
    }
  }

  async status(owner: string, id: string) {
    return voiceSessionView(await this.owned(owner, id));
  }

  async connection(
    owner: string,
    id: string,
    key: string,
    correlationId: string,
  ) {
    const owned = await this.owned(owner, id);
    await this.store.bindCommand(
      this.digest(`voice:connection:${owner}:${key}`),
      "connection",
      owned,
      this.clock(),
    );
    const session = await this.access.authorizeSession(
      owner,
      id,
      correlationId,
    );
    const document = await this.store.find(id, this.clock());
    if (document?.phase !== "READY")
      throw new VoiceLedgerError("INVALID_SESSION_STATE");
    if (document.attemptId !== session.attemptId)
      throw new VoiceLedgerError("STALE_ATTEMPT");
    const connection = await this.media.connection(document, this.clock());
    const latest = await this.current(id);
    if (
      closed(latest) ||
      this.clock() >= latest.hardStopAt ||
      this.clock() >= latest.authorityExpiresAt ||
      (latest.leaseExpiresAt !== null &&
        this.clock() >= latest.leaseExpiresAt) ||
      Date.parse(connection.tokenExpiresAt) <= this.clock().getTime() ||
      connection.voiceSessionId !== id ||
      Date.parse(connection.tokenExpiresAt) > latest.hardStopAt.getTime()
    )
      throw new VoiceLedgerError("SESSION_EXPIRED");
    return connection;
  }

  async end(owner: string, id: string, key: string) {
    const session = await this.owned(owner, id);
    await this.store.bindCommand(
      this.digest(`voice:end:${owner}:${key}`),
      "end",
      session,
      this.clock(),
    );
    if (!terminal(session)) {
      const ending = await this.ledger.requestEnd(
        owner,
        id,
        "USER_ENDED",
        this.clock(),
      );
      await this.cleanup(ending);
    }
    return voiceSessionView(await this.current(id));
  }

  private async cleanup(session: VoiceSession): Promise<void> {
    if (session.state !== "ENDING") return;
    let document = await this.store.find(session.voiceSessionId, this.clock());
    try {
      if (!document) {
        if (session.dispatchState === "UNISSUED")
          await this.ledger.settle(
            session.voiceSessionId,
            null,
            "NEVER_DISPATCHED",
            this.clock(),
          );
        else
          await this.ledger.settle(
            session.voiceSessionId,
            null,
            "NO_MEDIA_INITIALIZATION",
            this.clock(),
          );
        return;
      }
      if (["CREATING", "DISPATCHING"].includes(document.phase)) {
        const observed = await this.media.inspect(document);
        if (document.phase === "CREATING" && observed.roomExists) {
          document =
            (await this.store.advance(document, "ROOM_READY", this.clock())) ??
            document;
        } else if (
          document.phase === "DISPATCHING" &&
          observed.providerDispatchId
        ) {
          document =
            (await this.store.advance(
              document,
              "READY",
              this.clock(),
              observed.providerDispatchId,
            )) ?? document;
        } else {
          return;
        }
      }
      if (document.phase !== "STOPPED") {
        const stopping =
          document.phase === "STOPPING"
            ? document
            : await this.store.advance(document, "STOPPING", this.clock());
        if (!stopping) return;
        await this.media.stop(stopping);
        await this.store.advance(stopping, "STOPPED", this.clock());
      }
      const latest = await this.current(session.voiceSessionId);
      await this.ledger.settle(
        latest.voiceSessionId,
        this.guard(latest, latest.lastSequence + 1),
        "PROVIDER_CONFIRMED_STOPPED",
        this.clock(),
      );
    } catch {
      this.telemetry("VOICE_CLEANUP_PENDING");
    }
  }

  async claim(
    id: string,
    attemptId: string,
    dispatchId: string,
    correlationId: string,
  ) {
    let session = await this.current(id);
    const document = await this.store.find(id, this.clock());
    if (
      document?.attemptId !== attemptId ||
      document.dispatchId !== dispatchId ||
      session.attemptId !== attemptId ||
      !["DISPATCHING", "READY"].includes(document.phase)
    )
      throw new VoiceLedgerError("STALE_ATTEMPT");
    session = document.claimedAt
      ? await this.access.inspectSession(session.ownerId, id, correlationId)
      : await this.access.authorizeSession(session.ownerId, id, correlationId);
    await this.store.claim(document, this.clock());
    if (!session.leaseId || !session.leaseExpiresAt)
      throw new VoiceLedgerError("STALE_ATTEMPT");
    return {
      voiceSessionId: id,
      attemptId,
      leaseId: session.leaseId,
      validUntil: session.leaseExpiresAt.toISOString(),
      hardStopAt: session.hardStopAt.toISOString(),
      policyVersion: session.policyVersion,
    };
  }

  async control(
    id: string,
    input: VoiceWorkerSignal,
    correlationId: string,
  ): Promise<VoiceControl> {
    let session = await this.current(id);
    const document = await this.store.find(id, this.clock());
    if (
      !document?.claimedAt ||
      input.voiceSessionId !== id ||
      input.attemptId !== session.attemptId ||
      document.attemptId !== input.attemptId ||
      input.payload.leaseId !== session.leaseId
    )
      throw new VoiceLedgerError("STALE_ATTEMPT");
    const fingerprint = this.digest(JSON.stringify(input));
    if (
      (input.kind === "PLAYBACK_STARTED" || input.kind === "INTERRUPTED") &&
      (document.expectedTurnId !== input.payload.turnId ||
        document.expectedReplyId !== input.payload.replyId)
    )
      throw new VoiceLedgerError("INVALID_SESSION_STATE");
    const repeated = input.sequence <= document.workerSequence;
    if (!repeated && input.sequence <= session.lastSequence)
      throw new VoiceLedgerError("STALE_ATTEMPT");
    if (
      repeated &&
      (input.sequence !== document.workerSequence ||
        fingerprint !== document.workerFingerprint ||
        !document.lastControl)
    )
      throw new VoiceLedgerError("STALE_ATTEMPT");
    const now = this.clock();
    if (
      Date.parse(input.issuedAt) > now.getTime() ||
      Date.parse(input.expiresAt) <= now.getTime() ||
      Date.parse(input.expiresAt) <= Date.parse(input.issuedAt) ||
      Date.parse(input.expiresAt) > (session.leaseExpiresAt?.getTime() ?? 0)
    )
      throw new VoiceLedgerError("SESSION_EXPIRED");
    if (input.kind === "FINISHED") {
      session = await this.ledger.requestEnd(
        session.ownerId,
        id,
        input.payload.reason,
        this.clock(),
      );
    } else if (!closed(session)) {
      try {
        session = repeated
          ? await this.access.inspectSession(session.ownerId, id, correlationId)
          : await this.access.authorizeSession(
              session.ownerId,
              id,
              correlationId,
            );
      } catch (error) {
        session = await this.current(id);
        if (!closed(session)) throw error;
      }
    }
    if (!closed(session) && !repeated) {
      let observed;
      try {
        observed = await this.media.inspect(document);
      } catch {
        session = await this.ledger.requestEnd(
          session.ownerId,
          id,
          "PROVIDER_FAILURE",
          this.clock(),
        );
        await this.cleanup(session);
        return this.respond(
          document,
          input.sequence,
          fingerprint,
          await this.current(id),
        );
      }
      const guard = this.guard(session, input.sequence);
      if (input.kind === "PLAYBACK_STARTED" || input.kind === "INTERRUPTED") {
        if (
          document.expectedTurnId !== input.payload.turnId ||
          document.expectedReplyId !== input.payload.replyId ||
          !observed.ownerConnected ||
          !observed.agentAudioPublished
        )
          throw new VoiceLedgerError("INVALID_SESSION_STATE");
        await (input.kind === "PLAYBACK_STARTED"
          ? this.ledger.firstReply(id, guard, this.clock())
          : this.ledger.activity(id, guard, this.clock()));
      } else if (
        observed.ownerConnected &&
        observed.agentConnected &&
        ["CONNECTING", "RECONNECTING"].includes(session.state)
      ) {
        await this.ledger.transition(id, guard, "ACTIVE", this.clock());
      } else if (!observed.ownerConnected && session.state === "ACTIVE") {
        await this.ledger.transition(id, guard, "RECONNECTING", this.clock());
      } else {
        await this.ledger.acknowledgeSignal(id, guard, this.clock());
      }
    }
    session = await this.current(id);
    if (closed(session)) await this.cleanup(session);
    if (
      repeated &&
      !closed(session) &&
      document.lastControl &&
      Date.parse(document.lastControl.control.expiresAt) >
        this.clock().getTime()
    )
      return document.lastControl;
    return this.respond(
      document,
      input.sequence,
      fingerprint,
      await this.current(id),
    );
  }

  private async respond(
    document: VoiceMediaDocument,
    workerSequence: number,
    fingerprint: string,
    session: VoiceSession,
  ): Promise<VoiceControl> {
    const now = this.clock();
    const sequence = await this.store.nextBackendSequence(document, now);
    const base = {
      schemaVersion: 1 as const,
      commandId: randomUUID(),
      voiceSessionId: session.voiceSessionId,
      attemptId: document.attemptId,
      sequence,
      issuedAt: now.toISOString(),
    };
    const response: VoiceControl = closed(session)
      ? {
          action: "STOP",
          control: {
            ...base,
            kind: "STOP",
            expiresAt: new Date(now.getTime() + 10_000).toISOString(),
            payload: { reason: session.terminalReason ?? "AGENT_FAILURE" },
          },
        }
      : {
          action: "CONTINUE",
          control: {
            ...base,
            kind: "LEASE_RENEWED",
            expiresAt: (session.leaseExpiresAt ?? now).toISOString(),
            payload: {
              leaseId: session.leaseId ?? "",
              validUntil: (session.leaseExpiresAt ?? now).toISOString(),
              hardStopAt: session.hardStopAt.toISOString(),
              admittedSeconds: session.admittedSeconds,
            },
          },
        };
    return this.store.control(
      document,
      fingerprint,
      workerSequence,
      response,
      now,
    );
  }

  async registerReply(
    id: string,
    attemptId: string,
    turnId: string,
    replyId: string,
    correlationId: string,
  ): Promise<void> {
    for (const value of [id, attemptId, turnId, replyId])
      if (!z.uuid().safeParse(value).success)
        throw new VoiceLedgerError("INVALID_REQUEST");
    const session = await this.current(id);
    if (session.attemptId !== attemptId || session.state !== "ACTIVE")
      throw new VoiceLedgerError("STALE_ATTEMPT");
    await this.access.authorizeSession(session.ownerId, id, correlationId);
    await this.store.registerReply(
      id,
      attemptId,
      turnId,
      replyId,
      this.clock(),
    );
  }

  async reconcile(): Promise<void> {
    await this.access.reconcile(
      async (session) =>
        session.state === "CONNECTING" &&
        !(await this.store.find(session.voiceSessionId, this.clock()))
          ?.claimedAt,
    );
    const pending = await this.store.pending(this.clock());
    await Promise.all(
      pending.map(async (document) => {
        try {
          const session = await this.ledger.findSession(
            document.voiceSessionId,
            this.clock(),
          );
          if (!session) return;
          if (terminal(session) && document.phase === "NEW") {
            await this.store.advance(document, "STOPPED", this.clock());
            return;
          }
          if (closed(session)) {
            await this.cleanup(session);
            return;
          }
          await this.establish(document, randomUUID());
        } catch {
          this.telemetry("VOICE_RECONCILIATION_PENDING");
        }
      }),
    );
    const processed = new Set(
      pending.map((document) => document.voiceSessionId),
    );
    for (const ending of await this.ledger.endingSessions(this.clock())) {
      if (!processed.has(ending.voiceSessionId)) await this.cleanup(ending);
    }
    await this.store.purge(this.clock());
  }
}
