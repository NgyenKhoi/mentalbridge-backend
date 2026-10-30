import { z } from "zod";

export const voicePilotSchema = z
  .object({
    startsAt: z.iso.datetime().transform((value) => new Date(value)),
    endsAt: z.iso.datetime().transform((value) => new Date(value)),
    ownerIds: z.array(z.uuid()).min(1).max(2),
  })
  .strict()
  .refine((value) => {
    const duration = value.endsAt.getTime() - value.startsAt.getTime();
    return duration > 0 && duration <= 86_400_000;
  }, "Pilot window must be positive and at most 24 hours");

export type VoicePilot = z.infer<typeof voicePilotSchema>;
export const voicePilotAllows = (
  pilot: VoicePilot,
  ownerId: string,
  now: Date,
) =>
  pilot.ownerIds.includes(ownerId) &&
  now >= pilot.startsAt &&
  now < pilot.endsAt;
