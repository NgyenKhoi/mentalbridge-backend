import {
  BadRequestException,
  Controller,
  ForbiddenException,
  HttpCode,
  Inject,
  Injectable,
  Module,
  Post,
  Req,
  ServiceUnavailableException,
  UnauthorizedException,
  type DynamicModule,
  type Provider,
} from "@nestjs/common";
import { z } from "zod";

import { CareConsentClient, type ConsentClient } from "../analysis/analysis.js";
import type { ServiceConfiguration } from "../configuration/configuration.js";
import {
  RoutedConsultationBriefDraftProvider,
  type ConsultationBriefDraftProvider,
} from "../llm-providers/llm-providers.js";
import {
  ConsultationEntitlementClient,
  VersionedConsultationBriefDraftModelRouter,
  type ConsultationBriefDraftModelRouter,
  type EntitlementClient,
} from "../model-routing/model-routing.js";
import { normalizedConsultationBriefDraftSchema } from "../prompts/consultation-brief-draft.js";
import type { AuthenticatedRequest } from "../security/authenticated-principal.js";

const CONSENT_CLIENT = Symbol("CONSULTATION_BRIEF_DRAFT_CONSENT_CLIENT");
const ENTITLEMENT_CLIENT = Symbol(
  "CONSULTATION_BRIEF_DRAFT_ENTITLEMENT_CLIENT",
);
const MODEL_ROUTER = Symbol("CONSULTATION_BRIEF_DRAFT_MODEL_ROUTER");
const PROVIDER = Symbol("CONSULTATION_BRIEF_DRAFT_PROVIDER");

const screeningContextSchema = z
  .object({
    instrument: z.enum(["PHQ9", "GAD7"]),
    domain: z.enum(["DEPRESSIVE_SYMPTOMS", "ANXIETY_SYMPTOMS"]),
    screeningLevel: z.enum([
      "MINIMAL",
      "MILD",
      "MODERATE",
      "MODERATELY_SEVERE",
      "SEVERE",
    ]),
    questionnaireVersion: z.string().trim().min(1).max(32),
    scoringVersion: z.string().trim().min(1).max(32),
    evaluatedAt: z.iso.datetime({ offset: true }),
    policyVersion: z.string().trim().min(1).max(64),
  })
  .strict();

const requestSchema = z
  .object({
    appointmentId: z.uuid(),
    consultationBriefId: z.uuid(),
    consultationBriefVersion: z.number().int().min(0),
    supportEvaluationId: z.uuid(),
    currentSituation: z.string().trim().min(1).max(1_000),
    userGoals: z.array(z.string().trim().min(1).max(200)).min(1).max(5),
    screeningContext: z
      .array(screeningContextSchema)
      .length(2)
      .refine(
        (items) =>
          new Set(items.map((item) => item.instrument)).size === 2 &&
          items.some((item) => item.instrument === "PHQ9") &&
          items.some((item) => item.instrument === "GAD7"),
      ),
    sourceSetVersion: z.literal("consultation-brief-ai-source-v1"),
  })
  .strict();

interface DraftRequest extends AuthenticatedRequest {
  readonly id?: string;
  readonly body?: unknown;
}

export interface ConsultationBriefDraftDependencies {
  readonly consentClient?: ConsentClient;
  readonly entitlementClient?: EntitlementClient;
  readonly router?: ConsultationBriefDraftModelRouter;
  readonly provider?: ConsultationBriefDraftProvider;
}

@Injectable()
export class ConsultationBriefDraftService {
  constructor(
    @Inject(CONSENT_CLIENT) private readonly consent: ConsentClient,
    @Inject(ENTITLEMENT_CLIENT)
    private readonly entitlement: EntitlementClient,
    @Inject(MODEL_ROUTER)
    private readonly router: ConsultationBriefDraftModelRouter,
    @Inject(PROVIDER)
    private readonly provider: ConsultationBriefDraftProvider,
  ) {}

