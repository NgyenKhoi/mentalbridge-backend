import {
  BadRequestException,
  Controller,
  Inject,
  Injectable,
  Module,
  Post,
  Req,
  UnauthorizedException,
  type DynamicModule,
  type Provider,
} from "@nestjs/common";
import type { Logger } from "pino";
import { z } from "zod";

import { CareConsentClient, type ConsentClient } from "../analysis/analysis.js";
import type { ServiceConfiguration } from "../configuration/configuration.js";
import {
  ProviderFailure,
  RoutedSupportPlanCompanionProvider,
  type SupportPlanCompanionProvider,
} from "../llm-providers/llm-providers.js";
import { createLogger } from "../observability/logger.js";
import {
  normalizedSupportPlanCompanionOutputSchema,
  SUPPORT_PLAN_COMPANION_PROMPT_VERSION,
} from "../prompts/support-plan-companion.js";
import type { AuthenticatedRequest } from "../security/authenticated-principal.js";

const CONSENT_CLIENT = Symbol("SUPPORT_PLAN_COMPANION_CONSENT_CLIENT");
const PROVIDER = Symbol("SUPPORT_PLAN_COMPANION_PROVIDER");
const TELEMETRY = Symbol("SUPPORT_PLAN_COMPANION_TELEMETRY");

export const SUPPORT_PLAN_COMPANION_CONTRACT_VERSION =
  "support-plan-companion-intent-v1";
export const SUPPORT_PLAN_COMPANION_CONTEXT_VERSION =
  "support-plan-companion-context-v1";
export const SUPPORT_PLAN_COMPANION_POLICY_VERSION =
  "support-plan-companion-policy-v1";
export const SUPPORT_PLAN_COMPANION_MESSAGE_VERSION =
  "support-plan-companion-message-v1";
export const SUPPORT_PLAN_COMPANION_TEMPLATE_VERSION =
  "support-plan-companion-template-v1";

const surfaces = ["TODAY", "PLAN", "PROGRESS", "ADJUST"] as const;
const intents = [
  "EXPLAIN_ACTION",
  "EXPLAIN_FOCUS",
  "SUGGEST_NEXT_STEP",
  "ENCOURAGE",
  "SUMMARIZE_PROGRESS",
  "EXPLAIN_REVIEW_OPTION",
  "NAVIGATE",
] as const;
const actionTypes = [
  "RESOURCE",
  "JOURNAL",
  "EMOTION_CHECK_IN",
  "ROUTINE_SELF_MANAGEMENT",
  "PROFESSIONAL_SUPPORT",
  "REASSESSMENT_SCREENING",
  "SELF_REPORT_REVIEW",
] as const;
const dueStates = [
  "DUE_TODAY",
  "UPCOMING",
  "OVERDUE",
  "UNSCHEDULED",
  "COMPLETED",
  "SKIPPED",
] as const;
const destinations = [
  "TODAY",
  "PLAN",
  "PROGRESS",
  "ADJUST",
  "ACTION_DETAIL",
  "RESOURCE",
  "JOURNAL",
  "EMOTION_CHECK_IN",
  "SPECIALIST_DISCOVERY",
  "REASSESSMENT",
] as const;

const boundedTitle = z.string().trim().min(1).max(160);
const boundedRationale = z.string().trim().min(1).max(320);
const navigationSchema = z
  .object({
    destination: z.enum(destinations),
    label: boundedTitle,
  })
  .strict();
const contextBase = {
  schemaVersion: z.literal(SUPPORT_PLAN_COMPANION_CONTEXT_VERSION),
  locale: z.literal("vi-VN"),
};

const actionContextSchema = z
  .object({
    ...contextBase,
    contextKind: z.literal("ACTION"),
    action: z
      .object({
        actionType: z.enum(actionTypes),
        title: boundedTitle,
        rationale: boundedRationale.optional(),
        availability: z.enum([
          "AVAILABLE",
          "UNAVAILABLE",
          "SOURCE_UNAVAILABLE",
        ]),
        dueState: z.enum(dueStates).optional(),
        navigation: navigationSchema.optional(),
      })
      .strict(),
  })
  .strict();

