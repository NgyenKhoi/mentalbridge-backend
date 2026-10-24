import {
  Catch,
  Controller,
  Get,
  Inject,
  Injectable,
  Module,
  Req,
  UseFilters,
  UseGuards,
  type ArgumentsHost,
  type CanActivate,
  type DynamicModule,
  type ExceptionFilter,
  type ExecutionContext,
  type OnApplicationShutdown,
  type OnModuleInit,
} from "@nestjs/common";
import { MongoClient } from "mongodb";
import { randomUUID } from "node:crypto";
import { z } from "zod";

import type { ServiceConfiguration } from "../configuration/configuration.js";
import { createLogger } from "../observability/logger.js";
import { CONFIGURATION_TOKEN } from "../observability/tokens.js";
import type { AuthenticatedRequest } from "../security/authenticated-principal.js";
import { IdentityJwtVerifier } from "../security/identity-jwt-verifier.js";
import { Public } from "../security/public.decorator.js";
import {
  CurrentVoiceOwnerAuthorizer,
  type VoiceOwnerAuthorizer,
} from "./voice-authorization.js";
import {
  VoiceAccessError,
  VoiceAccessService,
  type VoiceAccessLedger,
} from "./voice-access.js";
import { MongoVoiceLedger, type VoicePlan } from "./voice-ledger.js";

interface VoiceRequest extends AuthenticatedRequest {
  id?: string;
}

interface VoiceResponse {
  setHeader(name: string, value: string): void;
  status(code: number): VoiceResponse;
  json(body: unknown): void;
}

const VOICE_ACCESS = "COMPANION_VOICE_ACCESS";

class VoiceBoundaryError extends Error {
  constructor(
    readonly status: 401 | 403,
    readonly code: "UNAUTHENTICATED" | "FORBIDDEN",
  ) {
    super(code);
  }
}

@Injectable()
class VoiceUserGuard implements CanActivate {
  constructor(private readonly verifier: IdentityJwtVerifier) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const request = context.switchToHttp().getRequest<VoiceRequest>();
    const response = context.switchToHttp().getResponse<VoiceResponse>();
    response.setHeader("Cache-Control", "no-store");
    const correlation = z.uuid().safeParse(request.id);
    request.id = correlation.success ? correlation.data : randomUUID();
    response.setHeader("X-Correlation-Id", request.id);
    const authorization = request.headers.authorization;
    const token =
      typeof authorization === "string"
        ? /^Bearer ([^\s]+)$/.exec(authorization)?.[1]
        : undefined;
    if (!token) throw new VoiceBoundaryError(401, "UNAUTHENTICATED");
    try {
      request.principal = await this.verifier.verify(token);
    } catch {
      throw new VoiceBoundaryError(401, "UNAUTHENTICATED");
    }
    if (!request.principal.roles.includes("USER"))
      throw new VoiceBoundaryError(403, "FORBIDDEN");
    return true;
  }
}

@Catch()
class VoiceBoundaryFilter implements ExceptionFilter {
  catch(error: unknown, host: ArgumentsHost): void {
    const request = host.switchToHttp().getRequest<VoiceRequest>();
    const response = host.switchToHttp().getResponse<VoiceResponse>();
    const status = error instanceof VoiceBoundaryError ? error.status : 503;
    const code =
      error instanceof VoiceBoundaryError || error instanceof VoiceAccessError
        ? error.code
        : "PROVIDER_UNAVAILABLE";
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Content-Type", "application/problem+json");
    response.status(status).json({
      type: `https://mentalbridge.dev/problems/voice/${code.toLowerCase().replaceAll("_", "-")}`,
      title:
        status === 401
          ? "Authentication is required"
          : status === 403
            ? "Access is denied"
            : "Voice authorization is unavailable",
      status,
      code,
      correlationId: request.id ?? randomUUID(),
    });
  }
}

