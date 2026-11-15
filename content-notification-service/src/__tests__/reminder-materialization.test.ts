import { describe, expect, it } from 'vitest';

import type { NotificationPreferences } from '../notification-preferences/notification-preference.types.js';
import type { NotificationCreate } from '../notifications/notification.types.js';
import type { NotificationActivity } from '../reminders/reminder-activity.client.js';
import {
  ReminderMaterializationService,
  isQuietHour,
  localDayStart,
  reminderVariant,
} from '../reminders/reminder-materialization.service.js';

const OWNER = 'dc8b2305-9ba0-42f8-a98d-7aa93dd2dbdf';
const NOW = new Date('2026-09-24T13:00:00.000Z');

const preferences = (
  overrides: Partial<NotificationPreferences> = {},
): NotificationPreferences => ({
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
  updatedAt: NOW.toISOString(),
  ...overrides,
});

const activity = (overrides: Partial<NotificationActivity> = {}): NotificationActivity => ({
  asOfLocalDate: '2026-09-24',
  timezone: 'Asia/Ho_Chi_Minh',
  journal: { completedToday: false, currentStreak: 2, longestStreak: 4 },
  emotionCheckIn: { completedToday: false, currentStreak: 1, longestStreak: 5 },
  achievementFacts: {
    ownerAccountId: OWNER,
    journal: {
      distinctDays: 4,
      longestStreak: 4,
      firstOccurredAt: '2026-09-20T01:00:00.000Z',
      streakMilestones: [
        { days: 3, reachedAt: '2026-09-22T01:00:00.000Z' },
        { days: 7, reachedAt: null },
      ],
    },
    emotionCheckIn: {
      distinctDays: 5,
      longestStreak: 5,
      firstOccurredAt: '2026-09-18T01:00:00.000Z',
      streakMilestones: [
        { days: 3, reachedAt: '2026-09-20T01:00:00.000Z' },
        { days: 7, reachedAt: null },
      ],
    },
  },
  interpretation: 'FACTUAL_ACTIVITY_NOT_ADHERENCE_OR_RECOVERY',
  ...overrides,
});

function fixture(
  currentPreferences = preferences(),
  initialActivity = activity(),
  failActivity = false,
) {
  const created = new Map<string, NotificationCreate>();
  const attempts: NotificationCreate[] = [];
  let activityReads = 0;
  let currentActivity = initialActivity;
  let now = NOW;
  const plan = {
    configured: true,
    outcome: true as boolean | Error,
    calls: [] as Array<{ ownerId: string; asOf: Date }>,
  };
  const service = new ReminderMaterializationService(
    {
      async get() {
        activityReads += 1;
        if (failActivity) throw new Error('unavailable');
        return currentActivity;
      },
    },
    {
      async create(command: NotificationCreate) {
        attempts.push(command);
        created.set(command.sourceIdentity, command);
        return {} as never;
      },
    } as never,
    { now: () => now },
    {
      isConfigured: () => plan.configured,
      async eligible(ownerId: string, asOf: Date) {
        plan.calls.push({ ownerId, asOf });
        if (plan.outcome instanceof Error) throw plan.outcome;
        return plan.outcome;
      },
    } as never,
  );
  return {
    service,
    plan,
    preferences: currentPreferences,
    created,
    attempts,
    activityReads: () => activityReads,
    setActivity: (value: NotificationActivity) => {
      currentActivity = value;
    },
    setNow: (value: Date) => {
      now = value;
    },
  };
}

