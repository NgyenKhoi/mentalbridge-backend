import { createHmac, randomUUID } from "node:crypto";
import { z } from "zod";
import {
  selectCompanionProviderRoute,
  validatedAssistantMessage,
  type ChatProvider,
  type ProviderReply,
} from "../companion-chat/companion-chat.js";
import type { ServiceConfiguration } from "../configuration/configuration.js";
import { VoiceAccessError } from "./voice-access.js";
import { VoiceLedgerError } from "./voice-ledger.js";
import { VoiceMediaError } from "./voice-media-provider.js";
import type { VoiceSessionsService } from "./voice-sessions.js";
import type { MongoVoiceTurnStore } from "./voice-turn-store.js";
import { selectVoiceProviderRoute } from "./voice-provider.js";
import {
  VoiceProviderFailure,
  type VoiceFailureCode,
  type VoiceProviderPhase,
} from "../llm-providers/voice-diagnostics.js";

export const voiceTurnSchema = z
  .object({
    attemptId: z.uuid(),
    leaseId: z.uuid(),
    turnId: z.uuid(),
    sequence: z.number().int().min(1).max(2147483647),
    language: z.literal("vi-VN"),
    isFinal: z.literal(true),
    finalTranscript: z.string().trim().min(1).max(2000),
  })
  .strict();
export type VoiceTurn = z.infer<typeof voiceTurnSchema>;
export class VoiceTurnError extends Error {
  constructor(readonly code: "INVALID_FINAL_TRANSCRIPT" | "PROVIDER_TIMEOUT") {
    super(code);
  }
}
export interface VoiceReply {
  voiceSessionId: string;
  turnId: string;
  replyId: string;
  language: "vi-VN";
  spokenText: string;
  expiresAt: string;
  policyVersion: "companion-voice-policy-v1";
}
interface Pair {
  user: string;
  assistant: string;
}
interface Context {
  id: string;
  attemptId: string;
  leaseId: string;
  routeHash: string;
  sequence: number;
  busy: boolean;
  pairs: Pair[];
  controller: AbortController;
  latest: { keyHash: string; fingerprint: string; response: VoiceReply } | null;
}
export interface VoiceTurnMetric {
  outcome: "GENERATED" | "FAILED" | "PHASE" | "PROVIDER_USAGE";
  attemptId?: string;
  turnId?: string;
  correlationId?: string;
  phase?:
    | VoiceProviderPhase
    | "turn_authorized"
    | "output_validated"
    | "publication_authorized"
    | "turn_state_rechecked"
    | "reply_registered";
  code?: VoiceFailureCode;
  countedInputTokens?: number;
  sequence?: number;
  elapsedMs?: number;
  inputTokens?: number;
  outputTokens?: number;
  usageSource?: "PROVIDER" | "ESTIMATED";
  costMicroUsd?: number;
}
type Sessions = Pick<
  VoiceSessionsService,
  | "authorizeTurn"
  | "turnState"
  | "contextAlive"
  | "registerReply"
  | "recordSpeechActivity"
  | "failTurn"
  | "onTermination"
>;
const injection =
  /(?:ignore|disregard).{0,35}(?:instructions?|system|developer)|(?:bỏ qua|phớt lờ).{0,40}(?:chỉ dẫn|hướng dẫn|system|developer)|system prompt|developer message|chain.of.thought|hidden reasoning|<\/?(?:system|assistant|developer)>/iu;
const fits = (pairs: Pair[], input = "") =>
  Buffer.byteLength(JSON.stringify(pairs), "utf8") +
    Buffer.byteLength(input, "utf8") +
    128 <=
  2000;
const hasControlCharacters = (text: string) => {
  for (let i = 0; i < text.length; i++) {
    const code = text.charCodeAt(i);
    if ((code < 32 && code !== 9 && code !== 10) || code === 127) return true;
  }
  return false;
};
const history = (pairs: Pair[], input = ""): Pair[] => {
  const selected = pairs.slice(-4);
  while (selected.length && !fits(selected, input)) selected.shift();
  if (!fits(selected, input)) throw new VoiceLedgerError("INVALID_REQUEST");
  return selected;
};