const focusContextSchema = z
  .object({
    ...contextBase,
    contextKind: z.literal("FOCUS"),
    focus: z
      .object({
        title: z.string().trim().min(1).max(120),
        rationale: boundedRationale.optional(),
        navigation: navigationSchema.optional(),
      })
      .strict(),
  })
  .strict();

const nextStepContextSchema = z
  .object({
    ...contextBase,
    contextKind: z.literal("NEXT_STEP"),
    nextStep: z
      .object({
        label: boundedTitle,
        destination: z.enum(destinations),
        actionType: z.enum(actionTypes).optional(),
        dueState: z.enum(dueStates).optional(),
      })
      .strict(),
  })
  .strict();

const acknowledgementContextSchema = z
  .object({
    ...contextBase,
    contextKind: z.literal("ACKNOWLEDGEMENT"),
    acknowledgement: z
      .object({
        reason: z.enum([
          "ACTION_SAVED",
          "EXPLICIT_COMPLETION_SAVED",
          "REVIEW_SUBMITTED",
          "PREFERENCE_SAVED",
        ]),
        title: boundedTitle.optional(),
      })
      .strict(),
  })
  .strict();

const progressContextSchema = z
  .object({
    ...contextBase,
    contextKind: z.literal("PROGRESS"),
    progress: z
      .object({
        period: z.enum(["TODAY", "SEVEN_DAYS", "PLAN"]),
        completedCount: z.number().int().min(0).max(10_000),
        availableCount: z.number().int().min(0).max(10_000),
        evidenceAsOf: z
          .string()
          .regex(
            /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/,
          ),
        partial: z.boolean(),
        unavailableEvidence: z
          .array(
            z.enum([
              "CARE_ACTIONS",
              "CONTENT_RESOURCE_PROGRESS",
              "JOURNAL_ACTION_EVIDENCE",
              "EMOTION_CHECK_IN_EVIDENCE",
              "CONSULTATION_EVIDENCE",
            ]),
          )
          .max(5)
          .refine((values) => new Set(values).size === values.length)
          .optional(),
      })
      .strict()
      .superRefine((progress, context) => {
        if (progress.completedCount > progress.availableCount)
          context.addIssue({
            code: "custom",
            path: ["completedCount"],
            message: "completedCount must not exceed availableCount",
          });
        const unavailableCount = progress.unavailableEvidence?.length ?? 0;
        if (progress.partial && unavailableCount === 0)
          context.addIssue({
            code: "custom",
            path: ["unavailableEvidence"],
            message: "partial progress requires unavailable evidence",
          });
        if (!progress.partial && unavailableCount > 0)
          context.addIssue({
            code: "custom",
            path: ["unavailableEvidence"],
            message: "complete progress cannot include unavailable evidence",
          });
      }),
  })
  .strict();

