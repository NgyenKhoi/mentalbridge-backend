import { createHmac, randomUUID, timingSafeEqual } from "node:crypto";
import { z } from "zod";
import type {
  Conversation,
  ChatCrypto,
} from "../companion-chat/companion-chat.js";
import { VoiceAccessError } from "./voice-access.js";
import type { VoiceSession } from "./voice-ledger.js";

export const TEXT_VOICE_HANDOFF_POLICY = "text-voice-handoff-v1";
export const VOICE_CONSENT_VERSION = "ai-voice-processing-capstone-v2";
export const voiceStartCommandSchema = z
  .object({
    handoffToken: z.string().min(40).max(4096).optional(),
  })
  .strict();
export type VoiceStartCommand = z.infer<typeof voiceStartCommandSchema>;
export interface VoiceHandoffPair {
  user: string;
  assistant: string;
}
const ticketSchema = z
  .object({
    nonce: z.uuid(),
    owner: z.uuid(),
    conversation: z.uuid(),
    snapshot: z.string().length(43),
    issuedAt: z.number().int(),
    expiresAt: z.number().int(),
    policy: z.literal(TEXT_VOICE_HANDOFF_POLICY),
    consent: z.literal(VOICE_CONSENT_VERSION),
  })
  .strict();
interface PreparedHandoff {
  hash: string;
  pairs: VoiceHandoffPair[];
}
interface BoundHandoff extends PreparedHandoff {
  owner: string;
  attempt: string;
  expiresAt: number;
  taken: boolean;
}

