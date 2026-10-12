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