const reviewDimensionCodes = [
  "SCREENING_CHANGE",
  "JOURNAL_CONTEXT",
  "PLAN_ENGAGEMENT",
  "SELF_REPORTED_EXPERIENCE",
] as const;
const reviewContextSchema = z
  .object({
    ...contextBase,
    contextKind: z.literal("REVIEW"),
    review: z
      .object({
        outcome: z.enum([
          "KEEP_CURRENT",
          "ALTERNATIVES_AVAILABLE",
          "CURRENT_NOT_ADMISSIBLE",
        ]),
        dimensions: z
          .array(
            z
              .object({
                code: z.enum(reviewDimensionCodes),
                availability: z.enum([
                  "AVAILABLE",
                  "MISSING",
                  "UNAVAILABLE",
                  "NOT_APPLICABLE",
                ]),
                direction: z.enum([
                  "INCREASED",
                  "STABLE",
                  "DECREASED",
                  "MIXED",
                  "UNKNOWN",
                ]),
              })
              .strict(),
          )
          .length(4)
          .superRefine((dimensions, context) => {
            const codes = new Set(
              dimensions.map((dimension) => dimension.code),
            );
            if (
              codes.size !== reviewDimensionCodes.length ||
              reviewDimensionCodes.some((code) => !codes.has(code))
            )
              context.addIssue({
                code: "custom",
                message:
                  "review dimensions must contain each approved code once",
              });
          }),
        options: z
          .array(
            z
              .object({
                optionCode: z.enum([
                  "KEEP_CURRENT",
                  "REDUCE_WORKLOAD",
                  "MOVE_TIME",
                  "CHOOSE_ADMITTED_ALTERNATIVE",
                  "CONSIDER_PROFESSIONAL_SUPPORT",
                ]),
                label: boundedTitle,
              })
              .strict(),
          )
          .min(1)
          .max(5),
        barrierPatterns: z
          .array(
            z.enum([
              "TIME",
              "ENERGY",
              "ACCESS",
              "PREFERENCE",
              "OTHER_DECLARED",
            ]),
          )
          .max(5)
          .refine((values) => new Set(values).size === values.length)
          .optional(),
        helpfulnessPatterns: z
          .array(z.enum(["HELPFUL", "NEUTRAL", "NOT_HELPFUL", "NOT_REPORTED"]))
          .max(4)
          .refine((values) => new Set(values).size === values.length)
          .optional(),
        contradictoryEvidence: z.boolean(),
      })
      .strict(),
  })
  .strict();

const navigationContextSchema = z
  .object({
    ...contextBase,
    contextKind: z.literal("NAVIGATION"),
    navigation: z
      .object({
        destination: z.enum(destinations),
        label: boundedTitle,
        sourceActionTitle: boundedTitle.optional(),
      })
      .strict(),
  })
  .strict();

export const supportPlanCompanionContextSchema = z.discriminatedUnion(
  "contextKind",
  [
    actionContextSchema,
    focusContextSchema,
    nextStepContextSchema,
    acknowledgementContextSchema,
    progressContextSchema,
    reviewContextSchema,
    navigationContextSchema,
  ],
);

const allowedIntentsBySurface: Record<
  (typeof surfaces)[number],
  ReadonlySet<(typeof intents)[number]>
> = {
  TODAY: new Set([
    "EXPLAIN_ACTION",
    "SUGGEST_NEXT_STEP",
    "ENCOURAGE",
    "NAVIGATE",
  ]),
  PLAN: new Set(["EXPLAIN_FOCUS", "EXPLAIN_ACTION", "NAVIGATE"]),
  PROGRESS: new Set(["SUMMARIZE_PROGRESS", "ENCOURAGE", "NAVIGATE"]),
  ADJUST: new Set(["EXPLAIN_REVIEW_OPTION", "EXPLAIN_ACTION", "NAVIGATE"]),
} as const;
const contextKindByIntent = {
  EXPLAIN_ACTION: "ACTION",
  EXPLAIN_FOCUS: "FOCUS",
  SUGGEST_NEXT_STEP: "NEXT_STEP",
  ENCOURAGE: "ACKNOWLEDGEMENT",
  SUMMARIZE_PROGRESS: "PROGRESS",
  EXPLAIN_REVIEW_OPTION: "REVIEW",
  NAVIGATE: "NAVIGATION",
} as const;

