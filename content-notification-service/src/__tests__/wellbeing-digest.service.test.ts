import { describe, expect, it, vi } from 'vitest';
import type { NotificationPreferences } from '../notification-preferences/notification-preference.types.js';
import { WellbeingDigestService } from '../wellbeing-digest/wellbeing-digest.service.js';
import { WellbeingRecipientUnavailableError } from '../wellbeing-digest/wellbeing-digest.types.js';

const preferences: NotificationPreferences = {
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
    resourceRemindersEnabled: false,
    dailyDigestTime: '19:00',
    resourceReminderTime: '18:30',
  },
  version: 1,
  updatedAt: '2026-09-30T00:00:00.000Z',
};

function service(
  overrides: {
    resources?: readonly { id: string; title: string }[];
    completed?: boolean;
    activityDate?: string;
    recipientError?: Error;
  } = {},
) {
  const repository = {
    pendingResources: vi.fn().mockResolvedValue(overrides.resources ?? []),
    claim: vi
      .fn()
      .mockResolvedValue({ id: 'claim-1', kind: 'DAILY_DIGEST', localDate: '2026-09-30' }),
    delivered: vi.fn(),
    failed: vi.fn(),
    cancelled: vi.fn(),
  };
  const delivery = { send: vi.fn().mockResolvedValue({ providerMessageId: 'provider-1' }) };
  const recipient = overrides.recipientError
    ? { getEmail: vi.fn().mockRejectedValue(overrides.recipientError) }
    : { getEmail: vi.fn().mockResolvedValue('owner@example.test') };
  return {
    repository,
    delivery,
    value: new WellbeingDigestService(
      repository as never,
      {
        get: vi.fn().mockResolvedValue({
          asOfLocalDate: overrides.activityDate ?? '2026-09-30',
          timezone: 'Asia/Ho_Chi_Minh',
          journal: {
            completedToday: overrides.completed ?? false,
            currentStreak: 0,
            longestStreak: 0,
          },
          emotionCheckIn: {
            completedToday: overrides.completed ?? false,
            currentStreak: 0,
            longestStreak: 0,
          },
          interpretation: 'FACTUAL_ACTIVITY_NOT_ADHERENCE_OR_RECOVERY',
        }),
      } as never,
      recipient as never,
      delivery,
      { now: () => new Date('2026-09-30T13:00:00.000Z') },
    ),
  };
}

describe('WellbeingDigestService', () => {
  it('sends one approved digest after the configured local time', async () => {
    const subject = service({
      resources: [{ id: '00000000-0000-4000-8000-000000000001', title: 'Thở chậm' }],
    });
    await expect(
      subject.value.deliver('10000000-0000-4000-8000-000000000001', preferences, 'correlation'),
    ).resolves.toBe(1);
    expect(subject.repository.claim).toHaveBeenCalledWith(
      expect.any(String),
      '2026-09-30',
      'Asia/Ho_Chi_Minh',
      'DAILY_DIGEST',
      { resources: 1, journalPrompt: 1, emotionPrompt: 1 },
    );
    expect(subject.delivery.send).toHaveBeenCalledWith(
      expect.objectContaining({ recipientEmail: 'owner@example.test' }),
    );
    expect(subject.repository.delivered).toHaveBeenCalledWith('claim-1', 'provider-1');
  });

  it('does not send an empty digest', async () => {
    const subject = service({ completed: true });
    await expect(
      subject.value.deliver('10000000-0000-4000-8000-000000000001', preferences, 'correlation'),
    ).resolves.toBe(0);
    expect(subject.repository.claim).not.toHaveBeenCalled();
    expect(subject.delivery.send).not.toHaveBeenCalled();
  });

  it('honors a shared email opt-out', async () => {
    const subject = service({
      resources: [{ id: '00000000-0000-4000-8000-000000000001', title: 'Thở chậm' }],
    });
    await expect(
      subject.value.deliver(
        '10000000-0000-4000-8000-000000000001',
        {
          ...preferences,
          channels: { ...preferences.channels, email: false },
        },
        'correlation',
      ),
    ).resolves.toBe(0);
    expect(subject.delivery.send).not.toHaveBeenCalled();
  });

  it('honors the shared resource content-group opt-out', async () => {
    const subject = service({
      resources: [{ id: '00000000-0000-4000-8000-000000000001', title: 'Thở chậm' }],
      completed: true,
    });
    await expect(
      subject.value.deliver(
        '10000000-0000-4000-8000-000000000001',
        {
          ...preferences,
          contentGroups: { ...preferences.contentGroups, resourceSystem: false },
          email: { ...preferences.email, resourceRemindersEnabled: true },
        },
        'correlation',
      ),
    ).resolves.toBe(0);
    expect(subject.repository.claim).not.toHaveBeenCalled();
  });

  it('fails closed when the factual activity projection is stale', async () => {
    const subject = service({ activityDate: '2026-09-29' });
    await expect(
      subject.value.deliver('10000000-0000-4000-8000-000000000001', preferences, 'correlation'),
    ).rejects.toThrow('not current');
    expect(subject.repository.claim).not.toHaveBeenCalled();
  });

  it('cancels the daily delivery when the owner is deleted or no longer eligible', async () => {
    const subject = service({
      resources: [{ id: '00000000-0000-4000-8000-000000000001', title: 'Thở chậm' }],
      recipientError: new WellbeingRecipientUnavailableError(),
    });
    await expect(
      subject.value.deliver('10000000-0000-4000-8000-000000000001', preferences, 'correlation'),
    ).resolves.toBe(0);
    expect(subject.repository.cancelled).toHaveBeenCalledWith('claim-1', 'RECIPIENT_INELIGIBLE');
    expect(subject.repository.failed).not.toHaveBeenCalled();
  });
});
