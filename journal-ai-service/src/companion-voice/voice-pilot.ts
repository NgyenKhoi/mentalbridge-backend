import { z } from "zod";
import { measurementSchema } from "./voice-measurement-policy.js";

export const voicePilotSchema = z
  .object({
    startsAt: z.iso.datetime().transform((value) => new Date(value)),
    endsAt: z.iso.datetime().transform((value) => new Date(value)),
    ownerIds: z.array(z.uuid()).min(1).max(2),
    maxSessionSeconds: z.number().int().min(1).max(600).optional(),
    maxTurns: z.number().int().min(1).max(30).optional(),
    maxOutputTokens: z.number().int().min(300).max(600).optional(),
    replyStyle: z.enum(["BRIEF", "SUPPORTIVE"]).optional(),
    measurement: measurementSchema.optional(),
  })
  .strict()
  .refine((value) => {
    const duration = value.endsAt.getTime() - value.startsAt.getTime();
    return duration > 0 && duration <= 86_400_000;
  }, "Pilot window must be positive and at most 24 hours")
  .refine(
    (value) => new Set(value.ownerIds).size === value.ownerIds.length,
    "Pilot owner IDs must be unique",
  )
  .refine(
    (value) =>
      !value.measurement ||
      (value.endsAt.getTime() - value.startsAt.getTime() <= 1_800_000 &&
        (value.maxOutputTokens ?? 600) === 600 &&
        value.ownerIds.length <= value.measurement.maxSessions),
    "Measurement configuration must retain approved bounded limits",
  );

export type VoicePilot = z.infer<typeof voicePilotSchema>;
export const voicePilotAllows = (
  pilot: VoicePilot,
  ownerId: string,
  now: Date,
) =>
  pilot.ownerIds.includes(ownerId) &&
  now >= pilot.startsAt &&
  now < pilot.endsAt;