export const supportPlanCompanionIntentSchema = z
  .object({
    schemaVersion: z.literal(SUPPORT_PLAN_COMPANION_CONTRACT_VERSION),
    policyVersion: z.literal(SUPPORT_PLAN_COMPANION_POLICY_VERSION),
    requestSource: z.literal("USER_INITIATED"),
    surface: z.enum(surfaces),
    intent: z.enum(intents),
    context: supportPlanCompanionContextSchema,
  })
  .strict()
  .superRefine((value, context) => {
    if (!allowedIntentsBySurface[value.surface].has(value.intent))
      context.addIssue({
        code: "custom",
        path: ["intent"],
        message: "intent is not allowed on this surface",
      });
    if (value.context.contextKind !== contextKindByIntent[value.intent])
      context.addIssue({
        code: "custom",
        path: ["context", "contextKind"],
        message: "context does not match the selected intent",
      });
  });

export type SupportPlanCompanionIntent = z.infer<
  typeof supportPlanCompanionIntentSchema
>;
type CompanionContext = SupportPlanCompanionIntent["context"];
type CompanionNavigation = z.infer<typeof navigationSchema>;

export type CompanionFallbackReason =
  | "CONSENT_REQUIRED"
  | "CONSENT_UNAVAILABLE"
  | "PROVIDER_DISABLED"
  | "PROVIDER_TIMEOUT"
  | "PROVIDER_QUOTA_LIMITED"
  | "PROVIDER_UNAVAILABLE"
  | "MALFORMED_OUTPUT"
  | "POLICY_REJECTED";

export interface CompanionTelemetryEvent {
  readonly correlationId: string;
  readonly contractVersion: typeof SUPPORT_PLAN_COMPANION_CONTRACT_VERSION;
  readonly policyVersion: typeof SUPPORT_PLAN_COMPANION_POLICY_VERSION;
  readonly promptVersion: typeof SUPPORT_PLAN_COMPANION_PROMPT_VERSION;
  readonly templateVersion: typeof SUPPORT_PLAN_COMPANION_TEMPLATE_VERSION;
  readonly intent: SupportPlanCompanionIntent["intent"];
  readonly surface: SupportPlanCompanionIntent["surface"];
  readonly provider: string | null;
  readonly model: string | null;
  readonly outcome: "GENERATED" | "FALLBACK";
  readonly fallbackReason: CompanionFallbackReason | null;
  readonly latencyMs: number;
}

export interface CompanionTelemetry {
  record(event: CompanionTelemetryEvent): void;
}

class LoggerCompanionTelemetry implements CompanionTelemetry {
  constructor(private readonly logger: Logger) {}

  record(event: CompanionTelemetryEvent): void {
    this.logger.info(event, "SupportPlan companion request completed");
  }
}

interface CompanionRequest extends AuthenticatedRequest {
  readonly id?: string;
  readonly body?: unknown;
}

export interface SupportPlanCompanionDependencies {
  readonly consentClient?: ConsentClient;
  readonly provider?: SupportPlanCompanionProvider;
  readonly telemetry?: CompanionTelemetry;
}

const navigationFromContext = (
  context: CompanionContext,
): CompanionNavigation | null => {
  if (context.contextKind === "ACTION")
    return context.action.navigation ?? null;
  if (context.contextKind === "FOCUS") return context.focus.navigation ?? null;
  if (context.contextKind === "NEXT_STEP")
    return {
      destination: context.nextStep.destination,
      label: context.nextStep.label,
    };
  if (context.contextKind === "NAVIGATION") return context.navigation;
  return null;
};

