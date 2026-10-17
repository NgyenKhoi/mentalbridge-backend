import { z } from 'zod';

export const ResourceProgressResourceIdSchema = z.uuid();
export const ResourceProgressDateSchema = z.iso.date();

export const ResourceProgressUpdateSchema = z
  .object({
    status: z.enum(['IN_PROGRESS', 'COMPLETED']),
    completedActionIds: z
      .array(
        z
          .string()
          .min(1)
          .max(64)
          .regex(/^[A-Za-z0-9:_-]+$/),
      )
      .max(32)
      .refine((values) => new Set(values).size === values.length, {
        message: 'must contain unique action identifiers',
      }),
    practiceSessionId: z.uuid().optional(),
    practiceStartedAt: z.iso.datetime({ offset: true }).optional(),
    practiceDurationSeconds: z.number().int().min(1).max(7_200).optional(),
  })
  .strict()
  .superRefine((value, context) => {
    if (
      (value.practiceStartedAt !== undefined || value.practiceDurationSeconds !== undefined) &&
      value.practiceSessionId === undefined
    ) {
      context.addIssue({
        code: 'custom',
        message: 'practiceSessionId is required for practice session metadata',
      });
    }
  });

export type ResourceProgressUpdateDto = z.infer<typeof ResourceProgressUpdateSchema>;
