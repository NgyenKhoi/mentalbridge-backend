import { z } from 'zod';

import {
  ELIGIBILITY_POLICY_VERSIONS,
  ELIGIBILITY_ROLES,
  REQUIRED_ELIGIBILITY_ROLES,
  SCREENING_DOMAINS,
  SCREENING_INSTRUMENTS,
  SCREENING_LEVELS,
  SUPPORT_TIERS,
} from '../generated/resource-eligibility.contract.js';
import { ResourceLocaleSchema } from './resource.dto.js';

const policyVersion = z.enum(ELIGIBILITY_POLICY_VERSIONS);
const screeningDomain = z.enum(SCREENING_DOMAINS);
const eligibilityRole = z.enum(ELIGIBILITY_ROLES);
const requiredRole = z.enum(REQUIRED_ELIGIBILITY_ROLES);
const instrument = z.enum(SCREENING_INSTRUMENTS);
const screeningLevel = z.enum(SCREENING_LEVELS);
const supportTier = z.enum(SUPPORT_TIERS);
const dateTime = z.iso.datetime({ offset: true });
const contentVersion = z
  .string()
  .regex(/^(0|[1-9][0-9]*)$/)
  .max(19)
  .refine(
    (value) => /^(0|[1-9][0-9]*)$/.test(value) && BigInt(value) <= 9_223_372_036_854_775_807n,
    'contentVersion exceeds int64',
  );

const declaration = z
  .object({
    targetDomain: screeningDomain,
    role: eligibilityRole,
    instrument,
    screeningLevels: z.array(screeningLevel).min(1).max(5).refine(unique),
    supportTiers: z.array(supportTier).min(1).max(3).refine(unique),
  })
  .strict()
  .superRefine((value, context) => {
    const validPair =
      (value.targetDomain === 'DEPRESSIVE_SYMPTOMS' && value.instrument === 'PHQ_9') ||
      (value.targetDomain === 'ANXIETY_SYMPTOMS' && value.instrument === 'GAD_7');
    if (!validPair) {
      context.addIssue({
        code: 'custom',
        path: ['instrument'],
        message: 'instrument must match the approved screening domain',
      });
    }
    if (value.instrument === 'GAD_7' && value.screeningLevels.includes('MODERATELY_SEVERE')) {
      context.addIssue({
        code: 'custom',
        path: ['screeningLevels'],
        message: 'MODERATELY_SEVERE is not a GAD-7 screening level',
      });
    }
  });

export const PublishResourceEligibilitySchema = z
  .object({
    policyVersion,
    locale: ResourceLocaleSchema,
    effectiveAt: dateTime,
    expiresAt: dateTime.nullable().default(null),
    declarations: z.array(declaration).min(1).max(8),
  })
  .strict()
  .superRefine((value, context) => {
    if (value.expiresAt && Date.parse(value.expiresAt) <= Date.parse(value.effectiveAt)) {
      context.addIssue({
        code: 'custom',
        path: ['expiresAt'],
        message: 'expiresAt must be after effectiveAt',
      });
    }
    const declarationKeys = value.declarations.map(
      (item) => `${item.targetDomain}:${item.instrument}`,
    );
    if (new Set(declarationKeys).size !== declarationKeys.length) {
      context.addIssue({
        code: 'custom',
        path: ['declarations'],
        message: 'each domain and instrument may declare exactly one role',
      });
    }
  });

export const WithdrawResourceEligibilitySchema = z
  .object({
    policyVersion,
    reasonCode: z.enum(['CONTENT_WITHDRAWN', 'POLICY_WITHDRAWN', 'SUPERSEDED']),
  })
  .strict();

const eligibilityQuery = z
  .object({
    requestId: z.uuid(),
    resourceId: z.uuid(),
    contentVersion,
    targetDomain: screeningDomain,
    requiredRole,
    instrument,
    screeningLevel,
    supportTier,
    locale: ResourceLocaleSchema,
  })
  .strict()
  .superRefine((value, context) => {
    const validPair =
      (value.targetDomain === 'DEPRESSIVE_SYMPTOMS' && value.instrument === 'PHQ_9') ||
      (value.targetDomain === 'ANXIETY_SYMPTOMS' && value.instrument === 'GAD_7');
    if (!validPair) {
      context.addIssue({
        code: 'custom',
        path: ['instrument'],
        message: 'instrument must match the approved screening domain',
      });
    }
    if (value.instrument === 'GAD_7' && value.screeningLevel === 'MODERATELY_SEVERE') {
      context.addIssue({
        code: 'custom',
        path: ['screeningLevel'],
        message: 'MODERATELY_SEVERE is not a GAD-7 screening level',
      });
    }
  });

export const ResourceEligibilityBatchSchema = z
  .object({
    requests: z.array(eligibilityQuery).min(1).max(50),
  })
  .strict()
  .refine((value) => unique(value.requests.map((request) => request.requestId)), {
    path: ['requests'],
    message: 'requestId values must be unique within the batch',
  });

function unique(values: readonly string[]): boolean {
  return new Set(values).size === values.length;
}