describe('ReminderMaterializationService', () => {
  it('creates independent daily reminders only for activity not completed today', async () => {
    const subject = fixture(
      preferences(),
      activity({ journal: { completedToday: true, currentStreak: 3, longestStreak: 3 } }),
    );

    await expect(
      subject.service.materialize(OWNER, subject.preferences, 'correlation'),
    ).resolves.toBe(1);
    expect([...subject.created.values()].map((item) => item.kind)).toEqual([
      'EMOTION_CHECKIN_REMINDER',
    ]);
  });

  it('titles daily reminders with the owner-day variant and keeps the bodies', async () => {
    const variants = new Set<0 | 1>();
    for (const localDate of [
      '2026-09-24',
      '2026-09-25',
      '2026-09-26',
      '2026-09-27',
      '2026-09-28',
    ]) {
      const subject = fixture(preferences(), activity({ asOfLocalDate: localDate }));
      subject.setNow(new Date(`${localDate}T13:00:00.000Z`));
      await subject.service.materialize(OWNER, subject.preferences, 'correlation');
      const variant = reminderVariant(OWNER, localDate);
      variants.add(variant);
      const byKind = (kind: string) => subject.created.get(`${kind}:${localDate}`);
      expect(byKind('JOURNAL_REMINDER')).toMatchObject({
        title: ['Bạn chưa viết nhật ký hôm nay', 'Đừng quên viết vài dòng nhật ký hôm nay nhé'][
          variant
        ],
        body: 'Nhật ký hôm nay vẫn đang mở nếu bạn muốn ghi lại điều mình đang nghĩ.',
      });
      expect(byKind('EMOTION_CHECKIN_REMINDER')).toMatchObject({
        title: ['Bạn chưa ghi nhận cảm xúc hôm nay', 'Đừng quên ghi nhận cảm xúc hôm nay nhé'][
          variant
        ],
        body: 'Một lần ghi nhận ngắn giúp bạn lưu lại cảm nhận của chính mình trong ngày.',
      });
    }
    expect(variants).toEqual(new Set([0, 1]));
  });

  it('uses stable local-day commands so later duplicate retries retain one identity and payload', async () => {
    const subject = fixture();

    await subject.service.materialize(OWNER, subject.preferences, 'correlation');
    subject.setNow(new Date('2026-09-24T15:30:00.000Z'));
    await subject.service.materialize(OWNER, subject.preferences, 'correlation');

    expect(subject.created.size).toBe(2);
    expect([...subject.created.keys()].sort()).toEqual([
      'EMOTION_CHECKIN_REMINDER:2026-09-24',
      'JOURNAL_REMINDER:2026-09-24',
    ]);
    expect(subject.attempts[0]).toEqual(subject.attempts[2]);
    expect(subject.attempts[1]).toEqual(subject.attempts[3]);
  });

  it("creates factual 7, 14, and 30-day milestones only after today's activity", async () => {
    const subject = fixture(
      preferences({
        contentGroups: {
          ...preferences().contentGroups,
          journalReminder: false,
          emotionCheckIn: false,
        },
      }),
      activity({
        journal: { completedToday: true, currentStreak: 7, longestStreak: 7 },
        emotionCheckIn: { completedToday: true, currentStreak: 14, longestStreak: 14 },
      }),
    );

    await expect(
      subject.service.materialize(OWNER, subject.preferences, 'correlation'),
    ).resolves.toBe(2);
    expect([...subject.created.values()].map((item) => item.kind)).toEqual([
      'JOURNAL_STREAK_MILESTONE',
      'EMOTION_STREAK_MILESTONE',
    ]);
    expect([...subject.created.values()].map((item) => item.body).join(' ')).not.toMatch(
      /chẩn đoán|hồi phục|tuân thủ|cải thiện/i,
    );
  });

  it('does not repeat a milestone for a same-day edit represented by the same activity date', async () => {
    const subject = fixture(
      preferences({
        contentGroups: {
          ...preferences().contentGroups,
          journalReminder: false,
          emotionCheckIn: false,
        },
      }),
      activity({
        emotionCheckIn: { completedToday: true, currentStreak: 7, longestStreak: 7 },
      }),
    );

    await subject.service.materialize(OWNER, subject.preferences, 'correlation');
    await subject.service.materialize(OWNER, subject.preferences, 'correlation');
    expect(subject.created.size).toBe(1);
    expect([...subject.created.values()][0]?.kind).toBe('EMOTION_STREAK_MILESTONE');
  });

  it('suppresses a reminder when late activity is visible before materialization', async () => {
    const onlyJournal = preferences({
      contentGroups: {
        ...preferences().contentGroups,
        emotionCheckIn: false,
        streakMilestone: false,
      },
    });
    const subject = fixture(onlyJournal);
    subject.setActivity(
      activity({ journal: { completedToday: true, currentStreak: 3, longestStreak: 4 } }),
    );

    await expect(
      subject.service.materialize(OWNER, subject.preferences, 'correlation'),
    ).resolves.toBe(0);
    expect(subject.created.size).toBe(0);
  });

  it('uses shared preference and quiet-hour policy before reading owner activity', async () => {
    const disabled = fixture(preferences({ notificationsEnabled: false }));
    expect(await disabled.service.materialize(OWNER, disabled.preferences, 'correlation')).toBe(0);
    expect(disabled.activityReads()).toBe(0);

    const quiet = fixture(
      preferences({
        quietHours: {
          enabled: true,
          start: '19:00',
          end: '07:00',
          timeZone: 'Asia/Ho_Chi_Minh',
        },
      }),
    );
    expect(await quiet.service.materialize(OWNER, quiet.preferences, 'correlation')).toBe(0);
    expect(quiet.activityReads()).toBe(0);
  });

  it('fails closed when activity attribution does not match the requested local day', async () => {
    const subject = fixture(preferences(), activity({ asOfLocalDate: '2026-09-23' }));
    await expect(
      subject.service.materialize(OWNER, subject.preferences, 'correlation'),
    ).resolves.toBe(0);
    expect(subject.created.size).toBe(0);
  });

  it('fails without creating a notification when authoritative activity is unavailable', async () => {
    const subject = fixture(preferences(), activity(), true);
    await expect(
      subject.service.materialize(OWNER, subject.preferences, 'correlation'),
    ).rejects.toThrow('unavailable');
    expect(subject.created.size).toBe(0);
  });
});

