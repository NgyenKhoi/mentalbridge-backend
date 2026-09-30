import { Inject, Injectable } from '@nestjs/common';
import { randomUUID } from 'node:crypto';

import {
  APPOINTMENT_EMAIL_DELIVERY_TOKEN,
  APPOINTMENT_REMINDER_REPOSITORY_TOKEN,
  APPOINTMENT_TRUTH_CLIENT_TOKEN,
  CONFIGURATION_TOKEN,
  DELIVERY_ADDRESS_CLIENT_TOKEN,
  NOTIFICATION_PREFERENCE_SERVICE_TOKEN,
  REMINDER_CLOCK_TOKEN,
} from '../application.tokens.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import type { NotificationPreferenceService } from '../notification-preferences/notification-preference.service.js';
import type { NotificationPreferences } from '../notification-preferences/notification-preference.types.js';
import type { ReminderClock } from '../reminders/reminder-materialization.service.js';
import type { AppointmentEmailDelivery } from './appointment-email.delivery.js';
import {
  PermanentProviderRejectionError,
  TransientProviderRejectionError,
  UnknownProviderOutcomeError,
} from './appointment-email.delivery.js';
import type {
  AppointmentTruthClient,
  DeliveryAddressClient,
} from './appointment-reminder.clients.js';
import type { AppointmentReminderRepository } from './appointment-reminder.repository.js';
import type { ClaimedAppointmentReminder } from './appointment-reminder.types.js';

function localParts(
  date: Date,
  timeZone: string,
): { date: string; time: string; minuteOfDay: number } {
  const formatter = new Intl.DateTimeFormat('en-CA', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  });
  const parts = Object.fromEntries(
    formatter.formatToParts(date).map((part) => [part.type, part.value]),
  );
  return {
    date: `${parts.year}-${parts.month}-${parts.day}`,
    time: `${parts.hour}:${parts.minute}`,
    minuteOfDay: Number(parts.hour) * 60 + Number(parts.minute),
  };
}

function timeMinutes(value: string): number {
  const [hour, minute] = value.split(':').map(Number);
  return hour * 60 + minute;
}

function inQuietHours(date: Date, timeZone: string, start: string, end: string): boolean {
  const value = localParts(date, timeZone).minuteOfDay;
  const from = timeMinutes(start);
  const to = timeMinutes(end);
  return from < to ? value >= from && value < to : value >= from || value < to;
}

function quietEnd(now: Date, timeZone: string, start: string, end: string): Date {
  for (let minutes = 1; minutes <= 24 * 60; minutes += 1) {
    const candidate = new Date(now.getTime() + minutes * 60_000);
    if (!inQuietHours(candidate, timeZone, start, end)) return candidate;
  }
  return new Date(now.getTime() + 24 * 60 * 60_000);
}

@Injectable()
export class AppointmentReminderService {
  constructor(
    @Inject(CONFIGURATION_TOKEN) private readonly configuration: ServiceConfiguration,
    @Inject(APPOINTMENT_REMINDER_REPOSITORY_TOKEN)
    private readonly repository: AppointmentReminderRepository,
    @Inject(NOTIFICATION_PREFERENCE_SERVICE_TOKEN)
    private readonly preferences: NotificationPreferenceService,
    @Inject(APPOINTMENT_TRUTH_CLIENT_TOKEN) private readonly appointments: AppointmentTruthClient,
    @Inject(DELIVERY_ADDRESS_CLIENT_TOKEN) private readonly addresses: DeliveryAddressClient,
    @Inject(APPOINTMENT_EMAIL_DELIVERY_TOKEN) private readonly delivery: AppointmentEmailDelivery,
    @Inject(REMINDER_CLOCK_TOKEN) private readonly clock: ReminderClock,
  ) {}

  async runDue(limit: number): Promise<{ delivered: number; failed: number }> {
    if (!this.configuration.APPOINTMENT_REMINDER_ENABLED) return { delivered: 0, failed: 0 };
    const now = this.clock.now();
    const reminders = await this.repository.claimDue(now, limit);
    let delivered = 0;
    let failed = 0;
    for (const reminder of reminders) {
      try {
        if (await this.process(reminder, now)) delivered += 1;
      } catch {
        await this.repository
          .finish(reminder.id, 'FAILED', now, 'UNEXPECTED_PROCESSING_FAILURE')
          .catch(() => undefined);
        failed += 1;
      }
    }
    return { delivered, failed };
  }

