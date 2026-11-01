import { z } from 'zod';

export const ResourceBingoWeekSchema = z.iso
  .date()
  .refine(
    (value) => new Date(`${value}T00:00:00.000Z`).getUTCDay() === 1,
    'weekStart must be a Monday',
  );
export const ResourceBingoPositionSchema = z.coerce.number().int().min(1).max(9);
export const ResourceBingoSelfConfirmationSchema = z
  .object({ selfConfirmed: z.boolean() })
  .strict();

export const ResourceTimeZoneSchema = z
  .string()
  .min(1)
  .max(64)
  .refine((timeZone) => {
    try {
      new Intl.DateTimeFormat('en-US', { timeZone }).format();
      return true;
    } catch {
      return false;
    }
  }, 'must be a valid IANA time zone');

export const ResourceBingoPlanSyncSchema = z.object({ timeZone: ResourceTimeZoneSchema }).strict();

export const ResourceCompletionEvidenceQuerySchema = z
  .object({
    resourceId: z.uuid(),
    resourceVersion: z
      .string()
      .regex(/^(0|[1-9]\d*)$/)
      .max(19),
    localDate: z.iso.date(),
    timeZone: ResourceTimeZoneSchema,
    excludedFactIds: z
      .union([z.string().min(1).max(255), z.array(z.string().min(1).max(255))])
      .transform((value) => (typeof value === 'string' ? [value] : value))
      .optional(),
  })
  .strict();
