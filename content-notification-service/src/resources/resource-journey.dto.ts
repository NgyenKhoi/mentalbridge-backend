import { z } from 'zod';

const unique = (values: readonly unknown[]): boolean => new Set(values).size === values.length;
const timeZoneExists = (timeZone: string): boolean => {
  try {
    new Intl.DateTimeFormat('en-US', { timeZone }).format();
    return true;
  } catch {
    return false;
  }
};

export const ResourceJourneyDateSchema = z.iso.date();

export const ResourceJourneyRequestSchema = z
  .object({
    timeZone: z.string().min(1).max(64).refine(timeZoneExists, 'must be a valid IANA time zone'),
    supportPlan: z
      .object({
        supportPlanId: z.uuid(),
        version: z.number().int().min(1),
        status: z.literal('ACTIVE'),
        activatedAt: z.iso.datetime({ offset: true }),
        domains: z
          .array(z.enum(['DEPRESSIVE_SYMPTOMS', 'ANXIETY_SYMPTOMS']))
          .min(1)
          .max(2)
          .refine(unique, 'must contain unique domains'),
        selectedResourceIds: z
          .array(z.uuid())
          .max(12)
          .refine(unique, 'must contain unique resource identifiers'),
      })
      .strict(),
  })
  .strict();

export type ResourceJourneyRequestDto = z.infer<typeof ResourceJourneyRequestSchema>;
