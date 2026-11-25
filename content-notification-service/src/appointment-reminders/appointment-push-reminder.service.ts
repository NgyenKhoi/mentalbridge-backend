import { Inject, Injectable } from '@nestjs/common';

import {
  APPOINTMENT_REMINDER_REPOSITORY_TOKEN,
  APPOINTMENT_TRUTH_CLIENT_TOKEN,
  CONFIGURATION_TOKEN,
  NOTIFICATION_PREFERENCE_SERVICE_TOKEN,
  REMINDER_CLOCK_TOKEN,
} from '../application.tokens.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import type { NotificationPreferenceService } from '../notification-preferences/notification-preference.service.js';
import type { NotificationPreferences } from '../notification-preferences/notification-preference.types.js';
import type { PushDeliveryClaim } from '../push-delivery/push-device.types.js';
import type { ReminderClock } from '../reminders/reminder-materialization.service.js';
import type {
  AppointmentEligibility,
  AppointmentTruthClient,
} from './appointment-reminder.clients.js';
import {
  AppointmentPushReminderConflictError,
  type AppointmentReminderRepository,
} from './appointment-reminder.repository.js';
import type { ClaimedAppointmentPushReminder } from './appointment-reminder.types.js';

type AuthorizationDecision =
  | Readonly<{ outcome: 'SEND' }>
  | Readonly<{ outcome: 'RETRY'; code: string }>
  | Readonly<{ outcome: 'DEFER'; availableAt: Date; code: string }>
  | Readonly<{ outcome: 'SUPPRESS'; code: string }>;

function localMinute(date: Date, timeZone: string): number {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone,
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(date);
  const value = (type: Intl.DateTimeFormatPartTypes): number =>
    Number(parts.find((part) => part.type === type)?.value ?? '0');
  return value('hour') * 60 + value('minute');
}

function timeMinute(value: string): number {
  const [hour, minute] = value.split(':').map(Number);
  return hour * 60 + minute;
}

function inQuietHours(date: Date, preferences: NotificationPreferences): boolean {
  if (!preferences.quietHours.enabled) return false;
  const current = localMinute(date, preferences.quietHours.timeZone);
  const start = timeMinute(preferences.quietHours.start);
  const end = timeMinute(preferences.quietHours.end);
  return start < end ? current >= start && current < end : current >= start || current < end;
}

function quietEnd(now: Date, preferences: NotificationPreferences): Date {
  for (let minutes = 1; minutes <= 24 * 60; minutes += 1) {
    const candidate = new Date(now.getTime() + minutes * 60_000);
    if (!inQuietHours(candidate, preferences)) return candidate;
  }
  return new Date(now.getTime() + 24 * 60 * 60_000);
}

function preferenceAllowsPush(preferences: NotificationPreferences): boolean {
  return (
    preferences.notificationsEnabled &&
    preferences.channels.push &&
    preferences.contentGroups.appointmentMessage
  );
}

function timingDecision(
  now: Date,
  scheduledStartAt: Date,
  preferences: NotificationPreferences,
): AuthorizationDecision | null {
  if (now >= scheduledStartAt) return { outcome: 'SUPPRESS', code: 'APPOINTMENT_STARTED' };
  if (!inQuietHours(now, preferences)) return null;
  const availableAt = quietEnd(now, preferences);
  return availableAt >= scheduledStartAt
    ? { outcome: 'SUPPRESS', code: 'QUIET_HOURS_OVERLAP_START' }
    : { outcome: 'DEFER', availableAt, code: 'QUIET_HOURS_DEFERRED' };
}

function matchesTruth(
  truth: AppointmentEligibility,
  reminder: Readonly<{
    recipientId: string;
    appointmentId: string;
    appointmentVersion: number;
    modality: 'IN_APP_CHAT' | 'IN_APP_VIDEO';
    scheduledStartAt: Date;
  }>,
): boolean {
  return (
    truth.eligible &&
    truth.appointmentId === reminder.appointmentId &&
    truth.status === 'CONFIRMED' &&
    truth.ownerAccountId === reminder.recipientId &&
    truth.version === reminder.appointmentVersion &&
    truth.modality === reminder.modality &&
    new Date(truth.scheduledStartAt).getTime() === reminder.scheduledStartAt.getTime()
  );
}

@Injectable()
export class AppointmentPushReminderService {
  constructor(
    @Inject(CONFIGURATION_TOKEN) private readonly configuration: ServiceConfiguration,
    @Inject(APPOINTMENT_REMINDER_REPOSITORY_TOKEN)
    private readonly repository: AppointmentReminderRepository,
    @Inject(NOTIFICATION_PREFERENCE_SERVICE_TOKEN)
    private readonly preferences: NotificationPreferenceService,
    @Inject(APPOINTMENT_TRUTH_CLIENT_TOKEN) private readonly appointments: AppointmentTruthClient,
    @Inject(REMINDER_CLOCK_TOKEN) private readonly clock: ReminderClock,
  ) {}

  async runDue(limit: number): Promise<{ materialized: number; failed: number }> {
    if (!this.configuration.CONTENT_PUSH_DELIVERY_ENABLED) return { materialized: 0, failed: 0 };
    const now = this.clock.now();
    await this.repository.reconcilePushLeases(now);
    const reminders = await this.repository.claimPushDue(now, limit);
    let materialized = 0;
    let failed = 0;
    for (const reminder of reminders) {
      try {
        if (await this.process(reminder, now)) materialized += 1;
      } catch (error) {
        const failedAt = this.clock.now();
        if (error instanceof AppointmentPushReminderConflictError) {
          await this.repository
            .finishPush(reminder.id, 'FAILED', failedAt, 'MATERIALIZATION_CONFLICT')
            .catch(() => undefined);
        } else {
          await this.retry(reminder, failedAt, 'MATERIALIZATION_UNAVAILABLE').catch(
            () => undefined,
          );
        }
        failed += 1;
      }
    }
    return { materialized, failed };
  }