const fallbackText = (request: SupportPlanCompanionIntent): string => {
  const context = request.context;
  if (context.contextKind === "ACTION") {
    if (context.action.availability !== "AVAILABLE")
      return `Thông tin cho “${context.action.title}” hiện chưa sẵn sàng. Bạn có thể thử lại sau.`;
    return `${context.action.title} là một hoạt động trong kế hoạch của bạn.${context.action.rationale ? ` ${context.action.rationale}` : ""}`;
  }
  if (context.contextKind === "FOCUS")
    return `Nhóm này tập trung vào “${context.focus.title}”.${context.focus.rationale ? ` ${context.focus.rationale}` : ""}`;
  if (context.contextKind === "NEXT_STEP")
    return `Bước tiếp theo đang có sẵn: ${context.nextStep.label}.`;
  if (context.contextKind === "ACKNOWLEDGEMENT") {
    const messages = {
      ACTION_SAVED: "Thay đổi của bạn đã được lưu.",
      EXPLICIT_COMPLETION_SAVED: "Hoạt động đã được đánh dấu hoàn thành.",
      REVIEW_SUBMITTED: "Phản hồi xem lại kế hoạch đã được gửi.",
      PREFERENCE_SAVED: "Lựa chọn của bạn đã được lưu.",
    } as const;
    return messages[context.acknowledgement.reason];
  }
  if (context.contextKind === "PROGRESS") {
    const base = `Tính đến ${context.progress.evidenceAsOf}, bạn đã hoàn thành ${String(context.progress.completedCount)} trong ${String(context.progress.availableCount)} hoạt động có dữ liệu cho khoảng thời gian này.`;
    return context.progress.partial
      ? `${base} Một số nguồn hiện chưa có dữ liệu, nên đây là bản tóm tắt chưa đầy đủ.`
      : base;
  }
  if (context.contextKind === "REVIEW") {
    const base = `Kết quả xem lại hiện có ${String(context.review.options.length)} lựa chọn phù hợp để bạn tự quyết định.`;
    return context.review.contradictoryEvidence
      ? `${base} Các nguồn hiện chưa cùng hướng, nên hệ thống giữ riêng từng nguồn để bạn xem xét.`
      : base;
  }
  return `Bạn có thể tiếp tục tại “${context.navigation.label}”.`;
};

const normalized = (value: string) =>
  value.normalize("NFC").toLocaleLowerCase("vi-VN");
const policyViolationPattern =
  /https?:\/\/|www\.|<[^>]+>|\[[^\]]+\]\([^)]+\)|chẩn đoán|điều trị|kê đơn|nhà trị liệu|bác sĩ|tuân thủ|hồi phục|khỏi bệnh|an toàn tuyệt đối|chắc chắn sẽ|đã tiến bộ|đang bị|(tôi|mình)\s+đã\s+(thay đổi|cập nhật|đánh dấu|lên lịch|gửi|đặt lịch)|system prompt|developer message|chain of thought|provider|policy version/iu;
const hasControlCharacters = (value: string) => {
  for (const character of value) {
    const code = character.codePointAt(0) ?? 0;
    if (
      code <= 8 ||
      code === 11 ||
      code === 12 ||
      (code >= 14 && code <= 31) ||
      code === 127
    )
      return true;
  }
  return false;
};

const requiredGrounding = (
  request: SupportPlanCompanionIntent,
): readonly string[] => {
  const context = request.context;
  if (context.contextKind === "ACTION") return [context.action.title];
  if (context.contextKind === "FOCUS") return [context.focus.title];
  if (context.contextKind === "NEXT_STEP") return [context.nextStep.label];
  if (context.contextKind === "PROGRESS")
    return [
      String(context.progress.completedCount),
      String(context.progress.availableCount),
    ];
  if (context.contextKind === "REVIEW")
    return [String(context.review.options.length)];
  if (context.contextKind === "NAVIGATION") return [context.navigation.label];
  return [];
};

const isPolicyCompliantOutput = (
  request: SupportPlanCompanionIntent,
  text: string,
): boolean => {
  if (policyViolationPattern.test(text) || hasControlCharacters(text))
    return false;
  if (text.split(/[.!?]+/u).filter((sentence) => sentence.trim()).length > 3)
    return false;
  const output = normalized(text);
  if (
    requiredGrounding(request).some(
      (fact) => !output.includes(normalized(fact)),
    )
  )
    return false;
  if (
    /hoàn thành/iu.test(text) &&
    request.intent !== "SUMMARIZE_PROGRESS" &&
    !(
      request.context.contextKind === "ACKNOWLEDGEMENT" &&
      request.context.acknowledgement.reason === "EXPLICIT_COMPLETION_SAVED"
    ) &&
    !(
      request.context.contextKind === "ACTION" &&
      request.context.action.dueState === "COMPLETED"
    )
  )
    return false;
  return true;
};

