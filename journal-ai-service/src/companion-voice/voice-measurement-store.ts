import { randomUUID } from "node:crypto";
import type { ClientSession, Collection, MongoClient } from "mongodb";
import { VoiceLedgerError, type VoiceSession } from "./voice-ledger.js";
import { VOICE_RETENTION_MS } from "./voice-policy.js";
import {
  measurementPolicy,
  type MeasurementPilot,
  type MeasurementPolicy,
} from "./voice-measurement-policy.js";

interface Reservation {
  voiceSessionId: string;
  createdAt: Date;
  releasedAt: Date | null;
  workerClaimed: boolean;
  turns: number;
  voiceSeconds: number;
  ttsCharacters: number;
  turnReservations: { turnId: string; sequence: number }[];
  speechReservations: { turnId: string; replyId: string; characters: number }[];
}
export interface MeasurementDocument {
  _id: string;
  configHash: string;
  startsAt: Date;
  endsAt: Date;
  caps: MeasurementPolicy["caps"];
  allocation: MeasurementPolicy["allocation"];
  reservations: Reservation[];
  createdAt: Date;
  updatedAt: Date;
  expiresAt: Date;
}
function denied(): never {
  throw new VoiceLedgerError("QUOTA_EXHAUSTED");
}
export class MongoVoiceMeasurementStore {
  private readonly campaigns: Collection<MeasurementDocument>;
  readonly policy: MeasurementPolicy | undefined;
  constructor(
    private readonly client: MongoClient,
    private readonly database: string,
    pilot?: MeasurementPilot,
  ) {
    this.campaigns = client
      .db(database)
      .collection("ai_voice_measurement_campaigns");
    this.policy = measurementPolicy(pilot);
  }

  async provision(now: Date): Promise<void> {
    const p = this.policy;
    if (!p || !Number.isFinite(now.getTime()) || now >= p.endsAt) denied();
    const transaction = this.client.startSession();
    try {
      await transaction.withTransaction(
        async () => {
          const capacity = this.client.db(this.database).collection<{
            _id: string;
            slots: { status: string; generation: string }[];
          }>("ai_voice_capacity");
          const current = await capacity.findOne(
            { _id: "voice-global-v1" },
            { session: transaction },
          );
          if (
            current?.slots.length !== 2 ||
            current.slots.some((s) => s.status !== "FREE")
          )
            denied();
          const existing = await this.campaigns.findOne(
            { _id: p.campaignId },
            { session: transaction },
          );
          if (existing) {
            if (existing.configHash !== p.configHash) denied();
            return;
          }
          if (
            await this.client
              .db(this.database)
              .collection("ai_voice_sessions")
              .findOne(
                { createdAt: { $gte: p.startsAt, $lt: p.endsAt } },
                { session: transaction },
              )
          )
            denied();
          if (
            await this.campaigns.findOne(
              {
                $or: [
                  { startsAt: { $lt: p.endsAt }, endsAt: { $gt: p.startsAt } },
                  { reservations: { $elemMatch: { releasedAt: null } } },
                ],
              },
              { session: transaction },
            )
          )
            denied();
          const slot = current.slots[0];
          if (!slot) denied();
          slot.generation = randomUUID();
          await capacity.replaceOne({ _id: current._id }, current, {
            session: transaction,
          });
          await this.campaigns.insertOne(
            {
              _id: p.campaignId,
              configHash: p.configHash,
              startsAt: p.startsAt,
              endsAt: p.endsAt,
              caps: p.caps,
              allocation: p.allocation,
              reservations: [],
              createdAt: now,
              updatedAt: now,
              expiresAt: new Date(now.getTime() + VOICE_RETENTION_MS),
            },
            { session: transaction },
          );
        },
        { readConcern: { level: "snapshot" }, writeConcern: { w: "majority" } },
      );
    } finally {
      await transaction.endSession();
    }
  }

