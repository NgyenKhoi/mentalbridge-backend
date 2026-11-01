import {
  Inject,
  Injectable,
  Logger,
  type OnModuleInit,
  type OnApplicationShutdown,
} from '@nestjs/common';
import { CONFIGURATION_TOKEN, REMINDER_CLOCK_TOKEN } from '../application.tokens.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import type { ReminderClock } from '../reminders/reminder-materialization.service.js';
import { CommunityNotificationJobs } from './community-notification.jobs.js';
import { CommunityNotificationRepository } from './community-notification.repository.js';
import { CommunityNotificationClient } from './community-notification.client.js';

@Injectable()
export class CommunityNotificationWorker implements OnModuleInit, OnApplicationShutdown {
  private timer?: NodeJS.Timeout;
  private running = false;
  private readonly logger = new Logger(CommunityNotificationWorker.name);
  constructor(
    @Inject(CONFIGURATION_TOKEN) private readonly configuration: ServiceConfiguration,
    @Inject(CommunityNotificationJobs) private readonly jobs: CommunityNotificationJobs,
    @Inject(CommunityNotificationRepository)
    private readonly repository: CommunityNotificationRepository,
    @Inject(CommunityNotificationClient) private readonly owner: CommunityNotificationClient,
    @Inject(REMINDER_CLOCK_TOKEN) private readonly clock: ReminderClock,
  ) {}
  onModuleInit(): void {
    if (
      !this.configuration.CONTENT_COMMUNITY_INTERACTION_CONSUMER_ENABLED &&
      !this.configuration.CONTENT_COMMUNITY_NOTICE_CONSUMER_ENABLED
    )
      return;
    this.timer = setInterval(() => {
      void this.runDue().catch(() => {
        this.logger.warn('Community notice delivery deferred');
      });
    }, 1000);
    this.timer.unref();
  }
  async runDue(limit = 20): Promise<void> {
    if (this.running) return;
    this.running = true;
    try {
      for (let index = 0; index < Math.min(Math.max(limit, 0), 20); index++) {
        const job = await this.jobs.claim(this.clock.now());
        if (!job) break;
        try {
          if (
            new Date(job.expires_at).getTime() <= this.clock.now().getTime() ||
            (job.projection.authorization &&
              !(await this.owner.authorize(job.projection.authorization)))
          ) {
            await this.jobs.cancel(job);
            continue;
          }
          const result = await this.repository.materialize(
            job.projection,
            job.request_fingerprint,
            job,
            this.clock.now(),
          );
          if (result.deferredUntil) await this.jobs.release(job, result.deferredUntil);
        } catch {
          await this.jobs.release(
            job,
            new Date(
              this.clock.now().getTime() +
                Math.min(30_000, 1000 * 2 ** Math.min(job.attempts - 1, 5)),
            ),
          );
          this.logger.warn('Community notice dependency retry scheduled');
        }
      }
      await this.jobs.cleanup(this.clock.now());
    } finally {
      this.running = false;
    }
  }
  onApplicationShutdown(): void {
    if (this.timer) clearInterval(this.timer);
  }
}
