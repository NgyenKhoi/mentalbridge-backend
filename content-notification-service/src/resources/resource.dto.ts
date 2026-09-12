import { z } from 'zod';

export const ResourceCategorySchema = z.enum([
  'BREATHING',
  'MEDITATION',
  'ARTICLE',
  'VIDEO',
  'JOURNALING',
  'COMMUNITY',
]);

export const ResourceLocaleSchema = z
  .string()
  .regex(/^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$/, 'locale must be a valid BCP 47 tag');

const urlSchema = z.url().refine(
  (value) => {
    const url = new URL(value);
    return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password;
  },
  { message: 'externalUrl must be an HTTP(S) URL without credentials' },
);

const nullableDateTime = z.iso
  .datetime({ offset: true })
  .transform((value) => new Date(value))
  .nullish();

const resourceDates = (data: { effectiveAt?: Date | null; expiresAt?: Date | null }) =>
  !data.effectiveAt || !data.expiresAt || data.effectiveAt < data.expiresAt;

export const CreateResourceDtoSchema = z
  .object({
    category: ResourceCategorySchema,
    locale: ResourceLocaleSchema.default('vi-VN'),
    title: z.string().min(1).max(255),
    summary: z.string().min(1),
    contentBody: z.string().nullish(),
    externalUrl: urlSchema.nullish(),
    effectiveAt: nullableDateTime,
    expiresAt: nullableDateTime,
  })
  .refine((data) => data.contentBody || data.externalUrl, {
    message: 'Either contentBody or externalUrl must be provided',
    path: ['contentBody'],
  })
  .refine(resourceDates, {
    message: 'effectiveAt must be before expiresAt',
    path: ['effectiveAt'],
  });

export type CreateResourceDto = z.infer<typeof CreateResourceDtoSchema>;

export const UpdateResourceDtoSchema = z
  .object({
    locale: ResourceLocaleSchema.optional(),
    title: z.string().min(1).max(255).optional(),
    summary: z.string().min(1).optional(),
    contentBody: z.string().nullish(),
    externalUrl: urlSchema.nullish(),
    effectiveAt: nullableDateTime,
    expiresAt: nullableDateTime,
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
  )
  .refine(resourceDates, {
    message: 'effectiveAt must be before expiresAt',
    path: ['effectiveAt'],
  });

export type UpdateResourceDto = z.infer<typeof UpdateResourceDtoSchema>;
