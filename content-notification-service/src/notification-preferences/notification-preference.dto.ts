import { z } from 'zod';

const ChannelPatchSchema = z
  .object({
    inApp: z.boolean().optional(),
    email: z.boolean().optional(),
    push: z.boolean().optional(),
  })
  .strict()
  .refine((value) => Object.keys(value).length > 0, 'at least one channel is required');

const ContentGroupPatchSchema = z
  .object({
    journalReminder: z.boolean().optional(),
    emotionCheckIn: z.boolean().optional(),
    streakMilestone: z.boolean().optional(),
    screeningReassessment: z.boolean().optional(),
    appointmentMessage: z.boolean().optional(),
    resourceSystem: z.boolean().optional(),
  })
  .strict()
  .refine((value) => Object.keys(value).length > 0, 'at least one content group is required');

const TimeSchema = z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/, 'must use HH:mm');

export const NotificationPreferencePatchSchema = z
  .object({
    notificationsEnabled: z.boolean().optional(),
    channels: ChannelPatchSchema.optional(),
    contentGroups: ContentGroupPatchSchema.optional(),
    quietHours: z
      .object({
        enabled: z.boolean().optional(),
        start: TimeSchema.optional(),
        end: TimeSchema.optional(),
        timeZone: z.string().trim().min(1).max(64).optional(),
      })
      .strict()
      .refine((value) => Object.keys(value).length > 0, 'at least one quiet-hour field is required')
      .optional(),
    email: z
      .object({
        cadence: z.enum(['IMMEDIATE', 'DAILY_DIGEST', 'WEEKLY_DIGEST']).optional(),
        wellbeingDigestEnabled: z.boolean().optional(),
        resourceRemindersEnabled: z.boolean().optional(),
      })
      .strict()
      .refine((value) => Object.keys(value).length > 0, 'at least one email field is required')
      .optional(),
  })
  .strict()
  .refine((value) => Object.keys(value).length > 0, 'at least one preference is required');

export type NotificationPreferencePatchDto = z.infer<typeof NotificationPreferencePatchSchema>;
