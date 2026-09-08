import { z } from 'zod';

export const ResourceCategorySchema = z.enum([
  'BREATHING',
  'MEDITATION',
  'ARTICLE',
  'VIDEO',
  'JOURNALING',
  'COMMUNITY',
]);

export const CreateResourceDtoSchema = z.object({
  category: ResourceCategorySchema,
  locale: z.string().min(2).max(16).default('vi-VN'),
  title: z.string().min(1).max(255),
  summary: z.string().min(1),
  contentBody: z.string().nullish(),
  externalUrl: z.string().min(1).nullish(),
}).refine(
  (data) => data.contentBody || data.externalUrl,
  {
    message: 'Either contentBody or externalUrl must be provided',
    path: ['contentBody'],
  },
);

export type CreateResourceDto = z.infer<typeof CreateResourceDtoSchema>;

export const UpdateResourceDtoSchema = z.object({
  title: z.string().min(1).max(255).optional(),
  summary: z.string().min(1).optional(),
  contentBody: z.string().nullish(),
  externalUrl: z.string().min(1).nullish(),
});

export type UpdateResourceDto = z.infer<typeof UpdateResourceDtoSchema>;

export const PublishResourceDtoSchema = z.object({
  effectiveAt: z.coerce.date().nullish(),
  expiresAt: z.coerce.date().nullish(),
});

export type PublishResourceDto = z.infer<typeof PublishResourceDtoSchema>;
