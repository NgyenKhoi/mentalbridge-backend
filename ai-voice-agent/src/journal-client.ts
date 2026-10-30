import { randomUUID } from "node:crypto";
import { type z } from "zod";
import { AgentFailure, type AgentConfiguration } from "./configuration.js";
import {
  controlSchema,
  leaseSchema,
  replySchema,
  type Dispatch,
  type Lease,
  type Signal,
  type Turn,
} from "./contract.js";
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
  ): Promise<T> {
    if (!this.configuration.journalToken)
      throw new AgentFailure("JOURNAL_UNAVAILABLE");
    const timeout = AbortSignal.timeout(path === "turns" ? 30000 : 2000);
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
      return schema.parse(JSON.parse(Buffer.concat(chunks).toString("utf8")));
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
      { attemptId: dispatch.attemptId, dispatchId: dispatch.dispatchId },
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
    return value;
  }
  async control(dispatch: Dispatch, signal: Signal, abort?: AbortSignal) {
    const value = await this.post(
      dispatch.voiceSessionId,
      "agent-control",
      signal,
      controlSchema,
      abort,
    );
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
}
