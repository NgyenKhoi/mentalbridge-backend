import {
  Inject,
  Injectable,
  Logger,
  type OnApplicationShutdown,
  type OnModuleInit,
} from '@nestjs/common';

import { APPOINTMENT_REMINDER_SERVICE_TOKEN, CONFIGURATION_TOKEN } from '../application.tokens.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import type { AppointmentReminderService } from './appointment-reminder.service.js';

@Injectable()
export class AppointmentReminderScheduler implements OnModuleInit, OnApplicationShutdown {
  private readonly logger = new Logger(AppointmentReminderScheduler.name);
  private timer?: NodeJS.Timeout;
  private running = false;

  constructor(
    @Inject(CONFIGURATION_TOKEN) private readonly configuration: ServiceConfiguration,
    @Inject(APPOINTMENT_REMINDER_SERVICE_TOKEN)
    private readonly reminders: AppointmentReminderService,
  ) {}

  onModuleInit(): void {
    if (!this.configuration.APPOINTMENT_REMINDER_ENABLED) return;
    void this.runOnce();
    this.timer = setInterval(
      () => void this.runOnce(),
      this.configuration.REMINDER_SCHEDULER_INTERVAL_MS,
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
      await this.reminders.runDue(this.configuration.REMINDER_SCHEDULER_BATCH_SIZE);
    } catch {
      this.logger.error('Appointment reminder scheduler run failed');
    } finally {
      this.running = false;
    }
  }
}