const fallbackReasonFor = (error: unknown): CompanionFallbackReason => {
  if (!(error instanceof ProviderFailure)) return "PROVIDER_UNAVAILABLE";
  if (error.reason === "TIMEOUT") return "PROVIDER_TIMEOUT";
  if (
    error.diagnostics.httpStatus === 429 ||
    (error.diagnostics.quotaIds?.length ?? 0) > 0 ||
    (error.diagnostics.quotaMetrics?.length ?? 0) > 0
  )
    return "PROVIDER_QUOTA_LIMITED";
  return error.reason === "INVALID_RESULT"
    ? "MALFORMED_OUTPUT"
    : "PROVIDER_UNAVAILABLE";
};

@Injectable()
export class SupportPlanCompanionService {
  constructor(
    @Inject(CONSENT_CLIENT) private readonly consent: ConsentClient,
    @Inject(PROVIDER) private readonly provider: SupportPlanCompanionProvider,
    @Inject(TELEMETRY) private readonly telemetry: CompanionTelemetry,
    private readonly configuration: ServiceConfiguration,
  ) {}

  async message(httpRequest: CompanionRequest) {
    if (!httpRequest.principal?.roles.includes("USER"))
      throw new UnauthorizedException();
    const parsed = supportPlanCompanionIntentSchema.safeParse(httpRequest.body);
    if (!parsed.success) throw new BadRequestException();
    const authorization = httpRequest.headers.authorization;
    if (
      typeof authorization !== "string" ||
      !authorization.startsWith("Bearer ")
    )
      throw new UnauthorizedException();

    const request = parsed.data;
    const correlationId = httpRequest.id ?? crypto.randomUUID();
    const startedAt = performance.now();
    const route = this.configuration.FREE_PLUS_ROUTE;
    const provider =
      this.configuration.PROVIDER_MODE === "DETERMINISTIC_FAKE"
        ? "DETERMINISTIC_FAKE"
        : (route?.provider ?? null);
    const model =
      this.configuration.PROVIDER_MODE === "DETERMINISTIC_FAKE"
        ? "deterministic-support-plan-companion-v1"
        : (route?.model ?? null);

    const fallback = (reason: CompanionFallbackReason) => {
      const response = {
        schemaVersion: SUPPORT_PLAN_COMPANION_MESSAGE_VERSION,
        intent: request.intent,
        surface: request.surface,
        text: fallbackText(request),
        delivery: "TEMPLATE" as const,
        navigation: navigationFromContext(request.context),
        policyVersion: SUPPORT_PLAN_COMPANION_POLICY_VERSION,
        promptVersion: SUPPORT_PLAN_COMPANION_PROMPT_VERSION,
        templateVersion: SUPPORT_PLAN_COMPANION_TEMPLATE_VERSION,
        provider,
        model,
        fallbackReason: reason,
      };
      this.telemetry.record({
        correlationId,
        contractVersion: SUPPORT_PLAN_COMPANION_CONTRACT_VERSION,
        policyVersion: SUPPORT_PLAN_COMPANION_POLICY_VERSION,
        promptVersion: SUPPORT_PLAN_COMPANION_PROMPT_VERSION,
        templateVersion: SUPPORT_PLAN_COMPANION_TEMPLATE_VERSION,
        intent: request.intent,
        surface: request.surface,
        provider,
        model,
        outcome: "FALLBACK",
        fallbackReason: reason,
        latencyMs: Math.max(0, Math.round(performance.now() - startedAt)),
      });
      return response;
    };

    if (this.configuration.PROVIDER_MODE === "DETERMINISTIC_FAKE")
      return fallback("PROVIDER_DISABLED");

    let consent;
    try {
      consent = await this.consent.check(
        authorization.slice("Bearer ".length),
        correlationId,
      );
    } catch {
      return fallback("CONSENT_UNAVAILABLE");
    }
    if (!consent.authorized) return fallback("CONSENT_REQUIRED");

    let analysis;
    try {
      analysis = await this.provider.phrase(request);
    } catch (error) {
      return fallback(fallbackReasonFor(error));
    }
    const output = normalizedSupportPlanCompanionOutputSchema.safeParse(
      analysis.output,
    );
    if (!output.success) return fallback("MALFORMED_OUTPUT");
    if (!isPolicyCompliantOutput(request, output.data.text))
      return fallback("POLICY_REJECTED");

    const response = {
      schemaVersion: SUPPORT_PLAN_COMPANION_MESSAGE_VERSION,
      intent: request.intent,
      surface: request.surface,
      text: output.data.text,
      delivery: "GENERATED" as const,
      navigation: navigationFromContext(request.context),
      policyVersion: SUPPORT_PLAN_COMPANION_POLICY_VERSION,
      promptVersion: SUPPORT_PLAN_COMPANION_PROMPT_VERSION,
      templateVersion: SUPPORT_PLAN_COMPANION_TEMPLATE_VERSION,
      provider,
      model,
      fallbackReason: null,
    };
    this.telemetry.record({
      correlationId,
      contractVersion: SUPPORT_PLAN_COMPANION_CONTRACT_VERSION,
      policyVersion: SUPPORT_PLAN_COMPANION_POLICY_VERSION,
      promptVersion: SUPPORT_PLAN_COMPANION_PROMPT_VERSION,
      templateVersion: SUPPORT_PLAN_COMPANION_TEMPLATE_VERSION,
      intent: request.intent,
      surface: request.surface,
      provider,
      model,
      outcome: "GENERATED",
      fallbackReason: null,
      latencyMs: Math.max(0, Math.round(performance.now() - startedAt)),
    });
    return response;
  }
}

