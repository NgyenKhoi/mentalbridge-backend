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
  })
  .strict();

export type ResourceProgressUpdateDto = z.infer<typeof ResourceProgressUpdateSchema>;