  private async process(reminder: ClaimedAppointmentReminder, now: Date): Promise<boolean> {
    if (now >= reminder.scheduledStartAt) {
      await this.repository.finish(reminder.id, 'EXPIRED', now, 'APPOINTMENT_STARTED');
      return false;
    }
    let preference: NotificationPreferences;
    try {
      preference = await this.preferences.get(reminder.recipientId);
    } catch {
      await this.retryDependency(reminder, now, 'PREFERENCE_UNAVAILABLE');
      return false;
    }
    if (
      !preference.notificationsEnabled ||
      !preference.channels.email ||
      !preference.contentGroups.appointmentMessage ||
      !preference.email.appointmentRemindersEnabled
    ) {
      await this.repository.finish(reminder.id, 'SUPPRESSED', now, 'PREFERENCE_DISABLED');
      return false;
    }
    try {
      localParts(now, preference.quietHours.timeZone);
    } catch {
      await this.repository.finish(reminder.id, 'FAILED', now, 'INVALID_TIME_ZONE');
      return false;
    }
    if (
      preference.quietHours.enabled &&
      inQuietHours(
        now,
        preference.quietHours.timeZone,
        preference.quietHours.start,
        preference.quietHours.end,
      )
    ) {
      const end = quietEnd(
        now,
        preference.quietHours.timeZone,
        preference.quietHours.start,
        preference.quietHours.end,
      );
      if (end >= reminder.scheduledStartAt) {
        await this.repository.finish(reminder.id, 'SUPPRESSED', now, 'QUIET_HOURS_OVERLAP_START');
      } else {
        await this.repository.retry(reminder.id, end, 'QUIET_HOURS_DEFERRED', now);
      }
      return false;
    }
    let address: string;
    try {
      address = await this.addresses.get(reminder.recipientId);
    } catch {
      await this.retryDependency(reminder, now, 'IDENTITY_UNAVAILABLE');
      return false;
    }
    let truth;
    try {
      truth = await this.appointments.get(reminder.appointmentId, reminder.appointmentVersion);
    } catch {
      await this.retryDependency(reminder, now, 'CONSULTATION_UNAVAILABLE');
      return false;
    }
    if (
      !truth.eligible ||
      truth.ownerAccountId !== reminder.recipientId ||
      truth.version !== reminder.appointmentVersion ||
      truth.modality !== reminder.modality ||
      new Date(truth.scheduledStartAt).getTime() !== reminder.scheduledStartAt.getTime()
    ) {
      await this.repository.finish(reminder.id, 'INVALIDATED', now, 'APPOINTMENT_NOT_ELIGIBLE');
      return false;
    }
    const submissionAt = this.clock.now();
    if (submissionAt >= reminder.scheduledStartAt) {
      await this.repository.finish(reminder.id, 'EXPIRED', submissionAt, 'APPOINTMENT_STARTED');
      return false;
    }
    if (
      preference.quietHours.enabled &&
      inQuietHours(
        submissionAt,
        preference.quietHours.timeZone,
        preference.quietHours.start,
        preference.quietHours.end,
      )
    ) {
      const end = quietEnd(
        submissionAt,
        preference.quietHours.timeZone,
        preference.quietHours.start,
        preference.quietHours.end,
      );
      if (end >= reminder.scheduledStartAt) {
        await this.repository.finish(
          reminder.id,
          'SUPPRESSED',
          submissionAt,
          'QUIET_HOURS_OVERLAP_START',
        );
      } else {
        await this.repository.retry(reminder.id, end, 'QUIET_HOURS_DEFERRED', submissionAt);
      }
      return false;
    }
    const local = localParts(reminder.scheduledStartAt, preference.quietHours.timeZone);
    const modality =
      reminder.modality === 'IN_APP_CHAT' ? 'chat trong ứng dụng' : 'video trong ứng dụng';
    const link = new URL(
      encodeURIComponent(reminder.appointmentId),
      this.configuration.APPOINTMENT_REMINDER_APP_URL.endsWith('/')
        ? this.configuration.APPOINTMENT_REMINDER_APP_URL
        : `${this.configuration.APPOINTMENT_REMINDER_APP_URL}/`,
    ).toString();
    try {
      const providerId = await this.delivery.send({
        recipient: address,
        subject: 'Nhắc lịch hẹn MentalBridge',
        text: `Lịch hẹn ${modality} lúc ${local.time}, ${local.date} (${preference.quietHours.timeZone}). Mở MentalBridge: ${link}`,
        html: `<p>Lịch hẹn ${modality} lúc ${local.time}, ${local.date} (${preference.quietHours.timeZone}).</p><p><a href="${link}">Mở MentalBridge</a></p>`,
        idempotencyKey: reminder.providerIdempotencyKey,
        correlationId: randomUUID(),
      });
      await this.repository.delivered(reminder.id, providerId, now);
      return true;
    } catch (error) {
      const ttlEnd = new Date(reminder.firstAttemptAt.getTime() + 15 * 60_000);
      if (error instanceof UnknownProviderOutcomeError) {
        if (now >= ttlEnd || reminder.attemptCount >= 3) {
          await this.repository.finish(reminder.id, 'UNKNOWN', now, 'PROVIDER_OUTCOME_UNKNOWN');
        } else {
          await this.retryDependency(reminder, now, 'PROVIDER_OUTCOME_UNKNOWN');
        }
      } else if (error instanceof TransientProviderRejectionError) {
        await this.retryDependency(reminder, now, 'PROVIDER_TRANSIENT_REJECTION');
      } else if (error instanceof PermanentProviderRejectionError) {
        await this.repository.finish(reminder.id, 'FAILED', now, 'PROVIDER_PERMANENT_REJECTION');
      } else {
        await this.repository.finish(reminder.id, 'FAILED', now, 'PROVIDER_ADAPTER_FAILURE');
      }
      return false;
    }
  }

  private async retryDependency(
    reminder: ClaimedAppointmentReminder,
    now: Date,
    code: string,
  ): Promise<void> {
    const next = new Date(
      now.getTime() + Math.min(2 ** reminder.attemptCount * 30_000, 5 * 60_000),
    );
    if (reminder.attemptCount >= 3 || next >= reminder.scheduledStartAt) {
      await this.repository.finish(reminder.id, 'FAILED', now, code);
    } else {
      await this.repository.retry(reminder.id, next, code, now);
    }
  }
}
