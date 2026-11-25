import { vi } from 'vitest';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import type { NotificationPreferenceService } from '../notification-preferences/notification-preference.service.js';
import type { NotificationPreferences } from '../notification-preferences/notification-preference.types.js';
import type { NotificationActivity } from '../reminders/reminder-activity.client.js';
import type { ReminderOverviewRepository } from '../reminders/reminder-overview.repository.js';
import { ReminderOverviewService } from '../reminders/reminder-overview.service.js';
import type { WellbeingDigestRepository } from '../wellbeing-digest/wellbeing-digest.repository.js';

export const overviewOwner = 'a13e4567-e89b-42d3-a456-426614174000';
export const overviewNow = new Date('2026-10-22T03:00:00.000Z');
export function overviewPreferences(): NotificationPreferences {
  return {
    notificationsEnabled: true,
    channels: { inApp: true, email: true, push: false },
    contentGroups: {
      journalReminder: true,
      emotionCheckIn: true,
      streakMilestone: true,
      screeningReassessment: true,
      appointmentMessage: true,
      resourceSystem: true,
      communityInteraction: true,
    },
    quietHours: { enabled: false, start: '22:00', end: '07:00', timeZone: 'Asia/Ho_Chi_Minh' },
    email: {
      cadence: 'DAILY_DIGEST',
      wellbeingDigestEnabled: true,
      resourceRemindersEnabled: true,
      dailyDigestTime: '19:00',
      resourceReminderTime: '18:30',
      appointmentRemindersEnabled: true,
    },
    version: 0,
    updatedAt: overviewNow.toISOString(),
  };
}
export function overviewActivity(): NotificationActivity {
  const activity = {
    distinctDays: 0,
    longestStreak: 0,
    firstOccurredAt: null,
    streakMilestones: [
      { days: 3, reachedAt: null },
      { days: 7, reachedAt: null },
    ],
  };
  return {
    asOfLocalDate: '2026-10-22',
    timezone: 'Asia/Ho_Chi_Minh',
    journal: { completedToday: false, currentStreak: 0, longestStreak: 0 },
    emotionCheckIn: { completedToday: true, currentStreak: 1, longestStreak: 1 },
    achievementFacts: {
      ownerAccountId: overviewOwner,
      journal: activity,
      emotionCheckIn: activity,
    },
    interpretation: 'FACTUAL_ACTIVITY_NOT_ADHERENCE_OR_RECOVERY',
  };
}
export function overviewFixture(preferences = overviewPreferences()) {
  const preferenceService = { get: vi.fn(async () => preferences) };
  const activity = { get: vi.fn(async () => overviewActivity()) };
  const clock = { now: vi.fn(() => overviewNow) };
  const digest = { pendingResources: vi.fn(async () => [] as { id: string; title: string }[]) };
  const repository = {
    deliveries: vi.fn<ReminderOverviewRepository['deliveries']>(async () => ({})),
  };
  const configuration = {
    REMINDER_SCHEDULER_ENABLED: true,
    WELLBEING_DIGEST_SCHEDULER_ENABLED: true,
  } as ServiceConfiguration;
  const service = new ReminderOverviewService(
    preferenceService as unknown as NotificationPreferenceService,
    activity,
    clock,
    configuration,
    digest as unknown as WellbeingDigestRepository,
    repository as unknown as ReminderOverviewRepository,
  );
  return {
    preferences,
    preferenceService,
    activity,
    clock,
    digest,
    repository,
    configuration,
    service,
  };
}