describe('ReminderMaterializationService.materializeSupportPlan', () => {
  const BEFORE_DUE = new Date('2026-09-24T12:59:00.000Z');

  it('waits for the owner daily reminder time without asking Care', async () => {
    const subject = fixture();
    subject.setNow(BEFORE_DUE);

    await expect(
      subject.service.materializeSupportPlan(OWNER, subject.preferences, 'correlation'),
    ).resolves.toBe(0);
    expect(subject.plan.calls).toHaveLength(0);
    expect(subject.created.size).toBe(0);
  });

  it('creates one generic plan reminder at the due time when Care says eligible', async () => {
    const subject = fixture();

    await expect(
      subject.service.materializeSupportPlan(OWNER, subject.preferences, 'correlation'),
    ).resolves.toBe(1);
    expect(subject.plan.calls).toEqual([{ ownerId: OWNER, asOf: NOW }]);
    expect([...subject.created.values()]).toEqual([
      {
        ownerId: OWNER,
        kind: 'SUPPORT_PLAN_DAILY_REMINDER',
        title: 'Bạn có hoạt động trong kế hoạch hôm nay',
        body: 'Mở MentalBridge để xem kế hoạch hỗ trợ hôm nay.',
        occurredAt: '2026-09-23T17:00:00.000Z',
        action: { type: 'OPEN_SUPPORT_PLAN' },
        source: 'CARE_SUPPORT_PLAN',
        sourceIdentity: 'SUPPORT_PLAN_DAILY_REMINDER:2026-09-24',
        priority: 'NORMAL',
      },
    ]);

    subject.setNow(new Date('2026-09-24T15:00:00.000Z'));
    await expect(
      subject.service.materializeSupportPlan(OWNER, subject.preferences, 'correlation'),
    ).resolves.toBe(0);
    expect(subject.plan.calls).toHaveLength(1);
    expect(subject.attempts).toHaveLength(1);
  });

  it('uses the stored owner time instead of a server-wide time', async () => {
    const subject = fixture(preferences({ dailyReminderTime: '07:30' }));
    subject.setNow(new Date('2026-09-24T00:29:00.000Z'));
    await subject.service.materializeSupportPlan(OWNER, subject.preferences, 'correlation');
    expect(subject.plan.calls).toHaveLength(0);

    subject.setNow(new Date('2026-09-24T00:30:00.000Z'));
    await expect(
      subject.service.materializeSupportPlan(OWNER, subject.preferences, 'correlation'),
    ).resolves.toBe(1);
  });

  it('settles the day after a definite ineligible answer', async () => {
    const subject = fixture();
    subject.plan.outcome = false;

    await expect(
      subject.service.materializeSupportPlan(OWNER, subject.preferences, 'correlation'),
    ).resolves.toBe(0);
    subject.plan.outcome = true;
    await expect(
      subject.service.materializeSupportPlan(OWNER, subject.preferences, 'correlation'),
    ).resolves.toBe(0);
    expect(subject.plan.calls).toHaveLength(1);
    expect(subject.created.size).toBe(0);
  });

  it('retries the same day when Care is unavailable and creates nothing speculative', async () => {
    const subject = fixture();
    subject.plan.outcome = new Error('care unavailable');

    await expect(
      subject.service.materializeSupportPlan(OWNER, subject.preferences, 'correlation'),
    ).rejects.toThrow('care unavailable');
    expect(subject.created.size).toBe(0);

    subject.plan.outcome = true;
    await expect(
      subject.service.materializeSupportPlan(OWNER, subject.preferences, 'correlation'),
    ).resolves.toBe(1);
    expect(subject.plan.calls).toHaveLength(2);
  });

  it('asks Care again on the next local day', async () => {
    const subject = fixture();
    subject.plan.outcome = false;
    await subject.service.materializeSupportPlan(OWNER, subject.preferences, 'correlation');

    subject.setNow(new Date('2026-09-25T13:00:00.000Z'));
    subject.plan.outcome = true;
    await expect(
      subject.service.materializeSupportPlan(OWNER, subject.preferences, 'correlation'),
    ).resolves.toBe(1);
    expect(subject.plan.calls).toHaveLength(2);
    expect([...subject.created.keys()]).toEqual(['SUPPORT_PLAN_DAILY_REMINDER:2026-09-25']);
  });

  it.each([
    [
      'group off',
      preferences({
        contentGroups: { ...preferences().contentGroups, supportPlanReminder: false },
      }),
    ],
    ['in-app off', preferences({ channels: { inApp: false, email: false, push: true } })],
    ['master off', preferences({ notificationsEnabled: false })],
    [
      'quiet hour now',
      preferences({
        quietHours: { enabled: true, start: '19:00', end: '07:00', timeZone: 'Asia/Ho_Chi_Minh' },
      }),
    ],
  ])('skips without asking Care when %s', async (_name, ownerPreferences) => {
    const subject = fixture(ownerPreferences);

    await expect(
      subject.service.materializeSupportPlan(OWNER, subject.preferences, 'correlation'),
    ).resolves.toBe(0);
    expect(subject.plan.calls).toHaveLength(0);
  });

  it('stays off without asking Care when the Care credential is not configured', async () => {
    const subject = fixture();
    subject.plan.configured = false;

    await expect(
      subject.service.materializeSupportPlan(OWNER, subject.preferences, 'correlation'),
    ).resolves.toBe(0);
    expect(subject.plan.calls).toHaveLength(0);
  });
});

