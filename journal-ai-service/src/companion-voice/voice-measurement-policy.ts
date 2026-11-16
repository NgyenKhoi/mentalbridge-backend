import { createHash } from "node:crypto";
import { z } from "zod";

export const measurementSchema = z
  .object({
    campaignId: z.uuid(),
    maxSessions: z.number().int().min(1).max(2),
    maxConcurrentSessions: z.literal(1),
    maxTurns: z.number().int().min(1).max(12),
    maxVoiceSeconds: z.number().int().min(1).max(480),
    maxTtsCharacters: z.number().int().min(1).max(4000),
    voiceId: z.enum([
      "b8cd71e3-bc14-4538-a530-d6314731c036",
      "8e8f222d-c817-4cc5-822b-8bf76ca7e98d",
    ]),
  })
  .strict()
  .refine(
    (value) =>
      value.maxTurns >= value.maxSessions &&
      value.maxVoiceSeconds >= value.maxSessions &&
      value.maxTtsCharacters >= value.maxSessions,
  );

export interface MeasurementPilot {
  startsAt: Date;
  endsAt: Date;
  ownerIds: string[];
  maxTurns?: number | undefined;
  maxSessionSeconds?: number | undefined;
  maxOutputTokens?: number | undefined;
  replyStyle?: "BRIEF" | "SUPPORTIVE" | undefined;
  measurement?: z.infer<typeof measurementSchema> | undefined;
}

export function measurementPolicy(pilot?: MeasurementPilot) {
  const m = pilot?.measurement;
  if (!pilot || !m) return undefined;
  const allocation = {
    turns: Math.min(
      pilot.maxTurns ?? 4,
      Math.floor(m.maxTurns / m.maxSessions),
    ),
    voiceSeconds: Math.min(
      pilot.maxSessionSeconds ?? 90,
      Math.floor(m.maxVoiceSeconds / m.maxSessions),
    ),
    ttsCharacters: Math.floor(m.maxTtsCharacters / m.maxSessions),
  };
  const configHash = createHash("sha256")
    .update(
      JSON.stringify({
        version: 1,
        ...m,
        startsAt: pilot.startsAt.toISOString(),
        endsAt: pilot.endsAt.toISOString(),
        owners: [...pilot.ownerIds].sort(),
        allocation,
        outputTokens: pilot.maxOutputTokens ?? 300,
        replyStyle: pilot.replyStyle ?? "BRIEF",
        inputTokens: 1900,
        model: "global.anthropic.claude-sonnet-4-6",
        region: "ap-southeast-1",
        stt: "deepgram/nova-3",
        tts: "cartesia/sonic-3-2026-01-12",
      }),
    )
    .digest("base64url");
  return {
    campaignId: m.campaignId,
    configHash,
    startsAt: pilot.startsAt,
    endsAt: pilot.endsAt,
    caps: {
      sessions: m.maxSessions,
      concurrent: m.maxConcurrentSessions,
      turns: m.maxTurns,
      voiceSeconds: m.maxVoiceSeconds,
      ttsCharacters: m.maxTtsCharacters,
    },
    allocation,
    voiceId: m.voiceId,
  };
}
export type MeasurementPolicy = NonNullable<
  ReturnType<typeof measurementPolicy>
>;
