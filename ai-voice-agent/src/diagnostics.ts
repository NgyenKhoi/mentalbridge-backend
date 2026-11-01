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
export type MediaFailureSource =
  | "SDK_ERROR"
  | "SDK_CLOSE"
  | "ROOM_RECONNECTING"
  | "ROOM_DISCONNECTED"
  | "PARTICIPANT_GUARD"
  | "PLAYBACK_PUBLICATION"
  | "UNKNOWN";
export interface MediaFailure {
  source: MediaFailureSource;
  component?: "STT" | "TTS" | "OTHER";
  code?: FailureCode;
  recoverable?: boolean;
}
export type TranscriptRejection =
  | "INVALID_CONFIDENCE"
  | "LOW_CONFIDENCE"
  | "LANGUAGE_MISMATCH"
  | "TRANSCRIPT_TOO_LARGE"
  | "SEGMENT_LIMIT"
  | "PENDING_TOO_LARGE";
export function mediaFailure(
  source: MediaFailureSource,
  event?: unknown,
): MediaFailure {
  const object = (value: unknown): Record<string, unknown> =>
    typeof value === "object" && value !== null
      ? (value as Record<string, unknown>)
      : {};
  const error = object(object(event).error);
  const status = object(error.error).statusCode;
  return {
    source,
    component:
      error.type === "stt_error"
        ? "STT"
        : error.type === "tts_error"
          ? "TTS"
          : "OTHER",
    ...(typeof error.recoverable === "boolean"
      ? { recoverable: error.recoverable }
      : {}),
    ...(typeof status === "number" && Number.isInteger(status)
      ? status >= 400 && status < 500
        ? { code: "HTTP_4XX" as const }
        : status >= 500 && status < 600
          ? { code: "HTTP_5XX" as const }
          : {}
      : {}),
  };
}
export interface AgentDiagnostic {
  phase:
    | "agent_claim_ok"
    | "startup_failed"
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
    | "speech_usage"
    | "stt_final"
    | "media_failed"
    | "agent_stopped";
  code?: FailureCode;
  remainingMs?: number;
  delayMs?: number;
  reason?: StopReason;
  component?: "STT" | "TTS" | "OTHER";
  audioDurationMs?: number;
  charactersCount?: number;
  cancelled?: boolean;
  source?: MediaFailureSource;
  recoverable?: boolean;
  transcriptResult?: "EMPTY" | "PENDING" | "REJECTED";
  rejection?: TranscriptRejection;
  confidence?: number;
  confidenceValid?: boolean;
  languageAccepted?: boolean;
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