export class VoiceTurnService {
  private readonly contexts = new Map<string, Context>();
  private readonly unsubscribe: () => void;
  constructor(
    private readonly sessions: Sessions,
    private readonly store: Pick<MongoVoiceTurnStore, "claim">,
    private readonly provider: ChatProvider,
    private readonly configuration: ServiceConfiguration,
    private readonly clock: () => Date = () => new Date(),
    private readonly telemetry: (metric: VoiceTurnMetric) => void = () =>
      undefined,
  ) {
    this.unsubscribe = sessions.onTermination((id) => {
      this.discard(id);
    });
  }

  private digest(value: string) {
    return createHmac("sha256", this.configuration.JOURNAL_IDEMPOTENCY_HMAC_KEY)
      .update(value)
      .digest("base64url");
  }
  private discard(id: string) {
    const context = this.contexts.get(id);
    if (!context) return;
    this.contexts.delete(id);
    context.controller.abort();
    context.pairs.length = 0;
    context.latest = null;
  }
  close() {
    this.unsubscribe();
    for (const id of this.contexts.keys()) this.discard(id);
  }
  async reconcile() {
    await Promise.all(
      [...this.contexts].map(async ([id, context]) => {
        try {
          await this.sessions.contextAlive(
            id,
            context.attemptId,
            context.leaseId,
          );
        } catch {
          this.discard(id);
        }
      }),
    );
  }