  async authorizeClaim(claim: PushDeliveryClaim): Promise<AuthorizationDecision> {
    const reminder = claim.appointmentReminder;
    if (!reminder) return { outcome: 'SEND' };
    const now = this.clock.now();
    if (now >= reminder.scheduledStartAt)
      return { outcome: 'SUPPRESS', code: 'APPOINTMENT_STARTED' };
    let preferences: NotificationPreferences;
    try {
      preferences = await this.preferences.get(reminder.recipientId, 'USER');
    } catch {
      return { outcome: 'RETRY', code: 'PREFERENCE_UNAVAILABLE' };
    }
    if (!preferenceAllowsPush(preferences))
      return { outcome: 'SUPPRESS', code: 'PREFERENCE_DISABLED' };
    try {
      localMinute(now, preferences.quietHours.timeZone);
    } catch {
      return { outcome: 'SUPPRESS', code: 'INVALID_TIME_ZONE' };
    }
    const initialTiming = timingDecision(now, reminder.scheduledStartAt, preferences);
    if (initialTiming) return initialTiming;
    try {
      const truth = await this.appointments.get(
        reminder.appointmentId,
        reminder.appointmentVersion,
      );
      if (!matchesTruth(truth, reminder)) {
        return { outcome: 'SUPPRESS', code: 'APPOINTMENT_NOT_ELIGIBLE' };
      }
    } catch {
      return { outcome: 'RETRY', code: 'CONSULTATION_UNAVAILABLE' };
    }
    const authorizedAt = this.clock.now();
    const finalTiming = timingDecision(authorizedAt, reminder.scheduledStartAt, preferences);
    return finalTiming ?? { outcome: 'SEND' };
  }

  private async process(reminder: ClaimedAppointmentPushReminder, now: Date): Promise<boolean> {
    if (now >= reminder.scheduledStartAt) {
      await this.repository.finishPush(reminder.id, 'EXPIRED', now, 'APPOINTMENT_STARTED');
      return false;
    }
    let preferences: NotificationPreferences;
    try {
      preferences = await this.preferences.get(reminder.recipientId, 'USER');
    } catch {
      await this.retry(reminder, now, 'PREFERENCE_UNAVAILABLE');
      return false;
    }
    if (!preferenceAllowsPush(preferences)) {
      await this.repository.finishPush(reminder.id, 'SUPPRESSED', now, 'PREFERENCE_DISABLED');
      return false;
    }
    try {
      localMinute(now, preferences.quietHours.timeZone);
    } catch {
      await this.repository.finishPush(reminder.id, 'FAILED', now, 'INVALID_TIME_ZONE');
      return false;
    }
    if (inQuietHours(now, preferences)) {
      const availableAt = quietEnd(now, preferences);
      if (availableAt >= reminder.scheduledStartAt) {
        await this.repository.finishPush(
          reminder.id,
          'SUPPRESSED',
          now,
          'QUIET_HOURS_OVERLAP_START',
        );
      } else {
        await this.repository.retryPush(reminder.id, availableAt, 'QUIET_HOURS_DEFERRED', now);
      }
      return false;
    }
    try {
      const truth = await this.appointments.get(
        reminder.appointmentId,
        reminder.appointmentVersion,
      );
      if (!matchesTruth(truth, reminder)) {
        await this.repository.finishPush(
          reminder.id,
          'INVALIDATED',
          now,
          'APPOINTMENT_NOT_ELIGIBLE',
        );
        return false;
      }
    } catch {
      await this.retry(reminder, now, 'CONSULTATION_UNAVAILABLE');
      return false;
    }
    const materializedAt = this.clock.now();
    if (materializedAt >= reminder.scheduledStartAt) {
      await this.repository.finishPush(
        reminder.id,
        'EXPIRED',
        materializedAt,
        'APPOINTMENT_STARTED',
      );
      return false;
    }
    if (inQuietHours(materializedAt, preferences)) {
      const availableAt = quietEnd(materializedAt, preferences);
      if (availableAt >= reminder.scheduledStartAt) {
        await this.repository.finishPush(
          reminder.id,
          'SUPPRESSED',
          materializedAt,
          'QUIET_HOURS_OVERLAP_START',
        );
      } else {
        await this.repository.retryPush(
          reminder.id,
          availableAt,
          'QUIET_HOURS_DEFERRED',
          materializedAt,
        );
      }
      return false;
    }
    return this.repository.materializePush(reminder, materializedAt);
  }

  private async retry(
    reminder: ClaimedAppointmentPushReminder,
    now: Date,
    code: string,
  ): Promise<void> {
    const next = new Date(
      now.getTime() + Math.min(2 ** reminder.attemptCount * 30_000, 5 * 60_000),
    );
    if (reminder.attemptCount >= 3 || next >= reminder.scheduledStartAt) {
      await this.repository.finishPush(reminder.id, 'FAILED', now, code);
    } else {
      await this.repository.retryPush(reminder.id, next, code, now);
    }
  }
}
