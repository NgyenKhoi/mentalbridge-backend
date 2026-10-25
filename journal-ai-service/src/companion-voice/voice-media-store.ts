import { randomUUID } from "node:crypto";
import { MongoServerError, type Collection, type MongoClient } from "mongodb";
import {
  VoiceLedgerError,
  type VoiceSession,
  type VoiceTerminalReason,
} from "./voice-ledger.js";
import type { VoiceMediaReference } from "./voice-media-provider.js";

export type VoiceMediaPhase =
  | "NEW"
  | "CREATING"
  | "ROOM_READY"
  | "DISPATCHING"
  | "READY"
  | "STOPPING"
  | "STOPPED";
interface ControlBase {
  schemaVersion: 1;
  commandId: string;
  voiceSessionId: string;
  attemptId: string;
  sequence: number;
  issuedAt: string;
  expiresAt: string;
}
export type VoiceControl =
  | {
      action: "CONTINUE";
      control: ControlBase & {
        kind: "LEASE_RENEWED";
        payload: {
          leaseId: string;
          validUntil: string;
          hardStopAt: string;
          admittedSeconds: number;
        };
      };
    }
  | {
      action: "STOP";
      control: ControlBase & {
        kind: "STOP";
        payload: { reason: VoiceTerminalReason };
      };
    };

export interface VoiceMediaDocument extends VoiceMediaReference {
  _id: string;
  phase: VoiceMediaPhase;
  claimedAt: Date | null;
  workerSequence: number;
  workerFingerprint: string | null;
  backendSequence: number;
  lastControl: VoiceControl | null;
  expectedTurnId: string | null;
  expectedReplyId: string | null;
  createdAt: Date;
  updatedAt: Date;
  expiresAt: Date;
}
interface VoiceCommand {
  _id: string;
  operation: "connection" | "end";
  voiceSessionId: string;
  createdAt: Date;
  expiresAt: Date;
}

export class MongoVoiceMediaStore {
  private readonly media: Collection<VoiceMediaDocument>;
  private readonly commands: Collection<VoiceCommand>;
  constructor(client: MongoClient, databaseName: string) {
    const database = client.db(databaseName);
    this.media = database.collection("ai_voice_media");
    this.commands = database.collection("ai_voice_media_commands");
  }

  async ensure(session: VoiceSession, now: Date): Promise<VoiceMediaDocument> {
    if (!session.attemptId || session.dispatchState !== "ISSUED")
      throw new VoiceLedgerError("INVALID_SESSION_STATE");
    const document: VoiceMediaDocument = {
      _id: session.voiceSessionId,
      voiceSessionId: session.voiceSessionId,
      attemptId: session.attemptId,
      dispatchId: randomUUID(),
      roomName: `ai-voice-${randomUUID()}`,
      participantIdentity: `vu-${randomUUID()}`,
      agentIdentity: `va-${session.attemptId}`,
      hardStopAt: session.hardStopAt,
      providerDispatchId: null,
      phase: "NEW",
      claimedAt: null,
      workerSequence: 0,
      workerFingerprint: null,
      backendSequence: 0,
      lastControl: null,
      expectedTurnId: null,
      expectedReplyId: null,
      createdAt: session.createdAt,
      updatedAt: now,
      expiresAt: session.expiresAt,
    };
    try {
      await this.media.insertOne(document);
    } catch (error) {
      if (!(error instanceof MongoServerError) || error.code !== 11000)
        throw error;
    }
    const existing = await this.find(session.voiceSessionId, now);
    if (existing?.attemptId !== session.attemptId)
      throw new VoiceLedgerError("STALE_ATTEMPT");
    return existing;
  }

  find(voiceSessionId: string, now: Date): Promise<VoiceMediaDocument | null> {
    return this.media.findOne({ _id: voiceSessionId, expiresAt: { $gt: now } });
  }

  async advance(
    document: VoiceMediaDocument,
    next: VoiceMediaPhase,
    now: Date,
    providerDispatchId = document.providerDispatchId,
  ): Promise<VoiceMediaDocument | null> {
    return this.media.findOneAndUpdate(
      {
        _id: document._id,
        phase: document.phase,
        attemptId: document.attemptId,
        expiresAt: { $gt: now },
      },
      { $set: { phase: next, providerDispatchId, updatedAt: now } },
      { returnDocument: "after" },
    );
  }

