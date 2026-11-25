import { createHmac, randomUUID } from "node:crypto";
import { z } from "zod";
import {
  validatedAssistantMessage,
  type ChatProvider,
  type ProviderReply,
  type ProviderSpeechUnit,
} from "../companion-chat/companion-chat.js";
import type { ServiceConfiguration } from "../configuration/configuration.js";
import { VoiceAccessError } from "./voice-access.js";
import { VoiceLedgerError } from "./voice-ledger.js";
import { VoiceMediaError } from "./voice-media-provider.js";
import type { VoiceSessionsService } from "./voice-sessions.js";
import type { MongoVoiceTurnStore } from "./voice-turn-store.js";
import { measurementPolicy } from "./voice-measurement-policy.js";
import { VOICE_REPLY_MAX_CHARACTERS } from "../companion-chat/companion-voice-guidance.js";
import { selectVoiceProviderRoute } from "./voice-provider.js";
import type { VoiceContextHandoffs } from "./voice-context-handoff.js";
import {
  VoiceProviderFailure,
  type VoiceFailureCode,
  type VoiceProviderPhase,
  type VoiceStopReason,
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
  expression?: "neutral" | "comfort";
  expiresAt: string;
  policyVersion: "companion-voice-policy-v1";
}
const expressionSchema = z.enum(["neutral", "comfort"]);
export const voiceExpressionCapability = "expression";
export const voiceSpeechUnitsCapability = "voice-speech-units-v1";
export interface AuthorizedSpeechUnit extends Omit<
  VoiceReply,
  "policyVersion"
