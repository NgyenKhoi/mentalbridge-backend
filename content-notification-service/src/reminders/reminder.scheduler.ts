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
  REMINDER_MATERIALIZATION_SERVICE_TOKEN,
} from '../application.tokens.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import type { NotificationPreferenceService } from '../notification-preferences/notification-preference.service.js';
import type { ReminderMaterializationService } from './reminder-materialization.service.js';

export interface ReminderSchedulerResult {
  readonly candidates: number;
  readonly notifications: number;
  readonly failures: number;
  readonly skippedOverlap: boolean;
}

@Injectable()
export class ReminderScheduler implements OnModuleInit, OnApplicationShutdown {
  private readonly logger = new Logger(ReminderScheduler.name);
  private timer?: NodeJS.Timeout;
  private running = false;

  constructor(
    @Inject(CONFIGURATION_TOKEN)
    private readonly configuration: ServiceConfiguration,
    @Inject(NOTIFICATION_PREFERENCE_SERVICE_TOKEN)
    private readonly preferences: NotificationPreferenceService,
    @Inject(REMINDER_MATERIALIZATION_SERVICE_TOKEN)
    private readonly materialization: ReminderMaterializationService,
  ) {}

  onModuleInit(): void {
    if (!this.configuration.REMINDER_SCHEDULER_ENABLED) return;
    this.executeScheduledRun();
    this.timer = setInterval(() => {
      this.executeScheduledRun();
    }, this.configuration.REMINDER_SCHEDULER_INTERVAL_MS);
    this.timer.unref();
  }

  onApplicationShutdown(): void {
    if (this.timer) clearInterval(this.timer);
  }

  async runOnce(): Promise<ReminderSchedulerResult> {
    if (this.running)
      return {
        candidates: 0,
        notifications: 0,
        failures: 0,
        skippedOverlap: true,
      };
    this.running = true;
    let afterOwnerId: string | null = null;
    let candidates = 0;
    let notifications = 0;
    let failures = 0;
    try {
      for (;;) {
        const page = await this.preferences.listReminderCandidates(
          afterOwnerId,
          this.configuration.REMINDER_SCHEDULER_BATCH_SIZE,
        );
        for (const candidate of page) {
          candidates += 1;
          try {
            notifications += await this.materialization.materialize(
              candidate.ownerId,
              candidate.preferences,
              randomUUID(),
            );
          } catch {
            failures += 1;
          }
        }
        if (page.length < this.configuration.REMINDER_SCHEDULER_BATCH_SIZE) break;
        afterOwnerId = page.at(-1)?.ownerId ?? null;
        if (!afterOwnerId) break;
      }
      return {
        candidates,
        notifications,
        failures,
        skippedOverlap: false,
      };
    } finally {
      this.running = false;
    }
  }

  private executeScheduledRun(): void {
    void this.runOnce().catch(() => {
      this.logger.error('Reminder scheduler run failed');
    });
  }
}
