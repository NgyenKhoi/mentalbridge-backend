import { z } from 'zod';

export const AchievementTimezoneSchema = z
  .string()
  .min(1)
  .max(64)
  .refine((value) => {
    try {
      new Intl.DateTimeFormat('en-US', { timeZone: value }).format();
      return true;
    } catch {
      return false;
    }
  }, 'Invalid IANA timezone');

export const AchievementPreferenceUpdateSchema = z
  .object({ visibilityState: z.enum(['VISIBLE', 'HIDDEN']) })
  .strict();