describe('reminder variant', () => {
  it('is stable for one owner and local date and covers both variants across dates', () => {
    expect(reminderVariant(OWNER, '2026-09-24')).toBe(reminderVariant(OWNER, '2026-09-24'));
    const dates = Array.from(
      { length: 14 },
      (_, day) => `2026-10-${String(day + 1).padStart(2, '0')}`,
    );
    expect(new Set(dates.map((date) => reminderVariant(OWNER, date)))).toEqual(new Set([0, 1]));
  });
});

describe('reminder local-time policy', () => {
  it('handles overnight and same-day quiet windows in the persisted timezone', () => {
    expect(
      isQuietHour(
        preferences({
          quietHours: {
            enabled: true,
            start: '22:00',
            end: '07:00',
            timeZone: 'Asia/Ho_Chi_Minh',
          },
        }),
        new Date('2026-09-24T16:30:00.000Z'),
      ),
    ).toBe(true);
    expect(
      isQuietHour(
        preferences({
          quietHours: {
            enabled: true,
            start: '12:00',
            end: '14:00',
            timeZone: 'Asia/Ho_Chi_Minh',
          },
        }),
        NOW,
      ),
    ).toBe(false);
  });

  it('uses a deterministic instant at the start of the requested local day', () => {
    expect(localDayStart('2026-09-24', 'Asia/Ho_Chi_Minh')).toBe('2026-09-23T17:00:00.000Z');
    expect(localDayStart('2026-11-01', 'America/New_York')).toBe('2026-11-01T04:00:00.000Z');
  });
});
