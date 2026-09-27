import { ServiceUnavailableException } from '@nestjs/common';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { ServiceConfiguration } from '../configuration/configuration.js';
import { JournalAiReminderActivityClient } from '../reminders/reminder-activity.client.js';

const configuration = {
  JOURNAL_AI_SERVICE_URL: 'http://journal-ai.test:3000',
  JOURNAL_AI_SERVICE_TIMEOUT_MS: 500,
} as ServiceConfiguration;

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('JournalAiReminderActivityClient', () => {
  it('forwards the owner bearer and validates the minimized projection', async () => {
    const fetch = vi.fn().mockResolvedValue(
      Response.json({
        asOfLocalDate: '2026-09-24',
        timezone: 'Asia/Ho_Chi_Minh',
        journal: { completedToday: true, currentStreak: 2, longestStreak: 4 },
        emotionCheckIn: { completedToday: false, currentStreak: 1, longestStreak: 5 },
        interpretation: 'FACTUAL_ACTIVITY_NOT_ADHERENCE_OR_RECOVERY',
      }),
    );
    vi.stubGlobal('fetch', fetch);

    const result = await new JournalAiReminderActivityClient(configuration).get(
      'owner-token',
      'Asia/Ho_Chi_Minh',
      'f872f72d-d5f1-48ce-a889-b7d1015347d6',
    );

    expect(result.journal.completedToday).toBe(true);
    const [url, request] = fetch.mock.calls[0] as [URL, RequestInit];
    expect(url.toString()).toBe(
      'http://journal-ai.test:3000/api/v1/notification-activity?timezone=Asia%2FHo_Chi_Minh',
    );
    expect(new Headers(request.headers).get('authorization')).toBe('Bearer owner-token');
    expect(new Headers(request.headers).get('x-correlation-id')).toBe(
      'f872f72d-d5f1-48ce-a889-b7d1015347d6',
    );
  });

  it('maps dependency failures and malformed projections to unavailable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ journalText: 'private' })));

    await expect(
      new JournalAiReminderActivityClient(configuration).get('token', 'UTC', 'correlation'),
    ).rejects.toBeInstanceOf(ServiceUnavailableException);
  });
});
