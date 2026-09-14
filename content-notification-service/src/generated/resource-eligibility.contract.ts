export const ELIGIBILITY_POLICY_VERSIONS = ['content-eligibility-v1'] as const;
export const SCREENING_DOMAINS = ['DEPRESSIVE_SYMPTOMS', 'ANXIETY_SYMPTOMS'] as const;
export const ELIGIBILITY_ROLES = ['PRIMARY', 'ADJUNCT'] as const;
export const REQUIRED_ELIGIBILITY_ROLES = ['PRIMARY', 'PRIMARY_OR_ADJUNCT'] as const;
export const SCREENING_INSTRUMENTS = ['PHQ_9', 'GAD_7'] as const;
export const SCREENING_LEVELS = [
  'MINIMAL',
  'MILD',
  'MODERATE',
  'MODERATELY_SEVERE',
  'SEVERE',
] as const;
export const SUPPORT_TIERS = [
  'SELF_GUIDED_SUPPORT',
  'PROFESSIONAL_SUPPORT_RECOMMENDED',
  'SAFETY_FOLLOW_UP_RECOMMENDED',
] as const;
export const ELIGIBILITY_PUBLICATION_STATES = ['PUBLISHED', 'WITHDRAWN'] as const;
export const RESOURCE_ELIGIBILITY_OUTCOMES = [
  'ELIGIBLE',
  'INELIGIBLE',
  'STALE',
  'WITHDRAWN',
  'NOT_FOUND',
  'UNAVAILABLE',
] as const;
export const RESOURCE_ELIGIBILITY_REASON_CODES = [
  'ELIGIBLE_MATCH',
  'RESOURCE_NOT_FOUND',
  'CONTENT_VERSION_STALE',
  'NO_ELIGIBILITY_PUBLICATION',
  'DOMAIN_OR_PATHWAY_NOT_ELIGIBLE',
  'PRIMARY_REQUIRED',
  'NOT_YET_EFFECTIVE',
  'EFFECTIVE_WINDOW_ENDED',
  'RESOURCE_ARCHIVED',
  'RESOURCE_NOT_PUBLISHED',
  'LOCALE_MISMATCH',
  'ELIGIBILITY_WITHDRAWN',
  'DEPENDENCY_UNAVAILABLE',
] as const;

export type EligibilityPolicyVersion = (typeof ELIGIBILITY_POLICY_VERSIONS)[number];
export type ScreeningDomain = (typeof SCREENING_DOMAINS)[number];
export type EligibilityRole = (typeof ELIGIBILITY_ROLES)[number];
export type RequiredEligibilityRole = (typeof REQUIRED_ELIGIBILITY_ROLES)[number];
export type ScreeningInstrument = (typeof SCREENING_INSTRUMENTS)[number];
export type ScreeningLevel = (typeof SCREENING_LEVELS)[number];
export type SupportTier = (typeof SUPPORT_TIERS)[number];
export type EligibilityPublicationState = (typeof ELIGIBILITY_PUBLICATION_STATES)[number];
export type ResourceEligibilityOutcome = (typeof RESOURCE_ELIGIBILITY_OUTCOMES)[number];
export type ResourceEligibilityReasonCode = (typeof RESOURCE_ELIGIBILITY_REASON_CODES)[number];

export interface EligibilityDeclaration {
  readonly targetDomain: ScreeningDomain;
  readonly role: EligibilityRole;
  readonly instrument: ScreeningInstrument;
  readonly screeningLevels: readonly ScreeningLevel[];
  readonly supportTiers: readonly SupportTier[];
}

export interface PublishResourceEligibilityRequest {
  readonly policyVersion: EligibilityPolicyVersion;
  readonly locale: string;
  readonly effectiveAt: string;
  readonly expiresAt: string | null;
  readonly declarations: readonly EligibilityDeclaration[];
}

export interface WithdrawResourceEligibilityRequest {
  readonly policyVersion: EligibilityPolicyVersion;
  readonly reasonCode: 'CONTENT_WITHDRAWN' | 'POLICY_WITHDRAWN' | 'SUPERSEDED';
}

export interface ResourceEligibilityPublication {
  readonly publicationId: string;
  readonly resourceId: string;
  readonly contentVersion: string;
  readonly policyVersion: EligibilityPolicyVersion;
  readonly locale: string;
  readonly state: EligibilityPublicationState;
  readonly effectiveAt: string;
  readonly expiresAt: string | null;
  readonly declarations: readonly EligibilityDeclaration[];
  readonly publishedAt: string;
  readonly withdrawnAt: string | null;
  readonly withdrawalReasonCode: WithdrawResourceEligibilityRequest['reasonCode'] | null;
}

export interface ResourceEligibilityQuery {
  readonly requestId: string;
  readonly resourceId: string;
  readonly contentVersion: string;
  readonly targetDomain: ScreeningDomain;
  readonly requiredRole: RequiredEligibilityRole;
  readonly instrument: ScreeningInstrument;
  readonly screeningLevel: ScreeningLevel;
  readonly supportTier: SupportTier;
  readonly locale: string;
}

export interface ResourceEligibilityBatchRequest {
  readonly requests: readonly ResourceEligibilityQuery[];
}

export interface ResourceEligibilityResult {
  readonly requestId: string;
  readonly resourceId: string;
  readonly contentVersion: string;
  readonly outcome: ResourceEligibilityOutcome;
  readonly reasonCode: ResourceEligibilityReasonCode;
  readonly role: EligibilityRole | null;
  readonly publicationId: string | null;
}

export interface ResourceEligibilityBatchResponse {
  readonly policyVersion: EligibilityPolicyVersion;
  readonly resolvedAt: string;
  readonly results: readonly ResourceEligibilityResult[];
}
