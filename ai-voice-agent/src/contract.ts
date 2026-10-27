import { z } from "zod";
import { AgentFailure } from "./configuration.js";
export const agentName = "mentalbridge-ai-voice-v1";
export const policyVersion = "companion-voice-policy-v1";
const uuid = z.uuid();
export const dispatchSchema = z
  .object({ voiceSessionId: uuid, attemptId: uuid, dispatchId: uuid })
  .strict();
export type Dispatch = z.infer<typeof dispatchSchema>;
export const reasons = [
  "USER_ENDED",
  "CONSENT_REVOKED",
  "VOICE_CONSENT_REVOKED",
  "ENTITLEMENT_LOST",
  "OWNER_UNAVAILABLE",
  "IDLE_TIMEOUT",
  "HARD_DEADLINE",
  "QUOTA_EXHAUSTED",
  "RECONNECT_TIMEOUT",
  "PROVIDER_FAILURE",
  "AGENT_FAILURE",
  "AUTHORIZATION_EXPIRED",
  "ACCOUNT_DELETED",
] as const;
export type StopReason = (typeof reasons)[number];
export const leaseSchema = z
  .object({
    voiceSessionId: uuid,
    attemptId: uuid,
    leaseId: uuid,
    validUntil: z.iso.datetime({ offset: true }),
    hardStopAt: z.iso.datetime({ offset: true }),
    policyVersion: z.literal(policyVersion),
  })
  .strict();
export type Lease = z.infer<typeof leaseSchema>;
const envelope = {
  schemaVersion: z.literal(1),
  commandId: uuid,
  voiceSessionId: uuid,
  attemptId: uuid,
  sequence: z.number().int().min(1).max(2147483647),
  issuedAt: z.iso.datetime({ offset: true }),
  expiresAt: z.iso.datetime({ offset: true }),
};
export const controlSchema = z.discriminatedUnion("action", [
  z
    .object({
      action: z.literal("CONTINUE"),
      control: z
        .object({
          ...envelope,
          kind: z.literal("LEASE_RENEWED"),
          payload: z
            .object({
              leaseId: uuid,
              validUntil: z.iso.datetime({ offset: true }),
              hardStopAt: z.iso.datetime({ offset: true }),
              admittedSeconds: z.number().int().min(1).max(600),
            })
            .strict(),
        })
        .strict(),
    })
    .strict(),
  z
    .object({
      action: z.literal("STOP"),
      control: z
        .object({
          ...envelope,
          kind: z.literal("STOP"),
          payload: z.object({ reason: z.enum(reasons) }).strict(),
        })
        .strict(),
    })
    .strict(),
]);
export type Control = z.infer<typeof controlSchema>;
export const replySchema = z
  .object({
    voiceSessionId: uuid,
    turnId: uuid,
    replyId: uuid,
    language: z.literal("vi-VN"),
    spokenText: z.string().trim().min(1).max(1000),
    expiresAt: z.iso.datetime({ offset: true }),
    policyVersion: z.literal(policyVersion),
  })
  .strict();
export type Reply = z.infer<typeof replySchema>;
export interface Turn {
  attemptId: string;
  leaseId: string;
  turnId: string;
  sequence: number;
  language: "vi-VN";
  isFinal: true;
  finalTranscript: string;
}
export interface Signal {
  schemaVersion: 1;
  kind:
    | "MEDIA_READY"
    | "HEARTBEAT"
    | "PLAYBACK_STARTED"
    | "INTERRUPTED"
    | "FINISHED";
  commandId: string;
  voiceSessionId: string;
  attemptId: string;
  sequence: number;
  issuedAt: string;
  expiresAt: string;
  payload: {
    leaseId: string;
    observedState?: "CONNECTING" | "ACTIVE" | "RECONNECTING" | "ENDING";
    turnId?: string;
    replyId?: string;
    reason?: StopReason;
  };
}
export function parseDispatch(input: {
  agentName: string;
  metadata: string;
  roomName: string;
  roomMetadata: string;
  recording: boolean;
}): Dispatch {
  try {
    if (
      input.agentName !== agentName ||
      input.metadata.length > 512 ||
      !input.roomName.startsWith("ai-voice-") ||
      !z.uuid().safeParse(input.roomName.slice(9)).success ||
      input.recording
    )
      throw new AgentFailure("DISPATCH_INVALID");
    const value = dispatchSchema.parse(JSON.parse(input.metadata));
    if (input.roomMetadata !== value.dispatchId)
      throw new AgentFailure("DISPATCH_INVALID");
    return value;
  } catch {
    throw new AgentFailure("DISPATCH_INVALID");
  }
}
