import { randomUUID } from 'node:crypto';
import { Inject, Injectable } from '@nestjs/common';

import { REMINDER_ACTIVITY_CLIENT_TOKEN, REMINDER_CLOCK_TOKEN } from '../application.tokens.js';
import type { ReminderActivityClient } from '../reminders/reminder-activity.client.js';
import {
  localDayStart,
  type ReminderClock,
} from '../reminders/reminder-materialization.service.js';
import { SupportPlanReminderClient } from '../reminders/support-plan-reminder.client.js';
import type { PushDeliveryClaim } from './push-device.types.js';

export type DailyReminderPushDecision =
  | Readonly<{ decision: 'SEND' }>
  | Readonly<{ decision: 'SUPPRESS'; code: 'REMINDER_EXPIRED' | 'ACTIVITY_COMPLETED' }>
  | Readonly<{
      decision: 'DEFER';
      availableAt: Date;
      code: 'REMINDER_NOT_DUE' | 'ACTIVITY_UNAVAILABLE';
    }>;

const ACTIVITY_BY_KIND = {
  JOURNAL_REMINDER: 'journal',
  EMOTION_CHECKIN_REMINDER: 'emotionCheckIn',
} as const;

const SUPPORT_PLAN_KIND = 'SUPPORT_PLAN_DAILY_REMINDER';
const ACTIVITY_RETRY_MS = 5 * 60_000;

@Injectable()
export class DailyReminderPushGate {
  constructor(
    @Inject(REMINDER_ACTIVITY_CLIENT_TOKEN) private readonly activity: ReminderActivityClient,
    @Inject(REMINDER_CLOCK_TOKEN) private readonly clock: ReminderClock,
    @Inject(SupportPlanReminderClient) private readonly supportPlan: SupportPlanReminderClient,
  ) {}

  async authorizeClaim(claim: PushDeliveryClaim): Promise<DailyReminderPushDecision> {
    if (!(claim.kind in ACTIVITY_BY_KIND) && claim.kind !== SUPPORT_PLAN_KIND)
      return { decision: 'SEND' };
    const localDate = /^[A-Z_]+:(\d{4}-\d{2}-\d{2})$/.exec(claim.sourceIdentity)?.[1];
    if (!localDate) return { decision: 'SUPPRESS', code: 'REMINDER_EXPIRED' };

    const now = this.clock.now();
    const [year, month, day] = localDate.split('-').map(Number);
    const nextDate = new Date(Date.UTC(year, month - 1, day + 1)).toISOString().slice(0, 10);
    if (now >= new Date(localDayStart(nextDate, claim.timeZone)))
      return { decision: 'SUPPRESS', code: 'REMINDER_EXPIRED' };
    const [hour, minute] = claim.dailyReminderTime.split(':').map(Number);
    const dueAt = new Date(localDayStart(localDate, claim.timeZone, hour * 60 + minute));
    if (now < dueAt) return { decision: 'DEFER', availableAt: dueAt, code: 'REMINDER_NOT_DUE' };

    try {
      if (claim.kind === SUPPORT_PLAN_KIND) {
        if (!this.supportPlan.isConfigured())
          return { decision: 'SUPPRESS', code: 'ACTIVITY_COMPLETED' };
        return (await this.supportPlan.eligible(claim.ownerId, now, randomUUID()))
          ? { decision: 'SEND' }
          : { decision: 'SUPPRESS', code: 'ACTIVITY_COMPLETED' };
      }
      const field = ACTIVITY_BY_KIND[claim.kind as keyof typeof ACTIVITY_BY_KIND];
      const activity = await this.activity.get(claim.ownerId, claim.timeZone, randomUUID());
      if (activity.asOfLocalDate !== localDate)
        return { decision: 'SUPPRESS', code: 'REMINDER_EXPIRED' };
      return activity[field].completedToday
        ? { decision: 'SUPPRESS', code: 'ACTIVITY_COMPLETED' }
        : { decision: 'SEND' };
    } catch {
      return {
        decision: 'DEFER',
        availableAt: new Date(this.clock.now().getTime() + ACTIVITY_RETRY_MS),
        code: 'ACTIVITY_UNAVAILABLE',
      };
    }
  }
}