  async claim(
    document: VoiceMediaDocument,
    now: Date,
  ): Promise<VoiceMediaDocument> {
    const value = await this.media.findOneAndUpdate(
      {
        _id: document._id,
        attemptId: document.attemptId,
        phase: { $in: ["DISPATCHING", "READY"] },
        claimedAt: null,
        expiresAt: { $gt: now },
      },
      { $set: { claimedAt: now, updatedAt: now } },
      { returnDocument: "after" },
    );
    const current = value ?? (await this.find(document._id, now));
    if (
      !current?.claimedAt ||
      !["DISPATCHING", "READY"].includes(current.phase)
    )
      throw new VoiceLedgerError("STALE_ATTEMPT");
    return current;
  }

  async bindCommand(
    keyHash: string,
    operation: VoiceCommand["operation"],
    session: VoiceSession,
    now: Date,
  ): Promise<void> {
    const command: VoiceCommand = {
      _id: keyHash,
      operation,
      voiceSessionId: session.voiceSessionId,
      createdAt: now,
      expiresAt: session.expiresAt,
    };
    try {
      await this.commands.insertOne(command);
    } catch (error) {
      if (!(error instanceof MongoServerError) || error.code !== 11000)
        throw error;
      const prior = await this.commands.findOne({
        _id: keyHash,
        expiresAt: { $gt: now },
      });
      if (
        prior?.voiceSessionId !== session.voiceSessionId ||
        prior.operation !== operation
      )
        throw new VoiceLedgerError("IDEMPOTENCY_CONFLICT");
    }
  }

  async control(
    document: VoiceMediaDocument,
    fingerprint: string,
    workerSequence: number,
    control: VoiceControl,
    now: Date,
  ): Promise<VoiceControl> {
    const value = await this.media.findOneAndUpdate(
      {
        _id: document._id,
        attemptId: document.attemptId,
        workerSequence: { $lt: workerSequence },
        expiresAt: { $gt: now },
      },
      {
        $set: {
          workerSequence,
          workerFingerprint: fingerprint,
          lastControl: control,
          updatedAt: now,
        },
      },
      { returnDocument: "after" },
    );
    if (value?.lastControl) return value.lastControl;
    if (control.action === "STOP") {
      const stopped = await this.media.findOneAndUpdate(
        {
          _id: document._id,
          workerSequence,
          workerFingerprint: fingerprint,
          "lastControl.action": "CONTINUE",
          expiresAt: { $gt: now },
        },
        { $set: { lastControl: control, updatedAt: now } },
        { returnDocument: "after" },
      );
      if (stopped?.lastControl) return stopped.lastControl;
    }
    const current = await this.find(document._id, now);
    if (
      !current?.lastControl ||
      current.workerSequence !== workerSequence ||
      current.workerFingerprint !== fingerprint
    )
      throw new VoiceLedgerError("STALE_ATTEMPT");
    return current.lastControl;
  }

  async registerReply(
    voiceSessionId: string,
    attemptId: string,
    turnId: string,
    replyId: string,
    now: Date,
  ): Promise<void> {
    const result = await this.media.updateOne(
      {
        _id: voiceSessionId,
        attemptId,
        phase: "READY",
        claimedAt: { $ne: null },
        expiresAt: { $gt: now },
      },
      {
        $set: {
          expectedTurnId: turnId,
          expectedReplyId: replyId,
          updatedAt: now,
        },
      },
    );
    if (result.matchedCount !== 1) throw new VoiceLedgerError("STALE_ATTEMPT");
  }

  async nextBackendSequence(
    document: VoiceMediaDocument,
    now: Date,
  ): Promise<number> {
    const value = await this.media.findOneAndUpdate(
      {
        _id: document._id,
        attemptId: document.attemptId,
        backendSequence: { $lt: 2147483647 },
        expiresAt: { $gt: now },
      },
      { $inc: { backendSequence: 1 } },
      { returnDocument: "after" },
    );
    if (!value) throw new VoiceLedgerError("STALE_ATTEMPT");
    return value.backendSequence;
  }

  pending(now: Date): Promise<VoiceMediaDocument[]> {
    return this.media
      .find({ phase: { $ne: "STOPPED" }, expiresAt: { $gt: now } })
      .toArray();
  }

  async purge(now: Date): Promise<void> {
    await Promise.all([
      this.media.deleteMany({ expiresAt: { $lte: now } }),
      this.commands.deleteMany({ expiresAt: { $lte: now } }),
    ]);
  }
}
