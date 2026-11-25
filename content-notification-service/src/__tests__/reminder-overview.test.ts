import { ConflictException, ServiceUnavailableException } from '@nestjs/common';
import { describe, expect, it } from 'vitest';
import {
  nextReminderEligibility,
  reminderLocalDate,
} from '../reminders/reminder-overview.policy.js';
import {
  overviewActivity,
  overviewFixture,
  overviewNow,
  overviewOwner,
  overviewPreferences,
} from './reminder-overview.fixtures.js';

describe('authoritative reminder overview', () => {
  it('reads current owner facts, suppresses completed reminders and keeps configured estimates separate', async () => {
    const fixture = overviewFixture();
    const overview = await fixture.service.get(overviewOwner, 'correlation');
    expect(fixture.activity.get).toHaveBeenCalledWith(
      overviewOwner,
      'Asia/Ho_Chi_Minh',
      'correlation',
    );
    expect(overview.today.map(({ status }) => status)).toEqual(['DUE', 'COMPLETED']);
    expect(overview.previews[0]).toMatchObject({
      state: 'ELIGIBLE',
      estimate: true,
      nextEligibleAt: overviewNow.toISOString(),
    });
    expect(overview.previews[1]).toMatchObject({ state: 'COMPLETED', nextEligibleAt: null });
    expect(overview.previews[2]).toMatchObject({
      state: 'SCHEDULED',
      nextEligibleAt: '2026-10-22T12:00:00.000Z',
    });
    expect(overview.unavailableCategories).toEqual([
      'SUPPORT_PLAN',
      'RESOURCES',
      'ASSESSMENT',
      'PUSH',
    ]);
    expect(JSON.stringify(overview)).not.toMatch(
      /currentStreak|longestStreak|achievementFacts|ownerAccountId|token|providerMessageId/,
    );
  });
  it('pauses previews without inventing completed activity or removing existing facts', async () => {
    const preferences = { ...overviewPreferences(), notificationsEnabled: false };
    const { service } = overviewFixture(preferences);
    const result = await service.get(overviewOwner, 'id');
    expect(result.today[0]?.status).toBe('DUE');
    expect(
      result.previews.every(
        (preview) => preview.state === 'PAUSED' && preview.nextEligibleAt === null,
      ),
    ).toBe(true);
  });
  it('applies current channel, category and cadence opt-outs', async () => {
    const preferences = overviewPreferences();
    const fixture = overviewFixture({
      ...preferences,
      channels: { ...preferences.channels, inApp: false },
      contentGroups: { ...preferences.contentGroups, emotionCheckIn: false },
      email: {
        ...preferences.email,
        cadence: 'WEEKLY_DIGEST',
        resourceRemindersEnabled: false,
        appointmentRemindersEnabled: false,
      },
    });
    const result = await fixture.service.get(overviewOwner, 'id');
    expect(result.today).toHaveLength(1);
    expect(result.previews.every((preview) => preview.state === 'DISABLED')).toBe(true);
  });
  it('reports disabled deployment capabilities without promising a send', async () => {
    const fixture = overviewFixture();
    fixture.configuration.REMINDER_SCHEDULER_ENABLED = false;
    fixture.configuration.WELLBEING_DIGEST_SCHEDULER_ENABLED = false;
    const result = await fixture.service.get(overviewOwner, 'id');
    expect(result.previews[0]?.state).toBe('UNAVAILABLE');
    expect(result.previews[2]?.nextEligibleAt).toBeNull();
  });
  it('retains real delivery history but does not generate another eligibility time after delivery', async () => {
    const fixture = overviewFixture();
    fixture.repository.deliveries.mockResolvedValue({
      JOURNAL_REMINDER: { state: 'DELIVERED', recordedAt: overviewNow.toISOString() },
      DAILY_DIGEST: { state: 'CLAIMED', recordedAt: overviewNow.toISOString() },
      APPOINTMENT_EMAIL: { state: 'INVALIDATED', recordedAt: overviewNow.toISOString() },
    });
    const result = await fixture.service.get(overviewOwner, 'id');
    expect(result.previews[0]).toMatchObject({ state: 'ALREADY_DELIVERED', nextEligibleAt: null });
    expect(result.previews[2]).toMatchObject({ state: 'IN_PROGRESS', nextEligibleAt: null });
    expect(result.previews[4]).toMatchObject({
      state: 'UNAVAILABLE',
      latestDelivery: { state: 'INVALIDATED' },
      nextEligibleAt: null,
    });
  });
  it('never turns a failed or cancelled delivery into a confirmed future send', async () => {
    const fixture = overviewFixture();
    fixture.repository.deliveries.mockResolvedValue({
      JOURNAL_REMINDER: { state: 'FAILED', recordedAt: overviewNow.toISOString() },
      DAILY_DIGEST: { state: 'CANCELLED', recordedAt: overviewNow.toISOString() },
    });
    const result = await fixture.service.get(overviewOwner, 'id');
    expect(result.previews[0]?.state).toBe('UNAVAILABLE');
    expect(result.previews[2]?.nextEligibleAt).toBeNull();
  });
  it('does not label an empty digest as completed work', async () => {
    const fixture = overviewFixture();
    const activity = overviewActivity();
    fixture.activity.get.mockResolvedValue({
      ...activity,
      journal: { ...activity.journal, completedToday: true },
    });
    const result = await fixture.service.get(overviewOwner, 'id');
    expect(result.previews[2]?.state).toBe('NOT_NEEDED');
    expect(result.previews[3]?.state).toBe('NOT_NEEDED');
  });
  it.each(['owner', 'date', 'timezone'])(
    'fails closed for a mismatched %s activity projection',
    async (mismatch) => {
      const fixture = overviewFixture();
      const activity = overviewActivity();
      fixture.activity.get.mockResolvedValue({
        ...activity,
        ...(mismatch === 'owner'
          ? {
              achievementFacts: {
                ...activity.achievementFacts,
                ownerAccountId: 'b13e4567-e89b-42d3-a456-426614174000',
              },
            }
          : mismatch === 'date'
            ? { asOfLocalDate: '2026-10-21' }
            : { timezone: 'UTC' }),
      });
      await expect(fixture.service.get(overviewOwner, 'id')).rejects.toBeInstanceOf(
        ServiceUnavailableException,
      );
    },
  );
  it.each(['activity', 'database', 'preferences'])(
    'returns a safe unavailable error on %s failure',
    async (failure) => {
      const fixture = overviewFixture();
      const reader =
        failure === 'activity'
          ? fixture.activity.get
          : failure === 'database'
            ? fixture.repository.deliveries
            : fixture.preferenceService.get;
      reader.mockRejectedValue(new Error('private downstream body'));
      try {
        await fixture.service.get(overviewOwner, 'id');
        expect.fail('must fail');
      } catch (error) {
        expect(error).toBeInstanceOf(ServiceUnavailableException);
        expect(JSON.stringify(error)).not.toContain('private downstream body');
      }
    },
  );
  it('rejects a preference change during composition, including a concurrent opt-out', async () => {
    const fixture = overviewFixture();
    fixture.preferenceService.get
      .mockResolvedValueOnce(fixture.preferences)
      .mockResolvedValueOnce({ ...fixture.preferences, version: 1, notificationsEnabled: false });
    await expect(fixture.service.get(overviewOwner, 'id')).rejects.toBeInstanceOf(
      ConflictException,
    );
  });
  it('rejects a local midnight crossed while resolving owner facts', async () => {
    const fixture = overviewFixture();
    fixture.clock.now
      .mockReturnValueOnce(overviewNow)
      .mockReturnValueOnce(new Date('2026-10-22T17:00:00Z'));
    await expect(fixture.service.get(overviewOwner, 'id')).rejects.toBeInstanceOf(
      ServiceUnavailableException,
    );
  });
  it('does not ask Journal for opted-out categories', async () => {
    const preferences = overviewPreferences();
    const fixture = overviewFixture({
      ...preferences,
      contentGroups: {
        ...preferences.contentGroups,
        journalReminder: false,
        emotionCheckIn: false,
      },
    });
    const result = await fixture.service.get(overviewOwner, 'id');
    expect(fixture.activity.get).not.toHaveBeenCalled();
    expect(result.today).toEqual([]);
  });
});

