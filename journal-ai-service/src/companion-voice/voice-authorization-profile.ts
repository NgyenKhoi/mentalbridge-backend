import { randomUUID } from "node:crypto";
import { performance } from "node:perf_hooks";
import { z } from "zod";

const callSchema = z
  .object({
    purpose: z.enum(["INITIAL", "PRE_INFERENCE", "PUBLICATION", "UNSCOPED"]),
    attemptId: z.uuid().optional(),
    turnId: z.uuid().optional(),
    turnSequence: z.number().int().min(1).max(2147483647).optional(),
  })
  .strict();
export type VoiceAuthorizationCall = z.infer<typeof callSchema>;
export type VoiceAuthorizationStage =
  | "TOTAL"
  | "STATE_BEFORE"
  | "MEDIA_DOCUMENT"
  | "OWNER_AUTHORITY"
  | "CARE"
  | "CONSULTATION"
  | "MEDIA_INSPECTION"
  | "STATE_AFTER"
  | "DENIAL_CLEANUP";
export interface VoiceAuthorizationDiagnostic extends VoiceAuthorizationCall {
  authorizationId: string;
  stage: VoiceAuthorizationStage;
  startedMs: number;
  elapsedMs: number;
  outcome: "SUCCESS" | "FAILED";
}
export class VoiceAuthorizationProfile {
  readonly tags: VoiceAuthorizationCall & { authorizationId: string };
  private readonly started: number;
  constructor(
    call: unknown,
    private readonly observe: (
      value: VoiceAuthorizationDiagnostic,
    ) => void = () => undefined,
    private readonly monotonic: () => number = () => performance.now(),
  ) {
    const parsed = callSchema.safeParse(call);
    this.tags = Object.freeze({
      ...(parsed.success ? parsed.data : { purpose: "UNSCOPED" as const }),
      authorizationId: randomUUID(),
    });
    this.started = this.monotonic();
  }

  async measure<T>(
    stage: VoiceAuthorizationStage,
    operation: () => Promise<T>,
  ): Promise<T> {
    const started = this.monotonic();
    let outcome: VoiceAuthorizationDiagnostic["outcome"] = "FAILED";
    try {
      const value = await operation();
      outcome = "SUCCESS";
      return value;
    } finally {
      const elapsed = this.monotonic() - started;
      const offset = started - this.started;
      if (Number.isFinite(elapsed) && Number.isFinite(offset)) {
        this.emit({
          ...this.tags,
          stage,
          startedMs: Math.min(30000, Math.max(0, Math.floor(offset))),
          elapsedMs: Math.min(30000, Math.max(0, Math.floor(elapsed))),
          outcome,
        });
      }
    }
  }

  private emit(value: VoiceAuthorizationDiagnostic) {
    try {
      this.observe(value);
    } catch {
      return;
    }
  }
}
