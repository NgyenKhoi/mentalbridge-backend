import {
  Module,
  type DynamicModule,
  type OnApplicationShutdown,
  type OnModuleInit,
} from "@nestjs/common";
import { MongoClient } from "mongodb";
import type { ServiceConfiguration } from "../configuration/configuration.js";
import { createLogger } from "../observability/logger.js";
import { CONFIGURATION_TOKEN } from "../observability/tokens.js";
import { IdentityJwtVerifier } from "../security/identity-jwt-verifier.js";
import {
  CurrentVoiceOwnerAuthorizer,
  type VoiceOwnerAuthorizer,
} from "./voice-authorization.js";
import { VoiceAccessService, type VoiceAccessLedger } from "./voice-access.js";
import { MongoVoiceLedger, type VoicePlan } from "./voice-ledger.js";
import {
  LiveKitVoiceMedia,
  VoiceMediaError,
  type VoiceMediaProvider,
} from "./voice-media-provider.js";
import { MongoVoiceMediaStore } from "./voice-media-store.js";
import { VoiceSessionsService } from "./voice-sessions.js";
import {
  VOICE_SESSIONS,
  VoiceUserController,
  VoiceWorkerController,
  VoiceUserGuard,
  VoiceWorkerGuard,
  type VoiceSessionOperations,
} from "./voice-session-boundary.js";

class VoiceMaintenance implements OnModuleInit, OnApplicationShutdown {
  private timer: ReturnType<typeof setInterval> | undefined;
  private inFlight: Promise<void> | undefined;

  constructor(
    private readonly access: Pick<VoiceSessionsService, "reconcile">,
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
  readonly media?: VoiceMediaProvider;
  readonly sessions?: VoiceSessionOperations;
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
  const coreLedger = new MongoVoiceLedger(
    client,
    configuration.MONGODB_DATABASE,
  );
  const ledger = dependencies.ledger ?? coreLedger;
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
    dependencies.providerReady ??
      (() =>
        Boolean(
          configuration.VOICE_MEDIA?.enabled &&
          configuration.VOICE_MEDIA.approvedVersion &&
          configuration.VOICE_MEDIA.serverUrl &&
          configuration.VOICE_MEDIA.apiKey &&
          configuration.VOICE_MEDIA.apiSecret &&
          configuration.VOICE_MEDIA.workerToken &&
          configuration.VOICE_OWNER_TOKENS?.care &&
          configuration.VOICE_OWNER_TOKENS.entitlement,
        )),
    dependencies.clock,
    telemetry,
  );
  const disabled = () => Promise.reject(new VoiceMediaError());
  const mediaConfiguration = configuration.VOICE_MEDIA;
  const media =
    dependencies.media ??
    (mediaConfiguration?.serverUrl &&
    mediaConfiguration.apiKey &&
    mediaConfiguration.apiSecret
      ? new LiveKitVoiceMedia(mediaConfiguration)
      : {
          createRoom: disabled,
          dispatch: disabled,
          inspect: disabled,
          connection: disabled,
          stop: disabled,
        });
  const sessions = new VoiceSessionsService(
    coreLedger,
    service,
    new MongoVoiceMediaStore(client, configuration.MONGODB_DATABASE),
    media,
    configuration.JOURNAL_IDEMPOTENCY_HMAC_KEY,
    dependencies.clock,
    telemetry,
  );
  return {
    module: CompanionVoiceModule,
    controllers: [VoiceUserController, VoiceWorkerController],
    providers: [
      { provide: CONFIGURATION_TOKEN, useValue: configuration },
      IdentityJwtVerifier,
      VoiceUserGuard,
      VoiceWorkerGuard,
      { provide: VOICE_SESSIONS, useValue: dependencies.sessions ?? sessions },
      { provide: VoiceSessionsService, useValue: sessions },
      { provide: VoiceAccessService, useValue: service },
      {
        provide: VoiceMaintenance,
        useValue: new VoiceMaintenance(
          sessions,
          dependencies.maintenance ??
            configuration.VOICE_OWNER_TOKENS !== undefined,
          () => {
            telemetry("RECONCILIATION_UNAVAILABLE");
          },
          () => client.close(),
        ),
      },
    ],
    exports: [VoiceAccessService, VoiceSessionsService],
  };
};
