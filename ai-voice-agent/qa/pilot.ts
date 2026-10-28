import { z } from "zod";
import { BenchmarkFailure } from "./metrics.js";
import { sttModels, ttsModel } from "./corpus.js";

export const pilotLimits = Object.freeze({
  creditMicroUsd: 500000,
  sttMs: 600000,
  ttsCharacters: 3000,
});
export const priceSnapshot = Object.freeze({
  checkedOn: "2026-10-28",
  source: "https://livekit.com/pricing/inference",
  sttMicroUsdPerMinute: { "deepgram/nova-3": 4800, "deepgram/nova-2": 5800 },
  ttsMicroUsdPerCharacter: 50,
});
const status = z.enum(["VERIFIED_OFF", "NOT_VERIFIED"]);
export const readinessSchema = z.strictObject({
  checkedOn: z.iso.date(),
  plan: z.literal("BUILD_FREE"),
  nextInvoiceMicroUsd: z.number().int().nonnegative(),
  remainingInferenceMicroUsd: z.number().int().nonnegative().nullable(),
  observability: status,
  recording: status,
  egress: status,
  externalContentExport: status,
});
export const operatorSnapshot = Object.freeze({
  checkedOn: "2026-10-28",
  plan: "BUILD_FREE" as const,
  nextInvoiceMicroUsd: 0,
  remainingInferenceMicroUsd: null,
  observability: "VERIFIED_OFF" as const,
  recording: "NOT_VERIFIED" as const,
  egress: "NOT_VERIFIED" as const,
  externalContentExport: "NOT_VERIFIED" as const,
});
export function readiness(input: unknown) {
  const parsed = readinessSchema.safeParse(input);
  if (!parsed.success) throw new BenchmarkFailure("INVALID_INPUT");
  const value = parsed.data;
  const blockers: string[] = [];
  if (value.remainingInferenceMicroUsd === null)
    blockers.push("BLOCKED_ALLOWANCE");
  else if (value.remainingInferenceMicroUsd < pilotLimits.creditMicroUsd)
    blockers.push("BLOCKED_INSUFFICIENT_ALLOWANCE");
  if (value.nextInvoiceMicroUsd !== 0) blockers.push("BLOCKED_BILLING");
  for (const key of [
    "observability",
    "recording",
    "egress",
    "externalContentExport",
  ] as const) {
    if (value[key] !== "VERIFIED_OFF")
      blockers.push(`BLOCKED_${key.toUpperCase()}`);
  }
  return {
    status: blockers[0] ?? "REQUIRES_OPERATOR_AUTHORIZATION",
    blockers,
    providerExecutionAvailable: false,
    basis:
      "operator-reported public status; not independently authenticated by this tool",
    snapshot: value,
    campaign: "MB-760-first-benchmark",
    limits: pilotLimits,
    sttCandidates: sttModels,
    ttsCandidate: ttsModel,
    voiceId: null,
  };
}
const usageSchema = z.strictObject({
  campaign: z.literal("MB-760-first-benchmark"),
  creditMicroUsd: z.number().int().min(0).max(pilotLimits.creditMicroUsd),
  sttMs: z.number().int().min(0).max(pilotLimits.sttMs),
  ttsCharacters: z.number().int().min(0).max(pilotLimits.ttsCharacters),
});
export type Usage = z.infer<typeof usageSchema>;
const requestSchema = z.discriminatedUnion("kind", [
  z.strictObject({
    kind: z.literal("STT"),
    model: z.enum(sttModels),
    audioMs: z.number().int().min(1).max(600000),
  }),
  z.strictObject({
    kind: z.literal("TTS"),
    model: z.literal(ttsModel),
    characters: z.number().int().min(1).max(3000),
  }),
]);
export class PilotBudget {
  private used: Usage;
  constructor(
    prior: unknown = {
      campaign: "MB-760-first-benchmark",
      creditMicroUsd: 0,
      sttMs: 0,
      ttsCharacters: 0,
    },
  ) {
    const parsed = usageSchema.safeParse(prior);
    if (!parsed.success) throw new BenchmarkFailure("INVALID_INPUT");
    this.used = parsed.data;
  }
  reserve(input: unknown) {
    const parsed = requestSchema.safeParse(input);
    if (!parsed.success) throw new BenchmarkFailure("INVALID_INPUT");
    const request = parsed.data;
    const before = this.used;
    const halted =
      before.creditMicroUsd >= pilotLimits.creditMicroUsd ||
      before.sttMs >= pilotLimits.sttMs ||
      before.ttsCharacters >= pilotLimits.ttsCharacters;
    const next = {
      campaign: before.campaign,
      creditMicroUsd:
        before.creditMicroUsd +
        (request.kind === "STT"
          ? Math.ceil(
              (Math.ceil(request.audioMs / 1000) *
                priceSnapshot.sttMicroUsdPerMinute[request.model]) /
                60,
            )
          : request.characters * priceSnapshot.ttsMicroUsdPerCharacter),
      sttMs:
        before.sttMs +
        (request.kind === "STT" ? Math.ceil(request.audioMs / 1000) * 1000 : 0),
      ttsCharacters:
        before.ttsCharacters +
        (request.kind === "TTS" ? request.characters : 0),
    };
    if (halted || !usageSchema.safeParse(next).success)
      throw new BenchmarkFailure("BUDGET_EXCEEDED");
    this.used = next;
    return this.snapshot();
  }
  snapshot(): Usage {
    return { ...this.used };
  }
}
