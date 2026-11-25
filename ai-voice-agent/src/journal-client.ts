import { randomUUID } from "node:crypto";
import { z } from "zod";
import { AgentFailure, type AgentConfiguration } from "./configuration.js";
import {
  controlSchema,
  leaseSchema,
  replySchema,
  type Dispatch,
  type Lease,
  type Signal,
  type Turn,
  type Reply,
} from "./contract.js";
import {
  SpeechEventArray,
  speechUnitCapability,
  speechUnitMaxCharacters,
} from "./speech-units.js";
export class JournalClient {
  constructor(
    private readonly configuration: AgentConfiguration,
    private readonly transport: typeof fetch = fetch,
    private readonly clock: () => number = Date.now,
  ) {}
  private async post<T>(
    id: string,
    path: string,
    body: unknown,
    schema: z.ZodType<T>,
    signal?: AbortSignal,
    key?: string,
    timeoutMs?: number,
  ): Promise<T> {
    if (!this.configuration.journalToken)
      throw new AgentFailure("JOURNAL_UNAVAILABLE");
    const timeout = AbortSignal.timeout(
      timeoutMs ?? (path === "turns" ? 30000 : 2000),
    );
    let received = false;
    try {
      const response = await this.transport(
        new URL(
          `/internal/v1/ai-companion/voice-sessions/${id}/${path}`,
          this.configuration.journalUrl,
        ),
        {
          method: "POST",
          redirect: "error",
          cache: "no-store",
          headers: {
            "Content-Type": "application/json",
            "X-Correlation-Id": randomUUID(),
            "X-MentalBridge-Voice-Worker-Token":
              this.configuration.journalToken,
            ...(key ? { "Idempotency-Key": key } : {}),
            // MB-846d: speech units are always on, so every call advertises them.
            "X-MentalBridge-Voice-Capabilities": speechUnitCapability,
          },
          body: JSON.stringify(body),
          signal: AbortSignal.any([timeout, ...(signal ? [signal] : [])]),
        },
      );
      received = true;
      if (!response.ok)
        throw new AgentFailure(
          "JOURNAL_UNAVAILABLE",
          response.status >= 500 ? "HTTP_5XX" : "HTTP_4XX",
        );
      if (
        response.headers.get("cache-control") !== "no-store" ||
        !response.headers.get("content-type")?.startsWith("application/json")
      )
        throw new AgentFailure("JOURNAL_UNAVAILABLE");
      const reader = response.body?.getReader();
      if (!reader) throw new AgentFailure("INVALID_RESPONSE");
      const chunks: Uint8Array[] = [];
      let length = 0;
      let completed = false;
      try {
        while (!completed) {
          const { done, value } = await reader.read();
          completed = done;
          if (done) break;
          length += value.length;
          if (length > 16384) throw new AgentFailure("INVALID_RESPONSE");
          chunks.push(value);
        }
      } finally {
        await reader.cancel();
        reader.releaseLock();
      }
      const value = schema.parse(
        JSON.parse(Buffer.concat(chunks).toString("utf8")),
      );
      if (signal?.aborted || timeout.aborted)
        throw new AgentFailure("JOURNAL_UNAVAILABLE");
      return value;
    } catch (error) {
      throw new AgentFailure(
        "JOURNAL_UNAVAILABLE",
        signal?.aborted
          ? "ABORTED"
          : timeout.aborted
            ? "TIMEOUT"
            : error instanceof AgentFailure && error.failure
              ? error.failure
              : received
                ? "INVALID_RESPONSE"
                : "TRANSPORT",
      );
    }
  }
  async claim(dispatch: Dispatch, signal?: AbortSignal): Promise<Lease> {
    const value = await this.post(
      dispatch.voiceSessionId,
      "agent-claim",
      {
        attemptId: dispatch.attemptId,
        dispatchId: dispatch.dispatchId,
        ...(this.configuration.measurementPolicyHash
          ? { measurementHash: this.configuration.measurementPolicyHash }
          : {}),
      },
      leaseSchema,
      signal,
    );
    if (
      value.voiceSessionId !== dispatch.voiceSessionId ||
      value.attemptId !== dispatch.attemptId ||
      Date.parse(value.validUntil) <= this.clock() ||
      Date.parse(value.validUntil) > this.clock() + 10500 ||
      Date.parse(value.validUntil) > Date.parse(value.hardStopAt) ||
      Date.parse(value.hardStopAt) > this.clock() + 600000
    )
      throw new AgentFailure("INVALID_RESPONSE");
    const measurement = value.measurement;
    if (this.configuration.measurementPolicyHash || measurement) {
      if (
        !measurement ||
        !this.configuration.pilot ||
        measurement.configHash !== this.configuration.measurementPolicyHash ||
        measurement.ttsCharacters !==
          this.configuration.pilotTtsMaxCharacters ||
        measurement.voiceId !== this.configuration.voiceId
      )
        throw new AgentFailure("INVALID_RESPONSE");
    }
    return value;
  }
  async control(dispatch: Dispatch, signal: Signal, abort?: AbortSignal) {
    const remainingMs = Date.parse(signal.expiresAt) - this.clock();
    if (!Number.isFinite(remainingMs) || remainingMs <= 0)
      throw new AgentFailure("AUTHORITY_EXPIRED");
    if (abort?.aborted)
      throw new AgentFailure("JOURNAL_UNAVAILABLE", "ABORTED");
    const value = await this.post(
      dispatch.voiceSessionId,
      "agent-control",
      signal,
      controlSchema,
      abort,
      undefined,
      Math.min(3500, remainingMs),
    );
    if (Date.parse(signal.expiresAt) <= this.clock())
      throw new AgentFailure("AUTHORITY_EXPIRED");
    if (
      value.control.voiceSessionId !== dispatch.voiceSessionId ||
      value.control.attemptId !== dispatch.attemptId ||
      Date.parse(value.control.expiresAt) <= this.clock() ||
      Date.parse(value.control.issuedAt) > this.clock() + 500
    )
      throw new AgentFailure("INVALID_RESPONSE");
    return value;
  }
  async turn(dispatch: Dispatch, turn: Turn, key: string, signal: AbortSignal) {
    const value = await this.post(
      dispatch.voiceSessionId,
      "turns",
      turn,
      replySchema,
      signal,
      key,
    );
    if (
      value.voiceSessionId !== dispatch.voiceSessionId ||
      value.turnId !== turn.turnId ||
      Date.parse(value.expiresAt) <= this.clock()
    )
      throw new AgentFailure("INVALID_RESPONSE");
    return value;
  }
  async cancelSpeechTurn(
    dispatch: Dispatch,
    turn: Turn,
    signal: AbortSignal,
  ): Promise<void> {
    const value = await this.post(
      dispatch.voiceSessionId,
      `turns/${turn.turnId}/cancel`,
      {
        attemptId: turn.attemptId,
        leaseId: turn.leaseId,
      },
      replySchema
        .pick({ voiceSessionId: true, turnId: true })
        .extend({ status: z.literal("CANCELLED") })
        .strict(),
      signal,
    );
    if (
      value.voiceSessionId !== dispatch.voiceSessionId ||
      value.turnId !== turn.turnId
    )
      throw new AgentFailure("INVALID_RESPONSE");
  }
  async acknowledgeSpeechUnit(
    dispatch: Dispatch,
    turn: Turn,
    unitSequence: number,
    replyId: string,
    signal: AbortSignal,
  ): Promise<void> {
    const value = await this.post(
      dispatch.voiceSessionId,
      `turns/${turn.turnId}/speech-unit-ack`,
      {
        attemptId: turn.attemptId,
        leaseId: turn.leaseId,
        unitSequence,
        replyId,
      },
      z
        .object({
          voiceSessionId: z.uuid(),
          turnId: z.uuid(),
          unitSequence: z.number().int().min(1).max(2),
          status: z.literal("ACKNOWLEDGED"),
        })
        .strict(),
      signal,
    );
    if (
      value.voiceSessionId !== dispatch.voiceSessionId ||
      value.turnId !== turn.turnId ||
      value.unitSequence !== unitSequence
    )
      throw new AgentFailure("INVALID_RESPONSE");
  }
  async turnSpeechUnits(
    dispatch: Dispatch,
    turn: Turn,
    key: string,
    signal: AbortSignal,
    receive: (reply: Reply, sequence: number) => void,
  ): Promise<void> {
    if (!this.configuration.journalToken)
      throw new AgentFailure("CONFIGURATION_INVALID");
    const timeout = AbortSignal.timeout(30000);
    const abort = AbortSignal.any([timeout, signal]);
    const aborted = () => abort.aborted;
    let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;
    let received = false;
    try {
      const response = await this.transport(
        new URL(
          `/internal/v1/ai-companion/voice-sessions/${dispatch.voiceSessionId}/turns/speech-units`,
          this.configuration.journalUrl,
        ),
        {
          method: "POST",
          redirect: "error",
          cache: "no-store",
          headers: {
            "Content-Type": "application/json",
            "X-Correlation-Id": randomUUID(),
            "X-MentalBridge-Voice-Worker-Token":
              this.configuration.journalToken,
            "X-MentalBridge-Voice-Capabilities": speechUnitCapability,
            "Idempotency-Key": key,
          },
          body: JSON.stringify(turn),
          signal: abort,
        },
      );
      received = true;
      if (!response.ok)
        throw new AgentFailure(
          "JOURNAL_UNAVAILABLE",
          response.status >= 500 ? "HTTP_5XX" : "HTTP_4XX",
        );
      if (
        response.headers.get("cache-control") !== "no-store" ||
        response.headers.get("content-type")?.split(";")[0]?.trim() !==
          "application/json" ||
        response.headers.get("x-mentalbridge-voice-protocol") !==
          speechUnitCapability ||
        !response.body
      ) {
        void response.body?.cancel().catch(() => undefined);
        throw new AgentFailure("INVALID_RESPONSE");
      }
      reader = response.body.getReader();
      let units = 0;
      let completed = false;
      const isComplete = () => completed;
      let replyId: string | undefined;
      let characters = 0;
      const parser = new SpeechEventArray((event) => {
        if (isComplete() || aborted())
          throw new AgentFailure("INVALID_RESPONSE");
        if (event.type === "FAILED") throw new AgentFailure("PROVIDER_FAILED");
        if (event.type === "COMPLETED") {
          if (!units) throw new AgentFailure("INVALID_RESPONSE");
          completed = true;
          return;
        }
        if (
          event.voiceSessionId !== dispatch.voiceSessionId ||
          event.turnId !== turn.turnId ||
          event.unitSequence !== ++units ||
          (replyId && event.replyId !== replyId) ||
          Date.parse(event.expiresAt) <= this.clock() ||
          (characters += event.spokenText.length + (units > 1 ? 1 : 0)) >
            speechUnitMaxCharacters ||
          !/[.!?…]$/u.test(event.spokenText)
        )
          throw new AgentFailure("INVALID_RESPONSE");
        replyId = event.replyId;
        receive(
          {
            voiceSessionId: event.voiceSessionId,
            turnId: event.turnId,
            replyId: event.replyId,
            language: event.language,
            spokenText: event.spokenText,
            ...(event.expression ? { expression: event.expression } : {}),
            expiresAt: event.expiresAt,
            policyVersion: "companion-voice-policy-v1",
          },
          event.unitSequence,
        );
      });
      const decoder = new TextDecoder("utf-8", { fatal: true });
      const stop = () => {
        void reader?.cancel().catch(() => undefined);
      };
      abort.addEventListener("abort", stop, { once: true });
      try {
        let bytes = 0;
        for (;;) {
          if (aborted()) throw new AgentFailure("JOURNAL_UNAVAILABLE");
          const chunk = await reader.read();
          if (aborted()) throw new AgentFailure("JOURNAL_UNAVAILABLE");
          if (chunk.done) break;
          if ((bytes += chunk.value.length) > 16384)
            throw new AgentFailure("INVALID_RESPONSE");
          parser.accept(decoder.decode(chunk.value, { stream: true }));
        }
        parser.accept(decoder.decode());
        parser.finish();
        if (!isComplete()) throw new AgentFailure("INVALID_RESPONSE");
      } finally {
        abort.removeEventListener("abort", stop);
      }
    } catch (error) {
      throw new AgentFailure(
        "JOURNAL_UNAVAILABLE",
        signal.aborted
          ? "ABORTED"
          : timeout.aborted
            ? "TIMEOUT"
            : error instanceof AgentFailure
              ? (error.failure ?? "INVALID_RESPONSE")
              : received
                ? "INVALID_RESPONSE"
                : "TRANSPORT",
      );
    } finally {
      if (reader) {
        await reader.cancel().catch(() => undefined);
        reader.releaseLock();
      }
    }
  }
}
