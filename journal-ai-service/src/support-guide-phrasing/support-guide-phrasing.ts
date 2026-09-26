import {
  BadRequestException,
  Controller,
  ForbiddenException,
  Inject,
  Injectable,
  Module,
  Post,
  Req,
  ServiceUnavailableException,
  UnauthorizedException,
  type Provider,
  type DynamicModule,
} from "@nestjs/common";
import { z } from "zod";

import { CareConsentClient, type ConsentClient } from "../analysis/analysis.js";
import type { ServiceConfiguration } from "../configuration/configuration.js";
import {
  RoutedSupportGuidePhrasingProvider,
  type SupportGuidePhrasingProvider,
} from "../llm-providers/llm-providers.js";
import { SUPPORT_GUIDE_PHRASING_PROMPT_VERSION } from "../prompts/support-guide-phrasing.js";
import type { AuthenticatedRequest } from "../security/authenticated-principal.js";

const CONSENT_CLIENT = Symbol("SUPPORT_GUIDE_CONSENT_CLIENT");
const PROVIDER = Symbol("SUPPORT_GUIDE_PHRASING_PROVIDER");

const requestSchema = z
  .object({
    approvedText: z.string().trim().min(1).max(1_600),
    locale: z.literal("vi-VN"),
  })
  .strict();

interface PhrasingRequest extends AuthenticatedRequest {
  readonly id?: string;
  readonly body?: unknown;
}

export interface SupportGuidePhrasingDependencies {
  readonly consentClient?: ConsentClient;
  readonly provider?: SupportGuidePhrasingProvider;
}

@Injectable()
export class SupportGuidePhrasingService {
  constructor(
    @Inject(CONSENT_CLIENT) private readonly consent: ConsentClient,
    @Inject(PROVIDER) private readonly provider: SupportGuidePhrasingProvider,
    private readonly configuration: ServiceConfiguration,
  ) {}

  async phrase(request: PhrasingRequest) {
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
    let consent;
    try {
      consent = await this.consent.check(
        authorization.slice("Bearer ".length),
        request.id ?? crypto.randomUUID(),
      );
    } catch {
      throw new ServiceUnavailableException();
    }
    if (!consent.authorized) throw new ForbiddenException();
    try {
      const analysis = await this.provider.phrase(parsed.data.approvedText);
      const output = z
        .object({ text: z.string().trim().min(1).max(1_600) })
        .strict()
        .parse(analysis.output);
      const route = this.configuration.FREE_PLUS_ROUTE;
      return {
        text: output.text,
        provider:
          this.configuration.PROVIDER_MODE === "DETERMINISTIC_FAKE"
            ? "DETERMINISTIC_FAKE"
            : route?.provider,
        model:
          this.configuration.PROVIDER_MODE === "DETERMINISTIC_FAKE"
            ? "deterministic-support-guide-phrasing-v1"
            : route?.model,
        promptVersion: SUPPORT_GUIDE_PHRASING_PROMPT_VERSION,
        schemaVersion: 1,
      };
    } catch {
      throw new ServiceUnavailableException();
    }
  }
}

@Controller("internal/v1/support-guide-phrasing")
export class SupportGuidePhrasingController {
  constructor(private readonly service: SupportGuidePhrasingService) {}

  @Post()
  phrase(@Req() request: PhrasingRequest) {
    return this.service.phrase(request);
  }
}

export const registerSupportGuidePhrasingModule = (
  configuration: ServiceConfiguration,
  dependencies: SupportGuidePhrasingDependencies = {},
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
            new RoutedSupportGuidePhrasingProvider(configuration),
        },
    {
      provide: SupportGuidePhrasingService,
      useFactory: (
        consent: ConsentClient,
        provider: SupportGuidePhrasingProvider,
      ) => new SupportGuidePhrasingService(consent, provider, configuration),
      inject: [CONSENT_CLIENT, PROVIDER],
    },
  ];
  return {
    module: SupportGuidePhrasingModule,
    controllers: [SupportGuidePhrasingController],
    providers,
  };
};

@Module({})
export class SupportGuidePhrasingModule {
  readonly moduleName = "support-guide-phrasing";
}
