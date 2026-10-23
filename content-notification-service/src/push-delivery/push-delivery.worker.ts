import {
  Inject,
  Injectable,
  Logger,
  type OnApplicationShutdown,
  type OnModuleInit,
} from '@nestjs/common';

import {
  CONFIGURATION_TOKEN,
  PUSH_DEVICE_REPOSITORY_TOKEN,
  PUSH_PROVIDER_TOKEN,
} from '../application.tokens.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import {
  InvalidPushTokenError,
  TransientPushProviderError,
  UnknownPushProviderOutcomeError,
  type PushProvider,
} from './expo-push.provider.js';
import type { PushDeviceRepository } from './push-device.repository.js';
import { PushTokenCrypto } from './push-token.crypto.js';

@Injectable()
export class PushDeliveryWorker implements OnModuleInit, OnApplicationShutdown {
  private readonly logger = new Logger(PushDeliveryWorker.name);
  private timer?: NodeJS.Timeout;
  private running = false;

  constructor(
    @Inject(CONFIGURATION_TOKEN) private readonly config: ServiceConfiguration,
    @Inject(PUSH_DEVICE_REPOSITORY_TOKEN) private readonly repository: PushDeviceRepository,
    @Inject(PUSH_PROVIDER_TOKEN) private readonly provider: PushProvider,
  ) {}

  onModuleInit(): void {
    if (!this.config.CONTENT_PUSH_DELIVERY_ENABLED) return;
    void this.runOnce();
    this.timer = setInterval(
      () => void this.runOnce(),
      this.config.CONTENT_PUSH_DELIVERY_INTERVAL_MS,
    );
    this.timer.unref();
  }

  onApplicationShutdown(): void {
    if (this.timer) clearInterval(this.timer);
  }

  async runOnce(): Promise<void> {
    if (this.running) return;
    this.running = true;
    try {
      await this.repository.materializeCandidates(this.config.CONTENT_PUSH_DELIVERY_BATCH_SIZE);
      await this.repository.settleIneligible();
      const claims = await this.repository.claim(this.config.CONTENT_PUSH_DELIVERY_BATCH_SIZE);
      const crypto = new PushTokenCrypto(this.config.CONTENT_PUSH_TOKEN_ENCRYPTION_KEY ?? '');
      for (const claim of claims) {
        try {
          const ticketId = await this.provider.send({
            token: crypto.decrypt(claim.tokenCiphertext, claim.tokenIv, claim.tokenAuthTag),
            title: claim.title,
            notificationId: claim.notificationId,
            actionType: claim.actionType,
            actionTargetId: claim.actionTargetId,
          });
          await this.repository.accepted(claim.id, ticketId);
        } catch (error) {
          if (error instanceof InvalidPushTokenError) {
            await this.repository.invalidateDevice(claim.deviceId);
            await this.repository.failed(claim.id, 'DEVICE_NOT_REGISTERED', false);
          } else if (error instanceof TransientPushProviderError) {
            await this.repository.failed(claim.id, 'PROVIDER_TRANSIENT', true);
          } else if (error instanceof UnknownPushProviderOutcomeError) {
            await this.repository.unknown(claim.id, 'PROVIDER_OUTCOME_UNKNOWN');
          } else {
            await this.repository.failed(claim.id, 'PROVIDER_REJECTED', false);
          }
        }
      }
    } catch {
      this.logger.error('Push delivery worker run failed');
    } finally {
      this.running = false;
    }
  }
}