  async reserve(
    voiceSessionId: string,
    now: Date,
    transaction: ClientSession,
  ): Promise<void> {
    const p = this.policy;
    if (!p) {
      if (
        await this.campaigns.findOne(
          { startsAt: { $lte: now }, endsAt: { $gt: now } },
          { session: transaction },
        )
      )
        denied();
      return;
    }
    const document = await this.campaigns.findOne(
      { _id: p.campaignId },
      { session: transaction },
    );
    this.bound(document, now);
    if (!document) denied();
    if (document.reservations.some((r) => r.voiceSessionId === voiceSessionId))
      return;
    if (
      document.reservations.length >= p.caps.sessions ||
      document.reservations.filter((r) => r.releasedAt === null).length >=
        p.caps.concurrent
    )
      denied();
    for (const key of ["turns", "voiceSeconds", "ttsCharacters"] as const)
      if (
        document.reservations.reduce((sum, r) => sum + r[key], 0) +
          p.allocation[key] >
        p.caps[key]
      )
        denied();
    document.reservations.push({
      voiceSessionId,
      createdAt: now,
      releasedAt: null,
      workerClaimed: false,
      ...p.allocation,
      turnReservations: [],
      speechReservations: [],
    });
    document.updatedAt = now;
    await this.campaigns.replaceOne({ _id: document._id }, document, {
      session: transaction,
    });
  }

  private bound(document: MeasurementDocument | null, now: Date): void {
    const p = this.policy;
    if (
      !p ||
      !Number.isFinite(now.getTime()) ||
      document?.configHash !== p.configHash ||
      now < document.startsAt ||
      now >= document.endsAt ||
      now >= document.expiresAt
    )
      denied();
    for (const key of [
      "sessions",
      "concurrent",
      "turns",
      "voiceSeconds",
      "ttsCharacters",
    ] as const)
      if (document.caps[key] !== p.caps[key]) denied();
    for (const key of ["turns", "voiceSeconds", "ttsCharacters"] as const)
      if (
        document.allocation[key] !== p.allocation[key] ||
        document.reservations.some((r) => r[key] !== p.allocation[key]) ||
        document.reservations.reduce((sum, r) => sum + r[key], 0) > p.caps[key]
      )
        denied();
    if (
      document.startsAt.getTime() !== p.startsAt.getTime() ||
      document.endsAt.getTime() !== p.endsAt.getTime() ||
      document.reservations.length > p.caps.sessions ||
      document.reservations.filter((r) => r.releasedAt === null).length >
        p.caps.concurrent
    )
      denied();
    if (
      new Set(document.reservations.map((r) => r.voiceSessionId)).size !==
      document.reservations.length
    )
      denied();
    for (const r of document.reservations) {
      if (
        r.turnReservations.length > r.turns ||
        new Set(r.turnReservations.map((t) => t.sequence)).size !==
          r.turnReservations.length ||
        new Set(r.turnReservations.map((t) => t.turnId)).size !==
          r.turnReservations.length ||
        r.turnReservations.some(
          (t) => t.sequence < 1 || t.sequence > r.turns,
        ) ||
        (r.turnReservations.length > 0 && !r.workerClaimed) ||
        new Set(r.speechReservations.map((s) => s.turnId)).size !==
          r.speechReservations.length ||
        r.speechReservations.some(
          (s) => !r.turnReservations.some((t) => t.turnId === s.turnId),
        ) ||
        r.speechReservations.reduce((n, s) => n + s.characters, 0) >
          r.ttsCharacters
      )
        denied();
    }
  }

  async authorize(
    voiceSessionId: string,
    now: Date,
    transaction?: ClientSession,
  ) {
    const document = await this.campaigns.findOne(
      { "reservations.voiceSessionId": voiceSessionId },
      transaction ? { session: transaction } : {},
    );
    if (!document && !this.policy) {
      if (
        await this.campaigns.findOne(
          { startsAt: { $lte: now }, endsAt: { $gt: now } },
          transaction ? { session: transaction } : {},
        )
      )
        denied();
      return undefined;
    }
    this.bound(document, now);
    const reservation = document?.reservations.find(
      (r) => r.voiceSessionId === voiceSessionId,
    );
    if (!reservation || reservation.releasedAt) denied();
    return this.policy;
  }

