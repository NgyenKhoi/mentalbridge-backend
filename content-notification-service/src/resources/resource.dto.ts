import { z } from 'zod';

export const ResourceCategorySchema = z.enum([
  'BREATHING',
  'MEDITATION',
  'ARTICLE',
  'VIDEO',
  'JOURNALING',
  'COMMUNITY',
]);

const urlSchema = z.string().min(1).refine(
  (val) => {
    try { new URL(val); return true; } catch { return false; }
  },
  { message: 'externalUrl must be a valid URI' },
);

export const CreateResourceDtoSchema = z
  .object({
    category: ResourceCategorySchema,
    locale: z.string().min(2).max(16).default('vi-VN'),
    title: z.string().min(1).max(255),
    summary: z.string().min(1),
    contentBody: z.string().nullish(),
    externalUrl: urlSchema.nullish(),
  })
  .refine((data) => data.contentBody || data.externalUrl, {
    message: 'Either contentBody or externalUrl must be provided',
    path: ['contentBody'],
  });

export type CreateResourceDto = z.infer<typeof CreateResourceDtoSchema>;

export const UpdateResourceDtoSchema = z
  .object({
    title: z.string().min(1).max(255).optional(),
    summary: z.string().min(1).optional(),
    contentBody: z.string().nullish(),
    externalUrl: urlSchema.nullish(),
  })
  .refine(
    (data) => {
      // If both are explicitly set to null, reject
      const bodyNull = data.contentBody === null;
      const urlNull = data.externalUrl === null;
      return !(bodyNull && urlNull);
    },
    {
      message: 'Cannot set both contentBody and externalUrl to null',
      path: ['contentBody'],
    },
  );

export type UpdateResourceDto = z.infer<typeof UpdateResourceDtoSchema>;

export const PublishResourceDtoSchema = z
  .object({
    effectiveAt: z.coerce.date().nullish(),
    expiresAt: z.coerce.date().nullish(),
  })
  .refine(
    (data) => {
      if (data.effectiveAt && data.expiresAt) {
        return data.effectiveAt < data.expiresAt;
      }
      return true;
    },
    {
      message: 'effectiveAt must be before expiresAt',
      path: ['effectiveAt'],
    },
  );

export type PublishResourceDto = z.infer<typeof PublishResourceDtoSchema>;
