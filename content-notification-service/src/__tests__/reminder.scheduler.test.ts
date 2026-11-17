import { describe, expect, it, vi } from 'vitest';

import type { ServiceConfiguration } from '../configuration/configuration.js';
import type {
  NotificationPreferences,
  ReminderCandidate,
} from '../notification-preferences/notification-preference.types.js';
import { ReminderScheduler } from '../reminders/reminder.scheduler.js';

const preferences: NotificationPreferences = {
  notificationsEnabled: true,
  channels: { inApp: true, email: false, push: false },
  contentGroups: {
    journalReminder: true,
    emotionCheckIn: true,
    streakMilestone: true,
    screeningReassessment: true,
    appointmentMessage: true,
    resourceSystem: true,
    communityInteraction: true,
    supportPlanReminder: true,
  },
  dailyReminderTime: '20:00',
  quietHours: {
    enabled: false,
    start: '22:00',
    end: '07:00',
    timeZone: 'Asia/Ho_Chi_Minh',
  },
  email: {
    cadence: 'IMMEDIATE',
    wellbeingDigestEnabled: false,
    resourceRemindersEnabled: false,
    dailyDigestTime: '19:00',
    resourceReminderTime: '18:30',
    appointmentRemindersEnabled: false,
  },
  version: 0,
  updatedAt: '2026-09-28T00:00:00.000Z',
};

const candidate = (ownerId: string): ReminderCandidate => ({
  ownerId,
  preferences,
});

const configuration = {
  REMINDER_SCHEDULER_ENABLED: true,
  REMINDER_SCHEDULER_INTERVAL_MS: 60_000,
  REMINDER_SCHEDULER_BATCH_SIZE: 2,
} as ServiceConfiguration;

describe('ReminderScheduler', () => {
  it('pages persisted preferences and isolates one owner dependency failure', async () => {
    const first = candidate('11111111-1111-4111-8111-111111111111');
    const second = candidate('22222222-2222-4222-8222-222222222222');
    const third = candidate('33333333-3333-4333-8333-333333333333');
    const listReminderCandidates = vi.fn(async (afterOwnerId: string | null) =>
      afterOwnerId === null ? [first, second] : [third],
    );
    const materialize = vi.fn(async (ownerId: string) => {
      if (ownerId === second.ownerId) throw new Error('dependency unavailable');
      return 2;
    });
    const materializeSupportPlan = vi.fn(async () => 0);
    const scheduler = new ReminderScheduler(
      configuration,
      { listReminderCandidates } as never,
      { materialize, materializeSupportPlan } as never,
    );

    await expect(scheduler.runOnce()).resolves.toEqual({
      candidates: 3,
      notifications: 4,
      failures: 1,
      skippedOverlap: false,
    });
    expect(listReminderCandidates).toHaveBeenNthCalledWith(1, null, 2);
    expect(listReminderCandidates).toHaveBeenNthCalledWith(2, second.ownerId, 2);
    expect(materialize).toHaveBeenCalledTimes(3);
  });

  it('materializes the SupportPlan reminder per owner after the activity reminders', async () => {
    const first = candidate('11111111-1111-4111-8111-111111111111');
    const second = candidate('22222222-2222-4222-8222-222222222222');
    const third = candidate('33333333-3333-4333-8333-333333333333');
    const order: string[] = [];
    const materialize = vi.fn(async (ownerId: string) => {
      order.push(`activity:${ownerId}`);
      if (ownerId === first.ownerId) throw new Error('journal-ai unavailable');
      return 0;
    });
    const materializeSupportPlan = vi.fn(async (ownerId: string) => {
      order.push(`plan:${ownerId}`);
      if (ownerId === second.ownerId) throw new Error('care unavailable');
      return 1;
    });
    const scheduler = new ReminderScheduler(
      configuration,
      {
        listReminderCandidates: vi.fn(async (afterOwnerId: string | null) =>
          afterOwnerId === null ? [first, second] : [third],
        ),
      } as never,
      { materialize, materializeSupportPlan } as never,
    );

    await expect(scheduler.runOnce()).resolves.toEqual({
      candidates: 3,
      notifications: 2,
      failures: 2,
      skippedOverlap: false,
    });
    expect(order).toEqual([
      `activity:${first.ownerId}`,
      `plan:${first.ownerId}`,
      `activity:${second.ownerId}`,
      `plan:${second.ownerId}`,
      `activity:${third.ownerId}`,
      `plan:${third.ownerId}`,
    ]);
    expect(materializeSupportPlan).toHaveBeenCalledWith(
      third.ownerId,
      preferences,
      expect.any(String),
    );
  });

  it('does not overlap scheduler runs in one process', async () => {
    let finish: (() => void) | undefined;
    const blocked = new Promise<void>((resolve) => {
      finish = resolve;
    });
    const scheduler = new ReminderScheduler(
      configuration,
      {
        listReminderCandidates: vi
          .fn()
          .mockResolvedValueOnce([candidate('11111111-1111-4111-8111-111111111111')])
          .mockResolvedValue([]),
      } as never,
      {
        materialize: async () => {
          await blocked;
          return 1;
        },
        materializeSupportPlan: async () => 0,
      } as never,
    );

    const first = scheduler.runOnce();
    await expect(scheduler.runOnce()).resolves.toEqual({
      candidates: 0,
      notifications: 0,
      failures: 0,
      skippedOverlap: true,
    });
    finish?.();
    await expect(first).resolves.toMatchObject({
      candidates: 1,
      notifications: 1,
      skippedOverlap: false,
    });
  });
});
