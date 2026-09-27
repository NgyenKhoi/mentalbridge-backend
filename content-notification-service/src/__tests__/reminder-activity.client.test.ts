import { ServiceUnavailableException } from '@nestjs/common';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { ServiceConfiguration } from '../configuration/configuration.js';
import { JournalAiReminderActivityClient } from '../reminders/reminder-activity.client.js';

const configuration = {
  JOURNAL_AI_SERVICE_URL: 'http://journal-ai.test:3000',
  JOURNAL_AI_SERVICE_TIMEOUT_MS: 500,
  JOURNAL_AI_REMINDER_SERVICE_TOKEN: 'test-reminder-service-token-at-least-32-characters',
} as ServiceConfiguration;

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('JournalAiReminderActivityClient', () => {
  it('uses the scoped service credential and validates the minimized projection', async () => {
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
      'a13e4567-e89b-42d3-a456-426614174000',
      'Asia/Ho_Chi_Minh',
      'f872f72d-d5f1-48ce-a889-b7d1015347d6',
    );

    expect(result.journal.completedToday).toBe(true);
    const [url, request] = fetch.mock.calls[0] as [URL, RequestInit];
    expect(url.toString()).toBe('http://journal-ai.test:3000/internal/v1/notification-activity');
    expect(request.method).toBe('POST');
    expect(new Headers(request.headers).get('x-mentalbridge-service-token')).toBe(
      configuration.JOURNAL_AI_REMINDER_SERVICE_TOKEN,
    );
    expect(new Headers(request.headers).get('x-correlation-id')).toBe(
      'f872f72d-d5f1-48ce-a889-b7d1015347d6',
    );
    expect(JSON.parse(String(request.body))).toEqual({
      ownerAccountId: 'a13e4567-e89b-42d3-a456-426614174000',
      timezone: 'Asia/Ho_Chi_Minh',
    });
  });

  it('maps dependency failures and malformed projections to unavailable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ journalText: 'private' })));

    await expect(
      new JournalAiReminderActivityClient(configuration).get(
        'a13e4567-e89b-42d3-a456-426614174000',
        'UTC',
        'correlation',
      ),
    ).rejects.toBeInstanceOf(ServiceUnavailableException);
  });
});