export class VoiceContextHandoffs {
  private readonly bound = new Map<string, BoundHandoff>();
  private readonly issued = new Map<
    string,
    { owner: string; expiresAt: number; session?: string }
  >();
  constructor(
    private readonly enabled: boolean,
    private readonly secret: Buffer,
    private readonly conversations: {
      findConversation(owner: string, id: string): Promise<Conversation | null>;
    },
    private readonly crypto: Pick<ChatCrypto, "decrypt">,
    private readonly clock: () => Date = () => new Date(),
  ) {}
  get available() {
    return this.enabled;
  }
  private digest(value: string) {
    return createHmac("sha256", this.secret).update(value).digest("base64url");
  }
  private requireEnabled() {
    if (!this.enabled) throw new VoiceAccessError("PROVIDER_UNAVAILABLE");
  }
  private async snapshot(owner: string, id: string) {
    const row = await this.conversations.findConversation(owner, id);
    if (row?.ownerAccountId !== owner || row.expiresAt <= this.clock())
      throw new VoiceAccessError("SESSION_NOT_FOUND");
    const snapshot = this.digest(
      JSON.stringify({ updatedAt: row.updatedAt, messages: row.messages }),
    );
    const pairs: VoiceHandoffPair[] = [];
    for (let i = row.messages.length - 1; i > 0 && pairs.length < 2; i -= 2) {
      const assistant = row.messages[i],
        user = row.messages[i - 1];
      if (
        !assistant ||
        !user ||
        assistant.role !== "ASSISTANT" ||
        user.role !== "USER" ||
        assistant.contextKinds.length ||
        user.contextKinds.length
      )
        break;
      const pair = {
        user: this.crypto.decrypt(owner, id, user),
        assistant: this.crypto.decrypt(owner, id, assistant),
      };
      if (Buffer.byteLength(JSON.stringify([pair, ...pairs]), "utf8") > 1200)
        break;
      pairs.unshift(pair);
    }
    if (!pairs.length) throw new VoiceAccessError("INVALID_REQUEST");
    return { snapshot, pairs };
  }
  async prepare(owner: string, conversation: string) {
    this.requireEnabled();
    const { snapshot, pairs } = await this.snapshot(owner, conversation);
    const issuedAt = this.clock().getTime(),
      expiresAt = issuedAt + 120000;
    const payload = Buffer.from(
      JSON.stringify({
        nonce: randomUUID(),
        owner,
        conversation,
        snapshot,
        issuedAt,
        expiresAt,
        policy: TEXT_VOICE_HANDOFF_POLICY,
        consent: VOICE_CONSENT_VERSION,
      }),
    ).toString("base64url");
    for (const [hash, value] of this.issued)
      if (value.expiresAt <= issuedAt) this.issued.delete(hash);
    if (
      this.issued.size >= 32 ||
      [...this.issued.values()].filter((value) => value.owner === owner)
        .length >= 4
    )
      throw new VoiceAccessError("RATE_LIMITED");
    const handoffToken = `${payload}.${this.digest(`ticket:${payload}`)}`;
    this.issued.set(this.reference(owner, handoffToken), { owner, expiresAt });
    return {
      handoffToken,
      expiresAt: new Date(expiresAt).toISOString(),
      selectedPairs: pairs.length,
      policyVersion: TEXT_VOICE_HANDOFF_POLICY,
    };
  }
  private ticket(owner: string, token: string, allowExpired: boolean) {
    this.requireEnabled();
    const [payload, signature, extra] = token.split(".");
    if (!payload || !signature || extra || !/^[A-Za-z0-9_-]+$/.test(payload))
      throw new VoiceAccessError("INVALID_REQUEST");
    const expected = this.digest(`ticket:${payload}`);
    if (
      Buffer.byteLength(signature) !== Buffer.byteLength(expected) ||
      !timingSafeEqual(Buffer.from(signature), Buffer.from(expected))
    )
      throw new VoiceAccessError("INVALID_REQUEST");
    let value: z.infer<typeof ticketSchema>;
    try {
      value = ticketSchema.parse(
        JSON.parse(Buffer.from(payload, "base64url").toString("utf8")),
      );
    } catch {
      throw new VoiceAccessError("INVALID_REQUEST");
    }
    const now = this.clock().getTime();
    if (
      value.owner !== owner ||
      value.issuedAt > now ||
      (!allowExpired && value.expiresAt <= now) ||
      value.expiresAt - value.issuedAt !== 120000
    )
      throw new VoiceAccessError("INVALID_REQUEST");
    return value;
  }
  reference(owner: string, token: string) {
    this.ticket(owner, token, true);
    return this.digest(`voice:handoff:${token}`);
  }
  expiresAt(owner: string, token: string) {
    return new Date(this.ticket(owner, token, true).expiresAt);
  }
  async resolve(owner: string, token: string): Promise<PreparedHandoff> {
    const value = this.ticket(owner, token, false);
    const hash = this.reference(owner, token),
      issued = this.issued.get(hash);
    if (!issued || issued.session) throw new VoiceAccessError("STALE_ATTEMPT");
    const snapshot = await this.snapshot(owner, value.conversation);
    if (snapshot.snapshot !== value.snapshot)
      throw new VoiceAccessError("STALE_ATTEMPT");
    return { hash, pairs: snapshot.pairs };
  }
  async resolveStart(owner: string, token: string, session?: VoiceSession) {
    const hash = this.reference(owner, token);
    if (session) {
      if (session.ownerId !== owner || session.contextHandoffHash !== hash)
        return undefined;
      const bound = this.bound.get(session.voiceSessionId);
      if (bound) {
        if (
          bound.owner !== owner ||
          bound.attempt !== session.attemptId ||
          bound.hash !== hash
        )
          throw new VoiceAccessError("STALE_ATTEMPT");
        return undefined;
      }
    }
    return this.resolve(owner, token);
  }
  async verifySnapshot(owner: string, token: string) {
    const value = this.ticket(owner, token, true);
    if (
      (await this.snapshot(owner, value.conversation)).snapshot !==
      value.snapshot
    )
      throw new VoiceAccessError("STALE_ATTEMPT");
  }
  bind(session: VoiceSession, prepared?: PreparedHandoff) {
    if (!session.contextHandoffHash) return;
    this.requireEnabled();
    const prior = this.bound.get(session.voiceSessionId);
    if (prior) {
      if (
        prior.owner !== session.ownerId ||
        prior.attempt !== session.attemptId ||
        prior.hash !== session.contextHandoffHash
      )
        throw new VoiceAccessError("STALE_ATTEMPT");
      return;
    }
    if (
      prepared?.hash !== session.contextHandoffHash ||
      !session.attemptId ||
      this.bound.size >= 2
    )
      throw new VoiceAccessError("STALE_ATTEMPT");
    const issued = this.issued.get(prepared.hash);
    if (!issued || issued.session) throw new VoiceAccessError("STALE_ATTEMPT");
    issued.session = session.voiceSessionId;
    this.bound.set(session.voiceSessionId, {
      ...prepared,
      owner: session.ownerId,
      attempt: session.attemptId,
      expiresAt: session.hardStopAt.getTime(),
      taken: false,
    });
  }
  take(session: VoiceSession): VoiceHandoffPair[] {
    if (!session.contextHandoffHash) return [];
    const value = this.bound.get(session.voiceSessionId);
    if (
      value?.owner !== session.ownerId ||
      value.attempt !== session.attemptId ||
      value.hash !== session.contextHandoffHash ||
      value.expiresAt <= this.clock().getTime() ||
      value.taken
    )
      throw new VoiceAccessError("STALE_ATTEMPT");
    const pairs = value.pairs;
    value.pairs = [];
    value.taken = true;
    return pairs;
  }
  discard(id: string) {
    const value = this.bound.get(id);
    if (value) value.pairs.length = 0;
    this.bound.delete(id);
  }
  close() {
    for (const id of this.bound.keys()) this.discard(id);
    this.issued.clear();
  }
}
