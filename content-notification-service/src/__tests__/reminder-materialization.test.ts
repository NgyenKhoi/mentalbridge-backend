import { describe, expect, it } from 'vitest';

import type { NotificationPreferences } from '../notification-preferences/notification-preference.types.js';
import type { NotificationCreate } from '../notifications/notification.types.js';
import type { NotificationActivity } from '../reminders/reminder-activity.client.js';
import {
  ReminderMaterializationService,
  isQuietHour,
  localDayStart,
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
  },
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
  const service = new ReminderMaterializationService(
    { get: async () => currentPreferences } as never,
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
  );
  return {
    service,
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

    await expect(subject.service.materialize(OWNER, 'token', 'correlation')).resolves.toBe(1);
    expect([...subject.created.values()].map((item) => item.kind)).toEqual([
      'EMOTION_CHECKIN_REMINDER',
    ]);
  });

  it('uses stable local-day commands so later duplicate retries retain one identity and payload', async () => {
    const subject = fixture();

    await subject.service.materialize(OWNER, 'token', 'correlation');
    subject.setNow(new Date('2026-09-24T15:30:00.000Z'));
    await subject.service.materialize(OWNER, 'token', 'correlation');

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

    await expect(subject.service.materialize(OWNER, 'token', 'correlation')).resolves.toBe(2);
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

    await subject.service.materialize(OWNER, 'token', 'correlation');
    await subject.service.materialize(OWNER, 'token', 'correlation');
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

    await expect(subject.service.materialize(OWNER, 'token', 'correlation')).resolves.toBe(0);
    expect(subject.created.size).toBe(0);
  });

  it('uses shared preference and quiet-hour policy before reading owner activity', async () => {
    const disabled = fixture(preferences({ notificationsEnabled: false }));
    expect(await disabled.service.materialize(OWNER, 'token', 'correlation')).toBe(0);
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
    expect(await quiet.service.materialize(OWNER, 'token', 'correlation')).toBe(0);
    expect(quiet.activityReads()).toBe(0);
  });

  it('fails closed when activity attribution does not match the requested local day', async () => {
    const subject = fixture(preferences(), activity({ asOfLocalDate: '2026-09-23' }));
    await expect(subject.service.materialize(OWNER, 'token', 'correlation')).resolves.toBe(0);
    expect(subject.created.size).toBe(0);
  });

  it('fails without creating a notification when authoritative activity is unavailable', async () => {
    const subject = fixture(preferences(), activity(), true);
    await expect(subject.service.materialize(OWNER, 'token', 'correlation')).rejects.toThrow(
      'unavailable',
    );
    expect(subject.created.size).toBe(0);
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
