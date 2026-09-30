import { z } from 'zod';

export const ResourceIdSchema = z.uuid();

export const ResourceCategorySchema = z.enum([
  'BREATHING',
  'MEDITATION',
  'ARTICLE',
  'VIDEO',
  'JOURNALING',
  'COMMUNITY',
]);

export const ResourceKindSchema = z.enum(['LEARNING', 'PRACTICE', 'HABIT', 'ACTION', 'REFLECTION']);
export const ResourceInteractionTypeSchema = z.enum([
  'STRUCTURED_READER',
  'VIDEO_TRANSCRIPT',
  'BREATHING_PACER',
  'GROUNDING_GUIDE',
  'PROGRESSIVE_RELAXATION',
  'WALK_TIMER',
  'STRETCH_SEQUENCE',
  'PROBLEM_SOLVING_WORKSHEET',
  'BEHAVIORAL_ACTIVATION_PLANNER',
  'SELF_COMPASSION_PROMPTS',
  'UNHOOKING_PROMPTS',
  'PREPARE_FOR_SPECIALIST_CHECKLIST',
  'REFLECTION',
]);
export const ResourceRepeatabilitySchema = z.enum(['ONE_TIME', 'REPEATABLE']);
export const ResourceCompletionModeSchema = z.enum([
  'EXPLICIT',
  'STEPS',
  'TIMED',
  'VIDEO_CONFIRMATION',
]);
export const ResourceSourceReviewStatusSchema = z.enum([
  'REVIEWED',
  'REVIEW_REQUIRED',
  'NEEDS_SOURCE_REVIEW',
]);

const semanticFields = {
  resourceKind: ResourceKindSchema.optional(),
  interactionType: ResourceInteractionTypeSchema.optional(),
  repeatability: ResourceRepeatabilitySchema.optional(),
  completionMode: ResourceCompletionModeSchema.optional(),
  streakEligible: z.boolean().optional(),
  expectedDurationMinutes: z.number().int().min(1).max(120).optional(),
  cooldownDays: z.number().int().min(0).max(30).optional(),
  recommendedFrequencyPerWeek: z.number().int().min(1).max(7).optional(),
  planTags: z.array(z.string().min(1).max(64)).min(1).max(12).optional(),
  structuredContent: z.record(z.string(), z.unknown()).default({}),
  interactionConfig: z.record(z.string(), z.unknown()).default({}),
  safetyNotes: z.array(z.string().min(1).max(500)).max(12).default([]),
  catalogueVisibility: z.enum(['LISTED', 'DIRECT_ONLY']).default('DIRECT_ONLY'),
  contentVersionLabel: z.string().min(1).max(64).default('draft-v1'),
  sourceReviewStatus: ResourceSourceReviewStatusSchema.default('NEEDS_SOURCE_REVIEW'),
};

function categoryDefaults(category: z.infer<typeof ResourceCategorySchema>) {
  switch (category) {
    case 'BREATHING':
      return {
        resourceKind: 'PRACTICE' as const,
        interactionType: 'BREATHING_PACER' as const,
        repeatability: 'REPEATABLE' as const,
        completionMode: 'TIMED' as const,
        streakEligible: true,
      };
    case 'MEDITATION':
      return {
        resourceKind: 'PRACTICE' as const,
        interactionType: 'GROUNDING_GUIDE' as const,
        repeatability: 'REPEATABLE' as const,
        completionMode: 'STEPS' as const,
        streakEligible: true,
      };
    case 'VIDEO':
      return {
        resourceKind: 'LEARNING' as const,
        interactionType: 'VIDEO_TRANSCRIPT' as const,
        repeatability: 'ONE_TIME' as const,
        completionMode: 'VIDEO_CONFIRMATION' as const,
        streakEligible: false,
      };
    case 'JOURNALING':
      return {
        resourceKind: 'REFLECTION' as const,
        interactionType: 'REFLECTION' as const,
        repeatability: 'REPEATABLE' as const,
        completionMode: 'STEPS' as const,
        streakEligible: false,
      };
    case 'COMMUNITY':
      return {
        resourceKind: 'HABIT' as const,
        interactionType: 'WALK_TIMER' as const,
        repeatability: 'REPEATABLE' as const,
        completionMode: 'TIMED' as const,
        streakEligible: true,
      };
    case 'ARTICLE':
      return {
        resourceKind: 'LEARNING' as const,
        interactionType: 'STRUCTURED_READER' as const,
        repeatability: 'ONE_TIME' as const,
        completionMode: 'EXPLICIT' as const,
        streakEligible: false,
      };
  }
}

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

export const VerifiedVideoUrlSchema = urlSchema.refine(
  (value) => {
    const url = new URL(value);
    const hostname = url.hostname.toLowerCase();
    return (
      url.protocol === 'https:' &&
      (hostname === 'youtube.com' || hostname === 'www.youtube.com' || hostname === 'youtu.be')
    );
  },
  { message: 'VIDEO resources require a verified YouTube URL' },
);

const sourceFields = {
  sourceOrganization: z.string().min(1).max(200).nullish(),
  sourceTitle: z.string().min(1).max(500).nullish(),
  sourceUrl: urlSchema.nullish(),
  sourceReviewNote: z.string().min(1).nullish(),
};

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
    ...semanticFields,
    ...sourceFields,
    sourceRetrievedAt: nullableDateTime,
    sourceContentHash: z
      .string()
      .regex(/^[a-f0-9]{64}$/)
      .nullish(),
    effectiveAt: nullableDateTime,
    expiresAt: nullableDateTime,
  })
  .refine((data) => data.contentBody || data.externalUrl, {
    message: 'Either contentBody or externalUrl must be provided',
    path: ['contentBody'],
  })
  .refine(
    (data) =>
      data.category !== 'VIDEO' || VerifiedVideoUrlSchema.safeParse(data.externalUrl).success,
    {
      message: 'VIDEO resources require a verified YouTube URL',
      path: ['externalUrl'],
    },
  )
  .refine(resourceDates, {
    message: 'effectiveAt must be before expiresAt',
    path: ['effectiveAt'],
  })
  .transform((data) => {
    const defaults = categoryDefaults(data.category);
    return {
      ...data,
      resourceKind: data.resourceKind ?? defaults.resourceKind,
      interactionType: data.interactionType ?? defaults.interactionType,
      repeatability: data.repeatability ?? defaults.repeatability,
      completionMode: data.completionMode ?? defaults.completionMode,
      streakEligible: data.streakEligible ?? defaults.streakEligible,
      expectedDurationMinutes: data.expectedDurationMinutes ?? 5,
      cooldownDays: data.cooldownDays ?? 0,
      recommendedFrequencyPerWeek: data.recommendedFrequencyPerWeek ?? 1,
      planTags: data.planTags ?? ['DEPRESSIVE_SYMPTOMS', 'ANXIETY_SYMPTOMS'],
    };
  });

export type CreateResourceDto = z.infer<typeof CreateResourceDtoSchema>;

export const UpdateResourceDtoSchema = z
  .object({
    locale: ResourceLocaleSchema.optional(),
    title: z.string().min(1).max(255).optional(),
    summary: z.string().min(1).optional(),
    contentBody: z.string().nullish(),
    externalUrl: urlSchema.nullish(),
    ...Object.fromEntries(
      Object.entries(semanticFields).map(([key, schema]) => [key, schema.optional()]),
    ),
    ...sourceFields,
    sourceRetrievedAt: nullableDateTime,
    sourceContentHash: z
      .string()
      .regex(/^[a-f0-9]{64}$/)
      .nullish(),
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