  async draft(request: DraftRequest) {
    if (!request.principal?.roles.includes("USER"))
      throw new UnauthorizedException();
    const parsed = requestSchema.safeParse(request.body);
    if (!parsed.success) throw new BadRequestException();
    const authorization = request.headers.authorization;
    if (
      typeof authorization !== "string" ||
      !authorization.startsWith("Bearer ")
    )
      throw new UnauthorizedException();
    const bearer = authorization.slice("Bearer ".length);
    const correlationId = request.id ?? crypto.randomUUID();
    let consent;
    try {
      consent = await this.consent.check(bearer, correlationId);
    } catch {
      throw new ServiceUnavailableException();
    }
    if (!consent.authorized) throw new ForbiddenException();
    let route;
    try {
      route = this.router.route(
        await this.entitlement.current(bearer, correlationId),
      );
    } catch {
      throw new ServiceUnavailableException();
    }
    try {
      const generated = await this.provider.draft(
        {
          currentSituation: parsed.data.currentSituation,
          userGoals: parsed.data.userGoals,
          screeningContext: parsed.data.screeningContext.map((item) => ({
            instrument: item.instrument,
            screeningLevel: item.screeningLevel,
          })),
        },
        route,
      );
      const result = normalizedConsultationBriefDraftSchema.safeParse(
        generated.output,
      );
      if (!result.success) throw new Error("invalid provider output");
      return {
        ...result.data,
        sourceSetVersion: parsed.data.sourceSetVersion,
        consentPolicyVersion: consent.policyVersion,
        servicePlan: route.servicePlan,
        entitlementSource: route.entitlementSource,
        entitlementPolicyVersion: route.entitlementPolicyVersion,
        entitlementVersion: route.entitlementVersion,
        routingPolicyVersion: route.routingPolicyVersion,
        providerApprovalVersion: route.providerApprovalVersion,
        provider: route.provider,
        model: route.model,
        promptVersion: route.promptVersion,
        schemaVersion: 1 as const,
        latencyMs: generated.latencyMs,
        inputTokens: generated.usage.inputTokens,
        outputTokens: generated.usage.outputTokens,
        estimatedCostMicroUsd: generated.usage.estimatedCostMicroUsd,
      };
    } catch {
      throw new ServiceUnavailableException();
    }
  }
}

@Controller("internal/v1/consultation-brief-drafts")
export class ConsultationBriefDraftController {
  constructor(private readonly service: ConsultationBriefDraftService) {}

  @Post()
  @HttpCode(200)
  draft(@Req() request: DraftRequest) {
    return this.service.draft(request);
  }
}

export const registerConsultationBriefDraftModule = (
  configuration: ServiceConfiguration,
  dependencies: ConsultationBriefDraftDependencies = {},
): DynamicModule => ({
  module: ConsultationBriefDraftModule,
  controllers: [ConsultationBriefDraftController],
  providers: [
    dependencies.consentClient
      ? { provide: CONSENT_CLIENT, useValue: dependencies.consentClient }
      : {
          provide: CONSENT_CLIENT,
          useFactory: () => new CareConsentClient(configuration),
        },
    dependencies.entitlementClient
      ? {
          provide: ENTITLEMENT_CLIENT,
          useValue: dependencies.entitlementClient,
        }
      : {
          provide: ENTITLEMENT_CLIENT,
          useFactory: () => new ConsultationEntitlementClient(configuration),
        },
    dependencies.router
      ? { provide: MODEL_ROUTER, useValue: dependencies.router }
      : {
          provide: MODEL_ROUTER,
          useFactory: () =>
            new VersionedConsultationBriefDraftModelRouter(configuration),
        },
    dependencies.provider
      ? { provide: PROVIDER, useValue: dependencies.provider }
      : {
          provide: PROVIDER,
          useFactory: () =>
            new RoutedConsultationBriefDraftProvider(configuration),
        },
    {
      provide: ConsultationBriefDraftService,
      useFactory: (
        consent: ConsentClient,
        entitlement: EntitlementClient,
        router: ConsultationBriefDraftModelRouter,
        provider: ConsultationBriefDraftProvider,
      ) =>
        new ConsultationBriefDraftService(
          consent,
          entitlement,
          router,
          provider,
        ),
      inject: [CONSENT_CLIENT, ENTITLEMENT_CLIENT, MODEL_ROUTER, PROVIDER],
    },
  ] satisfies Provider[],
});

@Module({})
export class ConsultationBriefDraftModule {
  readonly moduleName = "consultation-brief-draft";
}