describe('bounded local-time eligibility', () => {
  it('defers overnight quiet hours only within the current local day', () => {
    const preferences = overviewPreferences();
    const quiet = { ...preferences, quietHours: { ...preferences.quietHours, enabled: true } };
    expect(nextReminderEligibility(quiet, new Date('2026-10-22T16:00:00Z'))).toBeNull();
    expect(nextReminderEligibility(quiet, new Date('2026-10-21T23:30:00Z'))).toBe(
      '2026-10-22T00:00:00.000Z',
    );
  });
  it('uses the provider instant and configured timezone across UTC day boundaries', () => {
    expect(reminderLocalDate(new Date('2026-10-21T18:00:00Z'), 'Asia/Ho_Chi_Minh')).toBe(
      '2026-10-22',
    );
  });
  it('handles spring DST gaps without inventing nonexistent local instants', () => {
    const preferences = overviewPreferences();
    const paris = {
      ...preferences,
      quietHours: { enabled: true, start: '01:00', end: '03:00', timeZone: 'Europe/Paris' },
    };
    expect(nextReminderEligibility(paris, new Date('2026-03-29T00:30:00Z'))).toBe(
      '2026-03-29T01:00:00.000Z',
    );
  });
  it('handles the repeated autumn hour using ordered UTC instants', () => {
    const preferences = overviewPreferences();
    const paris = {
      ...preferences,
      quietHours: { enabled: true, start: '01:00', end: '02:30', timeZone: 'Europe/Paris' },
    };
    expect(nextReminderEligibility(paris, new Date('2026-10-25T00:10:00Z'))).toBe(
      '2026-10-25T00:30:00.000Z',
    );
    expect(nextReminderEligibility(paris, new Date('2026-10-25T01:10:00Z'))).toBe(
      '2026-10-25T01:30:00.000Z',
    );
  });
});
