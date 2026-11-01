import { MongoClient, MongoServerError, type Collection } from "mongodb";
import { VoiceLedgerError } from "./voice-ledger.js";

export interface VoiceTurnFence {
  _id: string;
  operation: "turn";
  voiceSessionId: string;
  attemptId: string;
  turnId: string;
  sequence: number;
  contextId: string;
  fingerprint: string;
  routeHash: string;
  createdAt: Date;
  expiresAt: Date;
}

export class MongoVoiceTurnStore {
  private readonly commands: Collection<VoiceTurnFence>;
  constructor(client: MongoClient, database: string) {
    this.commands = client.db(database).collection("ai_voice_media_commands");
  }

  async claim(fence: VoiceTurnFence): Promise<boolean> {
    const existing = await this.commands.findOne({
      _id: fence._id,
      operation: "turn",
      expiresAt: { $gt: fence.createdAt },
    });
    if (existing) return this.replay(existing, fence);
    const previous = await this.commands.findOne(
      {
        voiceSessionId: fence.voiceSessionId,
        operation: "turn",
        expiresAt: { $gt: fence.createdAt },
      },
      { sort: { sequence: -1 } },
    );
    if (
      fence.sequence !== (previous?.sequence ?? 0) + 1 ||
      (previous &&
        (previous.contextId !== fence.contextId ||
          previous.attemptId !== fence.attemptId))
    )
      throw new VoiceLedgerError("STALE_ATTEMPT");
    try {
      await this.commands.insertOne(fence);
      return true;
    } catch (error) {
      if (!(error instanceof MongoServerError) || error.code !== 11000)
        throw error;
      const prior = await this.commands.findOne({
        _id: fence._id,
        operation: "turn",
      });
      if (prior) return this.replay(prior, fence);
      throw new VoiceLedgerError("IDEMPOTENCY_CONFLICT");
    }
  }

  private replay(prior: VoiceTurnFence, current: VoiceTurnFence): false {
    if (
      prior.voiceSessionId !== current.voiceSessionId ||
      prior.attemptId !== current.attemptId ||
      prior.turnId !== current.turnId ||
      prior.sequence !== current.sequence ||
      prior.fingerprint !== current.fingerprint ||
      prior.routeHash !== current.routeHash
    )
      throw new VoiceLedgerError("IDEMPOTENCY_CONFLICT");
    return false;
  }
}
