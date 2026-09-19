import { z } from "zod";

import type { ServiceConfiguration } from "../configuration/configuration.js";
import { EXACT_REVISION_PROMPT_VERSION } from "../prompts/exact-revision.js";

export type ServicePlan = "FREE" | "PLUS" | "PREMIUM";
export type EntitlementSource = "DEFAULT_FREE" | "DEMO" | "PAID";
export type AiProviderId = "DETERMINISTIC_FAKE" | "GEMINI" | "OPENAI";

export interface EntitlementDecision {
  readonly packageCode: ServicePlan;
  readonly source: EntitlementSource;
  readonly policyVersion: string;
  readonly version: number;
}

export interface AnalysisRoute {
  readonly workload: "EXACT_REVISION";
  readonly servicePlan: ServicePlan;
  readonly entitlementSource: EntitlementSource;
  readonly entitlementPolicyVersion: string;
  readonly entitlementVersion: number;
  readonly routingPolicyVersion: string;
  readonly providerApprovalVersion: string;
  readonly provider: AiProviderId;
  readonly model: string;
  readonly promptVersion: string;
  readonly inputCostMicroUsdPerMillionTokens: number;
  readonly outputCostMicroUsdPerMillionTokens: number;
}

export interface EntitlementClient {
  current(
    bearerToken: string,
    correlationId: string,
  ): Promise<EntitlementDecision>;
}

export interface ModelRouter {
  route(entitlement: EntitlementDecision): AnalysisRoute;
}

const entitlementSchema = z
  .object({
    accountId: z.uuid(),
    packageCode: z.enum(["FREE", "PLUS", "PREMIUM"]),
    source: z.enum(["DEFAULT_FREE", "DEMO", "PAID"]),
    sourceReference: z.string().min(1).max(128).nullable(),
    effectiveFrom: z.iso.datetime({ offset: true }).nullable(),
    effectiveUntil: z.iso.datetime({ offset: true }).nullable(),
    policyVersion: z.literal("service-entitlement-v1"),
    version: z.number().int().min(0),
    decidedAt: z.iso.datetime({ offset: true }),
  })
  .strict();

export class EntitlementUnavailableError extends Error {}

export class ConsultationEntitlementClient implements EntitlementClient {
  constructor(private readonly configuration: ServiceConfiguration) {}

  async current(bearer: string, correlationId: string) {
    let response: Response;
    try {
      response = await fetch(
        new URL(
          "/internal/v1/entitlements/current",
          this.configuration.CONSULTATION_BASE_URL,
        ),
        {
          headers: {
            authorization: `Bearer ${bearer}`,
            "x-correlation-id": correlationId,
          },
          signal: AbortSignal.timeout(
            this.configuration.CONSULTATION_TIMEOUT_MS,
          ),
        },
      );
    } catch (error) {
      throw new EntitlementUnavailableError(
        "Consultation entitlement is unavailable",
        { cause: error },
      );
    }
    if (!response.ok)
      throw new EntitlementUnavailableError(
        "Consultation entitlement is unavailable",
      );
    let value: unknown;
    try {
      value = await response.json();
    } catch (error) {
      throw new EntitlementUnavailableError(
        "Consultation entitlement response is invalid",
        { cause: error },
      );
    }
    const parsed = entitlementSchema.safeParse(value);
    if (!parsed.success)
      throw new EntitlementUnavailableError(
        "Consultation entitlement response is invalid",
      );
    return {
      packageCode: parsed.data.packageCode,
      source: parsed.data.source,
      policyVersion: parsed.data.policyVersion,
      version: parsed.data.version,
    };
  }
}

export class VersionedModelRouter implements ModelRouter {
  constructor(private readonly configuration: ServiceConfiguration) {}

  route(entitlement: EntitlementDecision): AnalysisRoute {
    if (this.configuration.PROVIDER_MODE === "DETERMINISTIC_FAKE")
      return this.build(
        entitlement,
        "DETERMINISTIC_FAKE",
        "deterministic-reflection-v1",
        "local-deterministic-v1",
        0,
        0,
      );
    const configured =
      entitlement.packageCode === "PREMIUM"
        ? this.configuration.PREMIUM_ROUTE
        : this.configuration.FREE_PLUS_ROUTE;
    if (!configured || !this.configuration.PROVIDER_APPROVAL_VERSION)
      throw new EntitlementUnavailableError(
        "Approved model route is unavailable",
      );
    return this.build(
      entitlement,
      configured.provider,
      configured.model,
      this.configuration.PROVIDER_APPROVAL_VERSION,
      configured.inputCostMicroUsdPerMillionTokens,
      configured.outputCostMicroUsdPerMillionTokens,
    );
  }

  private build(
    entitlement: EntitlementDecision,
    provider: AiProviderId,
    model: string,
    providerApprovalVersion: string,
    inputCostMicroUsdPerMillionTokens: number,
    outputCostMicroUsdPerMillionTokens: number,
  ): AnalysisRoute {
    return {
      workload: "EXACT_REVISION",
      servicePlan: entitlement.packageCode,
      entitlementSource: entitlement.source,
      entitlementPolicyVersion: entitlement.policyVersion,
      entitlementVersion: entitlement.version,
      routingPolicyVersion: this.configuration.ROUTING_POLICY_VERSION,
      providerApprovalVersion,
      provider,
      model,
      promptVersion: EXACT_REVISION_PROMPT_VERSION,
      inputCostMicroUsdPerMillionTokens,
      outputCostMicroUsdPerMillionTokens,
    };
  }
}

export const sameProviderRoute = (
  first: AnalysisRoute,
  second: AnalysisRoute,
): boolean =>
  first.routingPolicyVersion === second.routingPolicyVersion &&
  first.providerApprovalVersion === second.providerApprovalVersion &&
  first.provider === second.provider &&
  first.model === second.model &&
  first.promptVersion === second.promptVersion;