> {
  type: "SPEECH_UNIT";
  unitSequence: number;
  reservedCharacters: typeof VOICE_REPLY_MAX_CHARACTERS;
  policyVersion: "companion-voice-speech-unit-v1";
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
  textPairs: Pair[];
  source?: { owner: string; conversation: string };
  spoken: Pair[];
  controller: AbortController;
  latest: { keyHash: string; fingerprint: string; response: VoiceReply } | null;
  cancelledTurnId?: string;
}
interface SpeechUnitJob {
  input: VoiceTurn;
  controller: AbortController;
  finished: Promise<void>;
  awaiting?: { sequence: number; replyId: string; release: () => void };
  acknowledged?: { sequence: number; replyId: string };
}
export interface VoiceTurnMetric {
  outcome: "GENERATED" | "FAILED" | "PHASE" | "PROVIDER_USAGE";
  attemptId?: string;
  turnId?: string;
  correlationId?: string;
  phase?:
    | VoiceProviderPhase
    | "turn_request_received"
    | "initial_authorization_started"
    | "turn_authorized"
    | "context_preparation_started"
    | "context_prepared"
    | "turn_claim_started"
    | "turn_claimed"
    | "speech_activity_recorded"
    | "pre_inference_authorization_started"
    | "pre_inference_authorized"
    | "provider_reply_requested"
    | "provider_reply_received"
    | "output_validated"
    | "publication_authorization_started"
    | "publication_authorized"
    | "reply_registration_started"
    | "turn_state_recheck_started"
    | "turn_state_rechecked"
    | "reply_registered";
  code?: VoiceFailureCode;
  stopReason?: VoiceStopReason;
  countedInputTokens?: number;
  sequence?: number;
  turnSequence?: number;
  unitSequence?: number;
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
> &
  Partial<
    Pick<
      VoiceSessionsService,
      "reserveMeasurementTurn" | "reserveMeasurementSpeech"
    >
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
const trimSelectedText = (
  selectedText: Pair[],
  currentCall: Pair[],
  input = "",
) => {
  while (
    selectedText.length &&
    (selectedText.length + currentCall.length > 4 ||
      Buffer.byteLength(JSON.stringify({ selectedText, currentCall }), "utf8") +
        Buffer.byteLength(input, "utf8") +
        128 >
        2000)
  )
    selectedText.shift();
};

export class VoiceTurnService {
  private readonly contexts = new Map<string, Context>();
  private readonly speechJobs = new Map<string, SpeechUnitJob>();
  private readonly writeBacks = new Map<string, Promise<void>>();
  private readonly unsubscribe: () => void;
  constructor(
    private readonly sessions: Sessions,
    private readonly store: Pick<MongoVoiceTurnStore, "claim">,
    private readonly provider: ChatProvider,
    private readonly configuration: ServiceConfiguration,
    private readonly clock: () => Date = () => new Date(),
    private readonly telemetry: (metric: VoiceTurnMetric) => void = () =>
      undefined,
    private readonly handoffs?: Pick<
      VoiceContextHandoffs,
      "take" | "writeBack"
    >,
  ) {
    this.unsubscribe = sessions.onTermination((id) => {
      this.discard(id);
      return this.writeBacks.get(id);
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
    context.textPairs.length = 0;
    context.latest = null;
    const spoken = context.spoken.splice(0);
    if (!context.source || !spoken.length || !this.handoffs) return;
    const write = this.handoffs
      .writeBack(context.source.owner, context.source.conversation, id, spoken)
      .catch(() => {
        this.telemetry({ outcome: "FAILED", code: "INTERNAL" });
      })
      .finally(() => {
        this.writeBacks.delete(id);
      });
    this.writeBacks.set(id, write);
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
    onAuthorizedUnit?: (unit: AuthorizedSpeechUnit) => Promise<void>,
  ): Promise<VoiceReply> {
    const parsed = voiceTurnSchema.safeParse(body);
    if (
      !z.uuid().safeParse(id).success ||
      !z.uuid().safeParse(key).success ||
      !parsed.success
    )
      throw new VoiceLedgerError("INVALID_REQUEST");
    const input = parsed.data;
    if (Boolean(onAuthorizedUnit) !== this.speechUnitsEnabled())
      throw new VoiceLedgerError("INVALID_REQUEST");
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
        turnSequence: input.sequence,
        elapsedMs: Math.max(0, Math.floor(performance.now() - diagnosticStart)),
      });
    };
    const stage = (value: NonNullable<VoiceTurnMetric["phase"]>) => {
      phase = value;
      report({ outcome: "PHASE", phase });
    };
    stage("turn_request_received");
    if (
      !/\p{L}/u.test(input.finalTranscript) ||
      hasControlCharacters(input.finalTranscript) ||
      injection.test(input.finalTranscript)
    )
      throw new VoiceTurnError("INVALID_FINAL_TRANSCRIPT");
    if (signal?.aborted) throw new VoiceLedgerError("SESSION_EXPIRED");
    stage("initial_authorization_started");
    const { session, entitlement } = await this.sessions.authorizeTurn(
      id,
      input.attemptId,
      input.leaseId,
      correlationId,
      {
        purpose: "INITIAL",
        turnId: input.turnId,
        turnSequence: input.sequence,
      },
    );
    stage("turn_authorized");
    if (
      this.configuration.VOICE_PILOT &&
      input.sequence >
        (measurementPolicy(this.configuration.VOICE_PILOT)?.allocation.turns ??
          this.configuration.VOICE_PILOT.maxTurns ??
          4)
    ) {
      await this.sessions.failTurn(id, "QUOTA_EXHAUSTED");
      this.discard(id);
      throw new VoiceLedgerError("QUOTA_EXHAUSTED");
    }
    stage("context_preparation_started");
    let route: ReturnType<typeof selectVoiceProviderRoute>;
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
      if (onAuthorizedUnit) throw new VoiceLedgerError("STALE_ATTEMPT");
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
      if (session.contextHandoffHash && !this.handoffs)
        throw new VoiceMediaError();
      const handoff = session.contextHandoffHash
        ? this.handoffs?.take(session)
        : undefined;
      context = {
        id: randomUUID(),
        attemptId: input.attemptId,
        leaseId: input.leaseId,
        routeHash,
        sequence: 0,
        busy: false,
        pairs: [],
        textPairs: handoff?.pairs ?? [],
        ...(handoff?.conversation
          ? {
              source: {
                owner: session.ownerId,
                conversation: handoff.conversation,
              },
            }
          : {}),
        spoken: [],
        controller: new AbortController(),
        latest: null,
      };
      this.contexts.set(id, context);
    }
    if (input.sequence !== context.sequence + 1)
      throw new VoiceLedgerError("STALE_ATTEMPT");
    const recent = history(context.pairs, input.finalTranscript);
    trimSelectedText(context.textPairs, recent, input.finalTranscript);
    const textHandoff = context.textPairs.length > 0;
    stage("context_prepared");
    context.busy = true;
    let claimed = false;
    let timer: ReturnType<typeof setInterval> | undefined;
    let timeout: AbortSignal | undefined;
    const turnController = new AbortController();
    let finishJob: (() => void) | undefined;
    let publications: Promise<void> = Promise.resolve();
    let publicationFailure: { error: unknown } | undefined;
    let generation: Promise<ProviderReply> | undefined;
    const generationState = { completed: false };
    const usage = { recorded: false };
    const abort = () => {
      if (onAuthorizedUnit) turnController.abort();
      else this.discard(id);
    };
    const active = context;
    if (onAuthorizedUnit)
      this.speechJobs.set(id, {
        input,
        controller: turnController,
        finished: new Promise<void>((resolve) => {
          finishJob = resolve;
        }),
      });
    try {
      stage("turn_claim_started");
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
      stage("turn_claimed");
      context.sequence = input.sequence;
      if (this.configuration.VOICE_PILOT?.measurement) {
        if (
          !this.sessions.reserveMeasurementTurn ||
          !this.sessions.reserveMeasurementSpeech
        )
          throw new VoiceLedgerError("QUOTA_EXHAUSTED");
        await this.sessions.reserveMeasurementTurn(
          id,
          input.turnId,
          input.sequence,
        );
      }
      await this.sessions.recordSpeechActivity(
        id,
        input.attemptId,
        input.leaseId,
      );
      stage("speech_activity_recorded");
      signal?.addEventListener("abort", abort, { once: true });
      if (signal?.aborted) abort();
      let checking = false;
      timer = setInterval(() => {
        if (checking) return;
        checking = true;
        void this.sessions
          .turnState(id, input.attemptId, input.leaseId)
          .catch(() => {
            this.discard(id);
          })
          .finally(() => {
            checking = false;
          });
      }, 1000);
      timer.unref();
      timeout = AbortSignal.timeout(this.configuration.PROVIDER_TIMEOUT_MS);
      const authoritySignal = AbortSignal.any([
        context.controller.signal,
        turnController.signal,
      ]);
      const providerSignal = AbortSignal.any([authoritySignal, timeout]);
      const requestSignal = onAuthorizedUnit ? authoritySignal : providerSignal;
      if (this.isAborted(requestSignal))
        throw new VoiceLedgerError("SESSION_EXPIRED");
      stage("pre_inference_authorization_started");
      const dispatch = await this.sessions.authorizeTurn(
        id,
        input.attemptId,
        input.leaseId,
        correlationId,
        {
          purpose: "PRE_INFERENCE",
          turnId: input.turnId,
          turnSequence: input.sequence,
        },
      );
      stage("pre_inference_authorized");
      if (
        this.digest(
          JSON.stringify(this.route(dispatch.entitlement.packageCode)),
        ) !== routeHash ||
        this.isAborted(requestSignal)
      )
        throw new VoiceMediaError();
      let rejectAbort: () => void = () => undefined;
      const cancelled = new Promise<never>((_resolve, reject) => {
        rejectAbort = () => {
          reject(new VoiceLedgerError("SESSION_EXPIRED"));
        };
        requestSignal.addEventListener("abort", rejectAbort, { once: true });
        if (requestSignal.aborted) rejectAbort();
      });
      let reply: ProviderReply;
      let streamedReply: VoiceReply | undefined;
      let unitSequence = 0;
      const queuedText: string[] = [];
      const queueUnit = onAuthorizedUnit
        ? (unit: ProviderSpeechUnit) => {
            let message: string;
            try {
              message = validatedAssistantMessage(unit.message, {
                rejectLinks: true,
                maxLength: VOICE_REPLY_MAX_CHARACTERS,
              });
              validatedAssistantMessage([...queuedText, message].join(" "), {
                rejectLinks: true,
                maxLength: VOICE_REPLY_MAX_CHARACTERS,
              });
            } catch {
              // A closed safety code, not a misleading envelope error.
              throw new VoiceProviderFailure("OUTPUT_SAFETY_REJECTED");
            }
            if (queuedText.length >= 2 || !/[.!?…]$/u.test(message))
              throw new VoiceProviderFailure("OUTPUT_SAFETY_REJECTED");
            queuedText.push(message);
            const selected: ProviderSpeechUnit = {
              message,
              expression:
                expressionSchema.safeParse(unit.expression).data ?? "neutral",
            };
            publications = publications.then(async () => {
              streamedReply = await this.publishSpeechUnit(
                id,
                input,
                active,
                routeHash,
                requestSignal,
                correlationId,
                selected,
                streamedReply,
                (value) => {
                  phase = value;
                  report({
                    outcome: "PHASE",
                    phase,
                    unitSequence: unitSequence + 1,
                  });
                },
              );
              const sequence = ++unitSequence;
              const acknowledged = this.waitForSpeechAcknowledgement(
                id,
                sequence,
                streamedReply.replyId,
                requestSignal,
              );
              void acknowledged.catch(() => undefined);
              await onAuthorizedUnit({
                ...streamedReply,
                type: "SPEECH_UNIT",
                unitSequence: sequence,
                reservedCharacters: VOICE_REPLY_MAX_CHARACTERS,
                policyVersion: "companion-voice-speech-unit-v1",
              });
              await acknowledged;
            });
            void publications.catch((error: unknown) => {
              if (!turnController.signal.aborted) {
                publicationFailure ??= { error };
                turnController.abort();
              }
            });
          }
        : undefined;
      try {
        stage("provider_reply_requested");
        generation = this.provider.reply(
          input.finalTranscript,
          {
            kinds: [],
            prompt: JSON.stringify(
              textHandoff
                ? { selectedText: context.textPairs, currentCall: recent }
                : recent,
            ),
          },
          route,
          {
            voice: true,
            textHandoff,
            ...(queueUnit ? { onSpeechUnit: queueUnit } : {}),
            signal: providerSignal,
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
        );
        reply = await Promise.race([generation, cancelled]);
        generationState.completed = true;
        stage("provider_reply_received");
        await publications;
        if (publicationFailure) throw publicationFailure.error;
      } catch (error) {
        if (publicationFailure) throw publicationFailure.error;
        throw error;
      } finally {
        requestSignal.removeEventListener("abort", rejectAbort);
      }
      if (!usage.recorded) {
        this.usage(reply, entitlement.packageCode, (metric) => {
          report({ ...metric, outcome: "PROVIDER_USAGE", phase });
        });
        usage.recorded = true;
      }
      if (onAuthorizedUnit) {
        if (!streamedReply || unitSequence < 1 || requestSignal.aborted)
          throw new VoiceLedgerError("SESSION_EXPIRED");
        const result = { ...streamedReply, spokenText: reply.message };
        const pair = { user: input.finalTranscript, assistant: reply.message };
        context.pairs = history([...recent, pair]);
        if (context.source) context.spoken.push(pair);
        trimSelectedText(context.textPairs, context.pairs);
        context.latest = { keyHash, fingerprint, response: result };
        report({ outcome: "GENERATED", phase });
        return result;
      }
      const spokenText = validatedAssistantMessage(reply.message, {
        maxLength: VOICE_REPLY_MAX_CHARACTERS,
      });
      if (/[<>{}*`#]/u.test(spokenText)) throw new VoiceMediaError();
      const expression = session.contextHandoffHash
        ? "neutral"
        : (expressionSchema.safeParse(reply.expression).data ?? "neutral");
      stage("output_validated");
      stage("publication_authorization_started");
      const publication = await this.sessions.authorizeTurn(
        id,
        input.attemptId,
        input.leaseId,
        correlationId,
        {
          purpose: "PUBLICATION",
          turnId: input.turnId,
          turnSequence: input.sequence,
        },
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
      if (this.configuration.VOICE_PILOT?.measurement) {
        if (!this.sessions.reserveMeasurementSpeech)
          throw new VoiceLedgerError("QUOTA_EXHAUSTED");
        await this.sessions.reserveMeasurementSpeech(
          id,
          input.turnId,
          replyId,
          spokenText.length,
        );
      }
      stage("reply_registration_started");
      await this.sessions.registerReply(
        id,
        input.attemptId,
        input.turnId,
        replyId,
        correlationId,
      );
      stage("reply_registered");
      stage("turn_state_recheck_started");
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
        expression,
        expiresAt: expiresAt.toISOString(),
        policyVersion: "companion-voice-policy-v1",
      };
      const pair = { user: input.finalTranscript, assistant: spokenText };
      context.pairs = history([...recent, pair]);
      if (context.source) context.spoken.push(pair);
      trimSelectedText(context.textPairs, context.pairs);
      context.latest = { keyHash, fingerprint, response: result };
      report({ outcome: "GENERATED", phase });
      return result;
    } catch (error) {
      const timedOut =
        timeout !== undefined &&
        this.isAborted(timeout) &&
        (!onAuthorizedUnit || !generationState.completed);
      const authorityAborted = active.controller.signal.aborted;
      if (
        onAuthorizedUnit &&
        turnController.signal.aborted &&
        !timedOut &&
        !authorityAborted &&
        !publicationFailure &&
        this.contexts.get(id) === active &&
        ((error instanceof VoiceProviderFailure && error.code === "ABORTED") ||
          (error instanceof VoiceLedgerError &&
            error.code === "SESSION_EXPIRED"))
      ) {
        await publications.catch(() => undefined);
        if (await this.generationStopped(generation)) {
          active.cancelledTurnId = input.turnId;
          active.latest = null;
          report({ outcome: "FAILED", phase, code: "ABORTED" });
          throw new VoiceLedgerError("STALE_ATTEMPT");
        }
      }
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
          await this.sessions.failTurn(
            id,
            error instanceof VoiceLedgerError &&
              error.code === "QUOTA_EXHAUSTED"
              ? "QUOTA_EXHAUSTED"
              : "PROVIDER_FAILURE",
          );
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
      if (onAuthorizedUnit) {
        turnController.abort();
        await publications.catch(() => undefined);
        this.speechJobs.delete(id);
      }
      active.busy = false;
      if (timer) clearInterval(timer);
      signal?.removeEventListener("abort", abort);
      finishJob?.();
    }
  }

  private isAborted(signal: AbortSignal) {
    return signal.aborted;
  }
  // Speech units need the approved Bedrock ConverseStream route; the local fake route keeps plain turns.
  speechUnitsEnabled(): boolean {
    return Boolean(
      this.configuration.VOICE_BEDROCK_ROUTE?.approvedVersion &&
      this.configuration.BEDROCK_API_KEY,
    );
  }
  async cancelSpeechTurn(
    id: string,
    turnId: string,
    attemptId: string,
    leaseId: string,
  ): Promise<void> {
    if (!this.speechUnitsEnabled())
      throw new VoiceLedgerError("INVALID_REQUEST");
    await this.sessions.turnState(id, attemptId, leaseId);
    const job = this.speechJobs.get(id);
    if (!job) {
      const context = this.contexts.get(id);
      if (
        context?.cancelledTurnId === turnId ||
        context?.latest?.response.turnId === turnId
      )
        return;
      throw new VoiceLedgerError("STALE_ATTEMPT");
    }
    if (
      job.input.turnId !== turnId ||
      job.input.attemptId !== attemptId ||
      job.input.leaseId !== leaseId
    )
      throw new VoiceLedgerError("STALE_ATTEMPT");
    job.controller.abort();
    await job.finished;
    if (this.contexts.get(id)?.cancelledTurnId !== turnId)
      throw new VoiceLedgerError("STALE_ATTEMPT");
  }
  private async generationStopped(
    work: Promise<ProviderReply> | undefined,
  ): Promise<boolean> {
    if (!work) return true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    try {
      return await Promise.race([
        work.then(
          () => true,
          () => true,
        ),
        new Promise<boolean>((resolve) => {
          timer = setTimeout(() => {
            resolve(false);
          }, 1000);
        }),
      ]);
    } finally {
      if (timer) clearTimeout(timer);
    }
  }
  async acknowledgeSpeechUnit(
    id: string,
    turnId: string,
    attemptId: string,
    leaseId: string,
    sequence: number,
    replyId: string,
  ): Promise<void> {
    await this.sessions.turnState(id, attemptId, leaseId);
    const job = this.speechJobs.get(id);
    if (
      job?.input.turnId !== turnId ||
      job.input.attemptId !== attemptId ||
      job.input.leaseId !== leaseId ||
      job.controller.signal.aborted
    )
      throw new VoiceLedgerError("STALE_ATTEMPT");
    if (
      job.acknowledged?.sequence === sequence &&
      job.acknowledged.replyId === replyId
    )
      return;
    const unit = job.awaiting;
    if (unit?.sequence !== sequence || unit.replyId !== replyId)
      throw new VoiceLedgerError("STALE_ATTEMPT");
    job.acknowledged = { sequence, replyId };
    delete job.awaiting;
    unit.release();
  }
  private waitForSpeechAcknowledgement(
    id: string,
    sequence: number,
    replyId: string,
    signal: AbortSignal,
  ): Promise<void> {
    const job = this.speechJobs.get(id);
    if (!job || job.awaiting || signal.aborted)
      throw new VoiceLedgerError("SESSION_EXPIRED");
    return new Promise<void>((resolve, reject) => {
      const stop = () => {
        reject(new VoiceLedgerError("SESSION_EXPIRED"));
      };
      const release = () => {
        signal.removeEventListener("abort", stop);
        resolve();
      };
      job.awaiting = { sequence, replyId, release };
      signal.addEventListener("abort", stop, { once: true });
      if (signal.aborted) stop();
    });
  }
  private async publishSpeechUnit(
    id: string,
    input: VoiceTurn,
    context: Context,
    routeHash: string,
    signal: AbortSignal,
    correlationId: string,
    unit: ProviderSpeechUnit,
    previous?: VoiceReply,
    stage: (phase: NonNullable<VoiceTurnMetric["phase"]>) => void = () =>
      undefined,
  ): Promise<VoiceReply> {
    if (this.isAborted(signal) || this.contexts.get(id) !== context)
      throw new VoiceLedgerError("SESSION_EXPIRED");
    const spokenText = validatedAssistantMessage(unit.message, {
      rejectLinks: true,
      maxLength: VOICE_REPLY_MAX_CHARACTERS,
    });
    if (/[<>{}*`#]/u.test(spokenText)) throw new VoiceMediaError();
    stage("output_validated");
    stage("publication_authorization_started");
    const publication = await this.sessions.authorizeTurn(
      id,
      input.attemptId,
      input.leaseId,
      correlationId,
      {
        purpose: "PUBLICATION",
        turnId: input.turnId,
        turnSequence: input.sequence,
      },
    );
    stage("publication_authorized");
    if (
      this.isAborted(signal) ||
      this.contexts.get(id) !== context ||
      this.digest(
        JSON.stringify(this.route(publication.entitlement.packageCode)),
      ) !== routeHash
    )
      throw new VoiceMediaError();
    const replyId = previous?.replyId ?? randomUUID();
    if (!previous) {
      if (this.configuration.VOICE_PILOT?.measurement) {
        if (!this.sessions.reserveMeasurementSpeech)
          throw new VoiceLedgerError("QUOTA_EXHAUSTED");
        await this.sessions.reserveMeasurementSpeech(
          id,
          input.turnId,
          replyId,
          VOICE_REPLY_MAX_CHARACTERS,
        );
      }
      if (this.isAborted(signal)) throw new VoiceLedgerError("SESSION_EXPIRED");
      stage("reply_registration_started");
      await this.sessions.registerReply(
        id,
        input.attemptId,
        input.turnId,
        replyId,
        correlationId,
      );
      stage("reply_registered");
    }
    stage("turn_state_recheck_started");
    const latest = await this.sessions.turnState(
      id,
      input.attemptId,
      input.leaseId,
    );
    stage("turn_state_rechecked");
    const expiresAt = new Date(
      Math.min(
        latest.hardStopAt.getTime(),
        latest.authorityExpiresAt.getTime(),
        latest.leaseExpiresAt?.getTime() ?? 0,
      ),
    );
    if (
      this.isAborted(signal) ||
      this.contexts.get(id) !== context ||
      expiresAt <= this.clock()
    )
      throw new VoiceLedgerError("SESSION_EXPIRED");
    return {
      voiceSessionId: id,
      turnId: input.turnId,
      replyId,
      language: "vi-VN",
      spokenText,
      expression: context.textPairs.length > 0 ? "neutral" : unit.expression,
      expiresAt: expiresAt.toISOString(),
      policyVersion: "companion-voice-policy-v1",
    };
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
