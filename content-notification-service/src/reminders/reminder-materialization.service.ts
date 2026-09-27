import { Inject, Injectable } from '@nestjs/common';

import {
  NOTIFICATION_PREFERENCE_SERVICE_TOKEN,
  NOTIFICATION_SERVICE_TOKEN,
  REMINDER_ACTIVITY_CLIENT_TOKEN,
  REMINDER_CLOCK_TOKEN,
} from '../application.tokens.js';
import type { NotificationPreferenceService } from '../notification-preferences/notification-preference.service.js';
import type { NotificationPreferences } from '../notification-preferences/notification-preference.types.js';
import type { NotificationService } from '../notifications/notification.service.js';
import type { NotificationCreate, NotificationKind } from '../notifications/notification.types.js';
import type { NotificationActivity, ReminderActivityClient } from './reminder-activity.client.js';

const MILESTONES = new Set([7, 14, 30]);

export interface ReminderClock {
  now(): Date;
}

const localParts = (instant: Date, timezone: string): { date: string; minutes: number } => {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: timezone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(instant);
  const value = (type: Intl.DateTimeFormatPartTypes): string =>
    parts.find((candidate) => candidate.type === type)?.value ?? '';
  return {
    date: `${value('year')}-${value('month')}-${value('day')}`,
    minutes: Number(value('hour')) * 60 + Number(value('minute')),
  };
};

const timeMinutes = (value: string): number => {
  const [hour, minute] = value.split(':').map(Number);
  return hour * 60 + minute;
};

export const localDayStart = (localDate: string, timezone: string): string => {
  const [year, month, day] = localDate.split('-').map(Number);
  const target = Date.UTC(year, month - 1, day);
  const formatter = new Intl.DateTimeFormat('en-US', {
    timeZone: timezone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  });
  let instant = target;
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const parts = formatter.formatToParts(new Date(instant));
    const value = (type: Intl.DateTimeFormatPartTypes): number =>
      Number(parts.find((candidate) => candidate.type === type)?.value ?? '0');
    const representedAsUtc = Date.UTC(
      value('year'),
      value('month') - 1,
      value('day'),
      value('hour'),
      value('minute'),
      value('second'),
    );
    const adjustment = target - representedAsUtc;
    instant += adjustment;
    if (adjustment === 0) break;
  }
  return new Date(instant).toISOString();
};

export const isQuietHour = (preferences: NotificationPreferences, instant: Date): boolean => {
  if (!preferences.quietHours.enabled) return false;
  const current = localParts(instant, preferences.quietHours.timeZone).minutes;
  const start = timeMinutes(preferences.quietHours.start);
  const end = timeMinutes(preferences.quietHours.end);
  return start < end ? current >= start && current < end : current >= start || current < end;
};

const command = (
  ownerId: string,
  kind: NotificationKind,
  localDate: string,
  occurredAt: string,
  title: string,
  body: string,
  action?: NotificationCreate['action'],
): NotificationCreate => ({
  ownerId,
  kind,
  title,
  body,
  occurredAt,
  ...(action ? { action } : {}),
  source: 'JOURNAL_AI_ACTIVITY',
  sourceIdentity: `${kind}:${localDate}`,
  priority: 'NORMAL',
});

@Injectable()
export class ReminderMaterializationService {
  constructor(
    @Inject(NOTIFICATION_PREFERENCE_SERVICE_TOKEN)
    private readonly preferences: NotificationPreferenceService,
    @Inject(REMINDER_ACTIVITY_CLIENT_TOKEN)
    private readonly activityClient: ReminderActivityClient,
    @Inject(NOTIFICATION_SERVICE_TOKEN)
    private readonly notifications: NotificationService,
    @Inject(REMINDER_CLOCK_TOKEN) private readonly clock: ReminderClock,
  ) {}

  async materialize(ownerId: string, accessToken: string, correlationId: string): Promise<number> {
    const preferences = await this.preferences.get(ownerId);
    const now = this.clock.now();
    if (
      !preferences.notificationsEnabled ||
      !preferences.channels.inApp ||
      isQuietHour(preferences, now) ||
      (!preferences.contentGroups.journalReminder &&
        !preferences.contentGroups.emotionCheckIn &&
        !preferences.contentGroups.streakMilestone)
    ) {
      return 0;
    }

    const activity = await this.activityClient.get(
      accessToken,
      preferences.quietHours.timeZone,
      correlationId,
    );
    const local = localParts(now, preferences.quietHours.timeZone);
    if (
      activity.timezone !== preferences.quietHours.timeZone ||
      activity.asOfLocalDate !== local.date
    ) {
      return 0;
    }

    const occurredAt = localDayStart(activity.asOfLocalDate, activity.timezone);
    const commands = this.commands(ownerId, activity, occurredAt, preferences);
    for (const notification of commands) {
      await this.notifications.create(notification);
    }
    return commands.length;
  }

  private commands(
    ownerId: string,
    activity: NotificationActivity,
    occurredAt: string,
    preferences: NotificationPreferences,
  ): NotificationCreate[] {
    const commands: NotificationCreate[] = [];
    if (preferences.contentGroups.journalReminder && !activity.journal.completedToday) {
      commands.push(
        command(
          ownerId,
          'JOURNAL_REMINDER',
          activity.asOfLocalDate,
          occurredAt,
          'Bạn có thể viết vài dòng hôm nay',
          'Nhật ký hôm nay vẫn đang mở nếu bạn muốn ghi lại điều mình đang nghĩ.',
          { type: 'OPEN_JOURNAL' },
        ),
      );
    }
    if (preferences.contentGroups.emotionCheckIn && !activity.emotionCheckIn.completedToday) {
      commands.push(
        command(
          ownerId,
          'EMOTION_CHECKIN_REMINDER',
          activity.asOfLocalDate,
          occurredAt,
          'Bạn có thể ghi nhận cảm xúc hôm nay',
          'Một lần ghi nhận ngắn giúp bạn lưu lại cảm nhận của chính mình trong ngày.',
        ),
      );
    }
    if (preferences.contentGroups.streakMilestone) {
      if (activity.journal.completedToday && MILESTONES.has(activity.journal.currentStreak)) {
        commands.push(
          command(
            ownerId,
            'JOURNAL_STREAK_MILESTONE',
            activity.asOfLocalDate,
            occurredAt,
            `Bạn đã viết nhật ký ${String(activity.journal.currentStreak)} ngày liên tiếp`,
            'Đây là số ngày có nhật ký liên tiếp theo múi giờ hiện tại của bạn.',
            { type: 'OPEN_JOURNAL' },
          ),
        );
      }
      if (
        activity.emotionCheckIn.completedToday &&
        MILESTONES.has(activity.emotionCheckIn.currentStreak)
      ) {
        commands.push(
          command(
            ownerId,
            'EMOTION_STREAK_MILESTONE',
            activity.asOfLocalDate,
            occurredAt,
            `Bạn đã ghi nhận cảm xúc ${String(activity.emotionCheckIn.currentStreak)} ngày liên tiếp`,
            'Đây là số ngày có ghi nhận cảm xúc liên tiếp, không phải đánh giá sức khỏe hay tiến triển.',
          ),
        );
      }
    }
    return commands;
  }
}
