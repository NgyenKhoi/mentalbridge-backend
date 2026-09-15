import SwaggerParser from '@apidevtools/swagger-parser';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname } from 'node:path';
import { format } from 'prettier';

const contractPath = fileURLToPath(
  new URL('../../contracts/openapi/content-notification-service.yaml', import.meta.url),
);
const typescriptPath = fileURLToPath(
  new URL('../src/generated/resource-eligibility.contract.ts', import.meta.url),
);
const javaPath = fileURLToPath(
  new URL(
    '../../care-service/src/main/java/com/mentalbridge/care/resourceeligibility/generated/ResourceEligibilityContract.java',
    import.meta.url,
  ),
);

const contract = await SwaggerParser.dereference(contractPath);
const schemas = contract.components.schemas;
const values = (name) => schemas[name]?.enum ?? fail(`${name} must define enum values`);
const quote = (value) => `'${value}'`;
const javaValues = (name) => values(name).join(', ');
const tsValues = (name) => values(name).map(quote).join(', ');

const typescript = await format(
  `export const ELIGIBILITY_POLICY_VERSIONS = [${tsValues('EligibilityPolicyVersion')}] as const;
export const SCREENING_DOMAINS = [${tsValues('ScreeningDomain')}] as const;
export const ELIGIBILITY_ROLES = [${tsValues('EligibilityRole')}] as const;
export const REQUIRED_ELIGIBILITY_ROLES = [${tsValues('RequiredEligibilityRole')}] as const;
export const SCREENING_INSTRUMENTS = [${tsValues('ScreeningInstrument')}] as const;
export const SCREENING_LEVELS = [${tsValues('ScreeningLevel')}] as const;
export const SUPPORT_TIERS = [${tsValues('SupportTier')}] as const;
export const ELIGIBILITY_PUBLICATION_STATES = [${tsValues('EligibilityPublicationState')}] as const;
export const RESOURCE_ELIGIBILITY_OUTCOMES = [${tsValues('ResourceEligibilityOutcome')}] as const;
export const RESOURCE_ELIGIBILITY_REASON_CODES = [${tsValues('ResourceEligibilityReasonCode')}] as const;

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
`,
  { parser: 'typescript', singleQuote: true, trailingComma: 'all', printWidth: 100, semi: true },
);

const java = `package com.mentalbridge.care.resourceeligibility.generated;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

public final class ResourceEligibilityContract {

\tprivate ResourceEligibilityContract() {
\t}

\tpublic enum ScreeningDomain { ${javaValues('ScreeningDomain')} }
\tpublic enum EligibilityRole { ${javaValues('EligibilityRole')} }
\tpublic enum RequiredEligibilityRole { ${javaValues('RequiredEligibilityRole')} }
\tpublic enum ScreeningInstrument { ${javaValues('ScreeningInstrument')} }
\tpublic enum ScreeningLevel { ${javaValues('ScreeningLevel')} }
\tpublic enum SupportTier { ${javaValues('SupportTier')} }
\tpublic enum ResourceEligibilityOutcome { ${javaValues('ResourceEligibilityOutcome')} }
\tpublic enum ResourceEligibilityReasonCode { ${javaValues('ResourceEligibilityReasonCode')} }

\t@JsonIgnoreProperties(ignoreUnknown = true)
\tpublic record ResourceEligibilityQuery(
\t\t\tString requestId,
\t\t\tString resourceId,
\t\t\tString contentVersion,
\t\t\tScreeningDomain targetDomain,
\t\t\tRequiredEligibilityRole requiredRole,
\t\t\tScreeningInstrument instrument,
\t\t\tScreeningLevel screeningLevel,
\t\t\tSupportTier supportTier,
\t\t\tString locale) {
\t}

\t@JsonIgnoreProperties(ignoreUnknown = true)
\tpublic record ResourceEligibilityBatchRequest(List<ResourceEligibilityQuery> requests) {
\t}

\t@JsonIgnoreProperties(ignoreUnknown = true)
\tpublic record ResourceEligibilityResult(
\t\t\tString requestId,
\t\t\tString resourceId,
\t\t\tString contentVersion,
\t\t\tResourceEligibilityOutcome outcome,
\t\t\tResourceEligibilityReasonCode reasonCode,
\t\t\tEligibilityRole role,
\t\t\tString publicationId) {
\t}

\t@JsonIgnoreProperties(ignoreUnknown = true)
\tpublic record ResourceEligibilityBatchResponse(
\t\t\tString policyVersion,
\t\t\tString resolvedAt,
\t\t\tList<ResourceEligibilityResult> results) {
\t}
}
`;

await emit(typescriptPath, typescript);
await emit(javaPath, java);

async function emit(path, content) {
  if (process.argv.includes('--check')) {
    const current = await readFile(path, 'utf8').catch(() => '');
    if (normalizeLineEndings(current) !== normalizeLineEndings(content)) {
      fail(`${path} is not synchronized with the OpenAPI contract`);
    }
    return;
  }
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, content, 'utf8');
}

function normalizeLineEndings(value) {
  return value.replace(/\r\n/g, '\n');
}

function fail(message) {
  throw new Error(message);
}