  async generate(
    id: string,
    key: string,
    body: unknown,
    correlationId: string,
    signal?: AbortSignal,
  ): Promise<VoiceReply> {
    const parsed = voiceTurnSchema.safeParse(body);
    if (
      !z.uuid().safeParse(id).success ||
      !z.uuid().safeParse(key).success ||
      !parsed.success
    )
      throw new VoiceLedgerError("INVALID_REQUEST");
    const input = parsed.data;
    let phase: NonNullable<VoiceTurnMetric["phase"]> = "turn_authorized";
    let diagnosticSequence = 0;
    const diagnosticStart = performance.now();
    const report = (value: VoiceTurnMetric) => {
      this.telemetry({
        ...value,
        attemptId: input.attemptId,
        turnId: input.turnId,
        ...(z.uuid().safeParse(correlationId).success ? { correlationId } : {}),
        sequence: ++diagnosticSequence,
        elapsedMs: Math.max(0, Math.floor(performance.now() - diagnosticStart)),
      });
    };
    const stage = (value: NonNullable<VoiceTurnMetric["phase"]>) => {
      phase = value;
      report({ outcome: "PHASE", phase });
    };
    if (
      !/\p{L}/u.test(input.finalTranscript) ||
      hasControlCharacters(input.finalTranscript) ||
      injection.test(input.finalTranscript)
    )
      throw new VoiceTurnError("INVALID_FINAL_TRANSCRIPT");
    if (signal?.aborted) throw new VoiceLedgerError("SESSION_EXPIRED");
    const { session, entitlement } = await this.sessions.authorizeTurn(
      id,
      input.attemptId,
      input.leaseId,
      correlationId,
    );
    stage("turn_authorized");
    if (this.configuration.VOICE_PILOT && input.sequence > 4) {
      await this.sessions.failTurn(id);
      this.discard(id);
      throw new VoiceLedgerError("QUOTA_EXHAUSTED");
    }
    let route: ReturnType<typeof selectCompanionProviderRoute>;
    try {
      route = this.route(entitlement.packageCode);
    } catch (error) {
      this.discard(id);
      await this.sessions.failTurn(id);
      throw error;
    }
    const routeHash = this.digest(JSON.stringify(route));
    const keyHash = this.digest(`voice:turn:${session.ownerId}:${key}`);
    const fingerprint = this.digest(JSON.stringify(input));
    let context = this.contexts.get(id);
    if (
      context &&
      (context.attemptId !== input.attemptId ||
        context.leaseId !== input.leaseId)
    )
      throw new VoiceLedgerError("STALE_ATTEMPT");
    if (context && context.routeHash !== routeHash) {
      this.discard(id);
      await this.sessions.failTurn(id);
      throw new VoiceMediaError();
    }
    if (context?.busy) throw new VoiceLedgerError("INVALID_SESSION_STATE");
    if (context?.latest?.keyHash === keyHash) {
      if (context.latest.fingerprint !== fingerprint)
        throw new VoiceLedgerError("IDEMPOTENCY_CONFLICT");
      if (
        Date.parse(context.latest.response.expiresAt) <= this.clock().getTime()
      )
        throw new VoiceLedgerError("SESSION_EXPIRED");
      return context.latest.response;
    }
    if (!context) {
      if (this.contexts.size >= 2) await this.reconcile();
      if (this.contexts.size >= 2)
        throw new VoiceLedgerError("GLOBAL_CAPACITY_FULL");
      context = {
        id: randomUUID(),
        attemptId: input.attemptId,
        leaseId: input.leaseId,
        routeHash,
        sequence: 0,
        busy: false,
        pairs: [],
        controller: new AbortController(),
        latest: null,
      };
      this.contexts.set(id, context);
    }
    if (input.sequence !== context.sequence + 1)
      throw new VoiceLedgerError("STALE_ATTEMPT");
    const recent = history(context.pairs, input.finalTranscript);
    context.busy = true;
    let claimed = false;
    let timer: ReturnType<typeof setInterval> | undefined;
    let timeout: AbortSignal | undefined;
    const usage = { recorded: false };
    const abort = () => {
      this.discard(id);
    };
    const active = context;
    try {
      claimed = await this.store.claim({
        _id: keyHash,
        operation: "turn",
        voiceSessionId: id,
        attemptId: input.attemptId,
        turnId: input.turnId,
        sequence: input.sequence,
        contextId: context.id,
        fingerprint,
        routeHash,
        createdAt: this.clock(),
        expiresAt: session.expiresAt,
      });
      if (!claimed) throw new VoiceLedgerError("STALE_ATTEMPT");
      context.sequence = input.sequence;
      await this.sessions.recordSpeechActivity(
        id,
        input.attemptId,
        input.leaseId,
      );
      signal?.addEventListener("abort", abort, { once: true });
      if (signal?.aborted) abort();
      let checking = false;
      timer = setInterval(() => {
        if (checking) return;
        checking = true;
        void this.sessions
          .turnState(id, input.attemptId, input.leaseId)
          .catch(abort)
          .finally(() => {
            checking = false;
          });
      }, 1000);
      timer.unref();
      timeout = AbortSignal.timeout(this.configuration.PROVIDER_TIMEOUT_MS);
      const requestSignal = AbortSignal.any([
        context.controller.signal,
        timeout,
      ]);
      if (this.isAborted(requestSignal))
        throw new VoiceLedgerError("SESSION_EXPIRED");
      let rejectAbort: () => void = () => undefined;
      const cancelled = new Promise<never>((_resolve, reject) => {
        rejectAbort = () => {
          reject(new VoiceLedgerError("SESSION_EXPIRED"));
        };
        requestSignal.addEventListener("abort", rejectAbort, { once: true });
        if (requestSignal.aborted) rejectAbort();
      });
      let reply: ProviderReply;
      try {
        reply = await Promise.race([
          this.provider.reply(
            input.finalTranscript,
            { kinds: [], prompt: JSON.stringify(recent) },
            route,
            {
              voice: true,
              signal: requestSignal,
              voiceObserver: (observation) => {
                phase = observation.phase;
                if (observation.phase === "provider_usage") {
                  if (
                    !usage.recorded &&
                    observation.inputTokens !== undefined &&
                    observation.outputTokens !== undefined
                  ) {
                    usage.recorded = true;
                    this.usage(
                      {
                        message: "",
                        inputTokens: observation.inputTokens,
                        outputTokens: observation.outputTokens,
                        usageSource: "PROVIDER",
                      },
                      entitlement.packageCode,
                      (metric) => {
                        report({ ...metric, outcome: "PROVIDER_USAGE", phase });
                      },
                    );
                  }
                } else report({ outcome: "PHASE", ...observation });
              },
            },
          ),
          cancelled,
        ]);
      } finally {
        requestSignal.removeEventListener("abort", rejectAbort);
      }
      if (!usage.recorded) {
        this.usage(reply, entitlement.packageCode, (metric) => {
          report({ ...metric, outcome: "PROVIDER_USAGE", phase });
        });
        usage.recorded = true;
      }
      const spokenText = validatedAssistantMessage(reply.message);
      if (/[<>{}*`#]/u.test(spokenText)) throw new VoiceMediaError();
      stage("output_validated");
      const publication = await this.sessions.authorizeTurn(
        id,
        input.attemptId,
        input.leaseId,
        correlationId,
      );
      stage("publication_authorized");
      if (
        requestSignal.aborted ||
        this.contexts.get(id) !== active ||
        this.digest(
          JSON.stringify(this.route(publication.entitlement.packageCode)),
        ) !== routeHash
      )
        throw new VoiceMediaError();
      const replyId = randomUUID();
      await this.sessions.registerReply(
        id,
        input.attemptId,
        input.turnId,
        replyId,
        correlationId,
      );
      stage("reply_registered");
      const latest = await this.sessions.turnState(
        id,
        input.attemptId,
        input.leaseId,
      );
      stage("turn_state_rechecked");
      if (this.isAborted(requestSignal) || this.contexts.get(id) !== active)
        throw new VoiceLedgerError("SESSION_EXPIRED");
      const expiresAt = new Date(
        Math.min(
          latest.hardStopAt.getTime(),
          latest.authorityExpiresAt.getTime(),
          latest.leaseExpiresAt?.getTime() ?? 0,
        ),
      );
      if (expiresAt <= this.clock())
        throw new VoiceLedgerError("SESSION_EXPIRED");
      const result: VoiceReply = {
        voiceSessionId: id,
        turnId: input.turnId,
        replyId,
        language: "vi-VN",
        spokenText,
        expiresAt: expiresAt.toISOString(),
        policyVersion: "companion-voice-policy-v1",
      };
      context.pairs = history([
        ...recent,
        { user: input.finalTranscript, assistant: spokenText },
      ]);
      context.latest = { keyHash, fingerprint, response: result };
      report({ outcome: "GENERATED", phase });
      return result;
    } catch (error) {
      const timedOut = timeout !== undefined && this.isAborted(timeout);
      const authorityAborted = active.controller.signal.aborted;
      this.discard(id);
      if (claimed) {
        report({
          outcome: "FAILED",
          phase,
          code: timedOut
            ? "TIMEOUT"
            : signal?.aborted
              ? "ABORTED"
              : error instanceof VoiceProviderFailure
                ? error.code
                : error instanceof VoiceLedgerError ||
                    error instanceof VoiceAccessError ||
                    authorityAborted
                  ? "AUTHORITY_OR_STATE"
                  : "INVALID_RESPONSE",
        });
        try {
          await this.sessions.failTurn(id);
        } catch {
          report({ outcome: "FAILED", phase, code: "INTERNAL" });
        }
      }
      if (timedOut) throw new VoiceTurnError("PROVIDER_TIMEOUT");
      if (
        error instanceof VoiceLedgerError ||
        error instanceof VoiceAccessError ||
        error instanceof VoiceTurnError
      )
        throw error;
      throw new VoiceMediaError();
    } finally {
      active.busy = false;
      if (timer) clearInterval(timer);
      signal?.removeEventListener("abort", abort);
    }
  }

  private isAborted(signal: AbortSignal) {
    return signal.aborted;
  }

  private route(plan: "FREE" | "PLUS" | "PREMIUM") {
    try {
      const route = selectVoiceProviderRoute(this.configuration, plan);
      const fake =
        route.provider === "DETERMINISTIC_FAKE" &&
        this.configuration.NODE_ENV !== "production" &&
        !this.configuration.VOICE_MEDIA?.enabled;
      if (
        !fake &&
        (route.provider !== "BEDROCK" ||
          !this.configuration.BEDROCK_API_KEY ||
          !/^(?:(?:global|apac|us|eu)\.)?anthropic\.claude-/u.test(route.model))
      )
        throw new VoiceMediaError();
      return route;
    } catch {
      throw new VoiceMediaError();
    }
  }
  private usage(
    reply: ProviderReply,
    plan: "FREE" | "PLUS" | "PREMIUM",
    report: (metric: VoiceTurnMetric) => void,
  ) {
    if (
      ![reply.inputTokens, reply.outputTokens].every(
        (n) => Number.isSafeInteger(n) && n >= 0,
      )
    )
      return;
    const rate =
      this.configuration.VOICE_BEDROCK_ROUTE ??
      (plan === "PREMIUM"
        ? this.configuration.PREMIUM_ROUTE
        : this.configuration.FREE_PLUS_ROUTE);
    report({
      outcome: "GENERATED",
      inputTokens: reply.inputTokens,
      outputTokens: reply.outputTokens,
      usageSource: reply.usageSource ?? "ESTIMATED",
      ...(rate
        ? {
            costMicroUsd:
              (reply.inputTokens * rate.inputCostMicroUsdPerMillionTokens +
                reply.outputTokens * rate.outputCostMicroUsdPerMillionTokens) /
              1_000_000,
          }
        : {}),
    });
  }
}