@Controller("internal/v1/support-plan-companion/messages")
export class SupportPlanCompanionController {
  constructor(private readonly service: SupportPlanCompanionService) {}

  @Post()
  message(@Req() request: CompanionRequest) {
    return this.service.message(request);
  }
}

export const registerSupportPlanCompanionModule = (
  configuration: ServiceConfiguration,
  dependencies: SupportPlanCompanionDependencies = {},
): DynamicModule => {
  const providers: Provider[] = [
    dependencies.consentClient
      ? { provide: CONSENT_CLIENT, useValue: dependencies.consentClient }
      : {
          provide: CONSENT_CLIENT,
          useFactory: () => new CareConsentClient(configuration),
        },
    dependencies.provider
      ? { provide: PROVIDER, useValue: dependencies.provider }
      : {
          provide: PROVIDER,
          useFactory: () =>
            new RoutedSupportPlanCompanionProvider(configuration),
        },
    {
      provide: TELEMETRY,
      useValue:
        dependencies.telemetry ??
        new LoggerCompanionTelemetry(createLogger(configuration)),
    },
    {
      provide: SupportPlanCompanionService,
      useFactory: (
        consent: ConsentClient,
        provider: SupportPlanCompanionProvider,
        telemetry: CompanionTelemetry,
      ) =>
        new SupportPlanCompanionService(
          consent,
          provider,
          telemetry,
          configuration,
        ),
      inject: [CONSENT_CLIENT, PROVIDER, TELEMETRY],
    },
  ];
  return {
    module: SupportPlanCompanionModule,
    controllers: [SupportPlanCompanionController],
    providers,
  };
};

@Module({})
export class SupportPlanCompanionModule {
  readonly moduleName = "support-plan-companion";
}