@Public()
@UseGuards(VoiceUserGuard)
@UseFilters(VoiceBoundaryFilter)
@Controller("api/v1/ai-companion")
class VoiceEligibilityController {
  constructor(
    @Inject(VOICE_ACCESS) private readonly access: VoiceAccessService,
  ) {}

  @Get("voice-eligibility")
  eligibility(@Req() request: VoiceRequest) {
    const ownerId = request.principal?.accountId;
    if (!ownerId) throw new VoiceBoundaryError(401, "UNAUTHENTICATED");
    return this.access.eligibility(ownerId, request.id ?? randomUUID());
  }
}

class VoiceMaintenance implements OnModuleInit, OnApplicationShutdown {
  private timer: ReturnType<typeof setInterval> | undefined;
  private inFlight: Promise<void> | undefined;

  constructor(
    private readonly access: VoiceAccessService,
    private readonly enabled: boolean,
    private readonly unavailable: () => void,
    private readonly close: () => Promise<void>,
  ) {}

  onModuleInit(): void {
    if (!this.enabled) return;
    this.timer = setInterval(() => {
      if (this.inFlight) return;
      this.inFlight = this.access
        .reconcile()
        .catch(this.unavailable)
        .finally(() => {
          this.inFlight = undefined;
        });
    }, 1_000);
    this.timer.unref();
  }

  async onApplicationShutdown(): Promise<void> {
    if (this.timer) clearInterval(this.timer);
    await this.inFlight;
    await this.close();
  }
}

export interface CompanionVoiceDependencies {
  readonly ledger?: VoiceAccessLedger;
  readonly authorizer?: VoiceOwnerAuthorizer;
  readonly clock?: () => Date;
  readonly providerReady?: (plan: VoicePlan) => boolean;
  readonly telemetry?: (outcome: string) => void;
  readonly maintenance?: boolean;
}

@Module({})
class CompanionVoiceModule {
  readonly moduleName = "companion-voice";
}

export const registerCompanionVoiceModule = (
  configuration: ServiceConfiguration,
  dependencies: CompanionVoiceDependencies = {},
): DynamicModule => {
  const client = new MongoClient(configuration.MONGODB_URI, {
    serverSelectionTimeoutMS: configuration.MONGODB_CONNECTION_TIMEOUT_MS,
  });
  const ledger =
    dependencies.ledger ??
    new MongoVoiceLedger(client, configuration.MONGODB_DATABASE);
  const logger = createLogger(configuration);
  const telemetry =
    dependencies.telemetry ??
    ((outcome: string) => {
      logger.info({ event: "voice_access", outcome });
    });
  const service = new VoiceAccessService(
    ledger,
    dependencies.authorizer ??
      new CurrentVoiceOwnerAuthorizer({
        careBaseUrl: configuration.CARE_BASE_URL,
        consultationBaseUrl: configuration.CONSULTATION_BASE_URL,
        careToken: configuration.VOICE_OWNER_TOKENS?.care ?? null,
        entitlementToken: configuration.VOICE_OWNER_TOKENS?.entitlement ?? null,
      }),
    configuration.JOURNAL_IDEMPOTENCY_HMAC_KEY,
    dependencies.providerReady,
    dependencies.clock,
    telemetry,
  );
  return {
    module: CompanionVoiceModule,
    controllers: [VoiceEligibilityController],
    providers: [
      { provide: CONFIGURATION_TOKEN, useValue: configuration },
      IdentityJwtVerifier,
      VoiceUserGuard,
      { provide: VOICE_ACCESS, useValue: service },
      { provide: VoiceAccessService, useValue: service },
      {
        provide: VoiceMaintenance,
        useValue: new VoiceMaintenance(
          service,
          dependencies.maintenance ??
            configuration.VOICE_OWNER_TOKENS !== undefined,
          () => {
            telemetry("RECONCILIATION_UNAVAILABLE");
          },
          () => client.close(),
        ),
      },
    ],
    exports: [VoiceAccessService],
  };
};
