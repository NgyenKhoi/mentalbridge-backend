import { z } from 'zod';

export const PushInstallationIdSchema = z.uuid();

export const PushDeviceRegistrationSchema = z
  .object({
    platform: z.enum(['ANDROID', 'IOS']),
    provider: z.literal('EXPO'),
    providerToken: z
      .string()
      .trim()
      .min(20)
      .max(512)
      .regex(/^ExponentPushToken\[[A-Za-z0-9_-]+\]$/),
    permissionStatus: z.enum(['GRANTED', 'DENIED']),
    appVersion: z.string().trim().min(1).max(32).optional(),
  })
  .strict();