  async release(
    session: Pick<
      VoiceSession,
      "voiceSessionId" | "state" | "dispatchState" | "settledAt"
    >,
    now: Date,
    transaction: ClientSession,
  ): Promise<void> {
    if (
      session.dispatchState !== "STOPPED" ||
      !session.settledAt ||
      !["ENDED", "FAILED", "EXPIRED"].includes(session.state)
    )
      denied();
    const existing = await this.campaigns.findOne(
      { "reservations.voiceSessionId": session.voiceSessionId },
      { session: transaction },
    );
    if (!existing) return;
    await this.campaigns.updateOne(
      {
        reservations: {
          $elemMatch: {
            voiceSessionId: session.voiceSessionId,
            releasedAt: null,
          },
        },
      },
      { $set: { "reservations.$.releasedAt": now, updatedAt: now } },
      { session: transaction },
    );
  }

  private async change(
    voiceSessionId: string,
    now: Date,
    action: (reservation: Reservation) => void,
  ): Promise<void> {
    const transaction = this.client.startSession();
    try {
      await transaction.withTransaction(
        async () => {
          const policy = await this.authorize(voiceSessionId, now, transaction);
          if (!policy) return;
          const document = await this.campaigns.findOne(
            { _id: policy.campaignId },
            { session: transaction },
          );
          const reservation = document?.reservations.find(
            (r) => r.voiceSessionId === voiceSessionId,
          );
          if (!document || !reservation) denied();
          action(reservation);
          document.updatedAt = now;
          await this.campaigns.replaceOne({ _id: document._id }, document, {
            session: transaction,
          });
        },
        { readConcern: { level: "snapshot" }, writeConcern: { w: "majority" } },
      );
    } finally {
      await transaction.endSession();
    }
  }

  async claimWorker(voiceSessionId: string, now: Date): Promise<void> {
    await this.change(voiceSessionId, now, (r) => {
      if (r.workerClaimed) denied();
      r.workerClaimed = true;
    });
  }

  async reserveTurn(
    voiceSessionId: string,
    turnId: string,
    sequence: number,
    now: Date,
  ): Promise<void> {
    await this.change(voiceSessionId, now, (r) => {
      if (
        !r.workerClaimed ||
        !Number.isSafeInteger(sequence) ||
        sequence < 1 ||
        sequence > r.turns ||
        r.turnReservations.some(
          (t) => t.sequence === sequence || t.turnId === turnId,
        ) ||
        r.turnReservations.length >= r.turns
      )
        denied();
      r.turnReservations.push({ turnId, sequence });
    });
  }

  async reserveSpeech(
    voiceSessionId: string,
    turnId: string,
    replyId: string,
    characters: number,
    now: Date,
  ): Promise<void> {
    await this.change(voiceSessionId, now, (r) => {
      if (
        !Number.isSafeInteger(characters) ||
        characters < 1 ||
        characters > 1000 ||
        !r.turnReservations.some((t) => t.turnId === turnId) ||
        r.speechReservations.some((s) => s.turnId === turnId) ||
        r.speechReservations.reduce((sum, s) => sum + s.characters, 0) +
          characters >
          r.ttsCharacters
      )
        denied();
      r.speechReservations.push({ turnId, replyId, characters });
    });
  }

  snapshot(now: Date) {
    const p = this.policy;
    return p
      ? this.campaigns.findOne({
          _id: p.campaignId,
          configHash: p.configHash,
          expiresAt: { $gt: now },
        })
      : Promise.resolve(null);
  }

  async preflight(now: Date) {
    const document = await this.snapshot(now);
    this.bound(document, now);
    if (!document) denied();
    return {
      configHash: document.configHash,
      caps: document.caps,
      allocation: document.allocation,
      reservedSessions: document.reservations.length,
      heldSessions: document.reservations.filter((r) => r.releasedAt === null)
        .length,
      reservedTurns: document.reservations.reduce((n, r) => n + r.turns, 0),
      reservedVoiceSeconds: document.reservations.reduce(
        (n, r) => n + r.voiceSeconds,
        0,
      ),
      reservedTtsCharacters: document.reservations.reduce(
        (n, r) => n + r.ttsCharacters,
        0,
      ),
    };
  }
}
