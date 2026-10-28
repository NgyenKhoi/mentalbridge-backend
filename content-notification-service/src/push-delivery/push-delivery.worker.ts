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
import { AppointmentPushReminderService } from '../appointment-reminders/appointment-push-reminder.service.js';
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
    private readonly appointmentPushReminders: AppointmentPushReminderService,
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
      await this.appointmentPushReminders.runDue(this.config.CONTENT_PUSH_DELIVERY_BATCH_SIZE);
      await this.repository.materializeCandidates(this.config.CONTENT_PUSH_DELIVERY_BATCH_SIZE);
      await this.repository.settleIneligible();
      const claims = await this.repository.claim(this.config.CONTENT_PUSH_DELIVERY_BATCH_SIZE);
      const crypto = new PushTokenCrypto(this.config.CONTENT_PUSH_TOKEN_ENCRYPTION_KEY ?? '');
      for (const claim of claims) {
        try {
          if (!(await this.repository.isDeliverable(claim.id))) {
            await this.repository.suppressClaim(claim.id, 'NOTIFICATION_UNAVAILABLE');
            continue;
          }
          const authorization = await this.appointmentPushReminders.authorizeClaim(claim);
          if (authorization.outcome === 'SUPPRESS') {
            await this.repository.suppressClaim(claim.id, authorization.code);
            continue;
          }
          if (authorization.outcome === 'RETRY') {
            await this.repository.failed(claim.id, authorization.code, true);
            continue;
          }
          if (authorization.outcome === 'DEFER') {
            await this.repository.deferClaim(
              claim.id,
              authorization.availableAt,
              authorization.code,
            );
            continue;
          }
          const ticketId = await this.provider.send({
            token: crypto.decrypt(claim.tokenCiphertext, claim.tokenIv, claim.tokenAuthTag),
            title: claim.title,
            notificationId: claim.notificationId,
            actionType: claim.actionType,
            actionTargetId: claim.actionTargetId,
          });
          await this.repository.accepted(
            claim.id,
            ticketId,
            this.config.CONTENT_PUSH_RECEIPT_INITIAL_DELAY_SECONDS,
          );
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
      const receiptClaims = await this.repository.claimReceipts(
        this.config.CONTENT_PUSH_DELIVERY_BATCH_SIZE,
        this.config.CONTENT_PUSH_RECEIPT_RETRY_DELAY_SECONDS,
      );
      if (receiptClaims.length > 0) {
        try {
          const receipts = await this.provider.getReceipts(
            receiptClaims.map((claim) => claim.providerTicketId),
          );
          for (const claim of receiptClaims) {
            const receipt = receipts.get(claim.providerTicketId) ?? { status: 'PENDING' as const };
            if (receipt.status === 'CONFIRMED') {
              await this.repository.confirmReceipt(claim.id);
            } else if (receipt.status === 'ERROR') {
              if (receipt.code === 'DEVICE_NOT_REGISTERED')
                await this.repository.invalidateDevice(claim.deviceId);
              await this.repository.failReceipt(claim.id, receipt.code);
            } else {
              await this.repository.retryReceipt(claim.id, 'PROVIDER_RECEIPT_PENDING');
            }
          }
        } catch {
          for (const claim of receiptClaims)
            await this.repository.retryReceipt(claim.id, 'PROVIDER_RECEIPT_UNAVAILABLE');
        }
      }
    } catch {
      this.logger.error('Push delivery worker run failed');
    } finally {
      this.running = false;
    }
  }
}
