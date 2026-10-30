import { performance } from "node:perf_hooks";
import type { StopReason } from "./contract.js";

export type FailureCode =
  | "TIMEOUT"
  | "ABORTED"
  | "HTTP_4XX"
  | "HTTP_5XX"
  | "INVALID_RESPONSE"
  | "TRANSPORT"
  | "AUTHORITY_EXPIRED"
  | "PROVIDER_FAILED";
export interface AgentDiagnostic {
  phase:
    | "agent_claim_ok"
    | "media_ready_ack"
    | "next_heartbeat_due"
    | "heartbeat_attempt"
    | "heartbeat_ack"
    | "heartbeat_fail"
    | "lease_expired"
    | "turn_requested"
    | "turn_failed"
    | "tts_begin"
    | "playout_first_audio"
    | "agent_stopped";
  code?: FailureCode;
  remainingMs?: number;
  delayMs?: number;
  reason?: StopReason;
}
export function agentDiagnostics(
  attemptId: string,
  write: (value: object) => void = (value) => {
    process.stdout.write(`${JSON.stringify(value)}\n`);
  },
  monotonic: () => number = () => performance.now(),
) {
  const started = monotonic();
  let sequence = 0;
  return (value: AgentDiagnostic) => {
    if (sequence >= 512) return;
    write({
      event: "voice_agent",
      attemptId,
      sequence: ++sequence,
      elapsedMs: Math.max(0, Math.floor(monotonic() - started)),
      ...value,
    });
  };
}
