import {
  Inject,
  Injectable,
  Logger,
  type OnApplicationShutdown,
  type OnModuleInit,
} from '@nestjs/common';
import { randomUUID } from 'node:crypto';
import {
  CONFIGURATION_TOKEN,
  NOTIFICATION_PREFERENCE_SERVICE_TOKEN,
  WELLBEING_DIGEST_SERVICE_TOKEN,
} from '../application.tokens.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import type { NotificationPreferenceService } from '../notification-preferences/notification-preference.service.js';
import type { WellbeingDigestService } from './wellbeing-digest.service.js';

@Injectable()
export class WellbeingDigestScheduler implements OnModuleInit, OnApplicationShutdown {
  private readonly logger = new Logger(WellbeingDigestScheduler.name);
  private timer?: NodeJS.Timeout;
  private running = false;

  constructor(
    @Inject(CONFIGURATION_TOKEN) private readonly config: ServiceConfiguration,
    @Inject(NOTIFICATION_PREFERENCE_SERVICE_TOKEN)
    private readonly preferences: NotificationPreferenceService,
    @Inject(WELLBEING_DIGEST_SERVICE_TOKEN) private readonly digest: WellbeingDigestService,
  ) {}

  onModuleInit(): void {
    if (!this.config.WELLBEING_DIGEST_SCHEDULER_ENABLED) return;
    this.execute();
    this.timer = setInterval(() => {
      this.execute();
    }, this.config.WELLBEING_DIGEST_SCHEDULER_INTERVAL_MS);
    this.timer.unref();
  }

  onApplicationShutdown(): void {
    if (this.timer) clearInterval(this.timer);
  }

  async runOnce(): Promise<{
    candidates: number;
    delivered: number;
    failures: number;
    skippedOverlap: boolean;
  }> {
    if (this.running) return { candidates: 0, delivered: 0, failures: 0, skippedOverlap: true };
    this.running = true;
    let afterOwnerId: string | null = null;
    let candidates = 0;
    let delivered = 0;
    let failures = 0;
    try {
      for (;;) {
        const page = await this.preferences.listEmailCandidates(
          afterOwnerId,
          this.config.WELLBEING_DIGEST_BATCH_SIZE,
        );
        for (const candidate of page) {
          candidates += 1;
          try {
            delivered += await this.digest.deliver(
              candidate.ownerId,
              candidate.preferences,
              randomUUID(),
            );
          } catch {
            failures += 1;
          }
        }
        if (page.length < this.config.WELLBEING_DIGEST_BATCH_SIZE) break;
        afterOwnerId = page.at(-1)?.ownerId ?? null;
        if (!afterOwnerId) break;
      }
      return { candidates, delivered, failures, skippedOverlap: false };
    } finally {
      this.running = false;
    }
  }

  private execute(): void {
    void this.runOnce().catch(() => {
      this.logger.error('Wellbeing digest scheduler run failed');
    });
  }
}
