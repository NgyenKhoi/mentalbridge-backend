import { Inject, Injectable, ServiceUnavailableException } from '@nestjs/common';
import { z } from 'zod';

import { CONFIGURATION_TOKEN } from '../application.tokens.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';

const StreakSchema = z
  .object({
    completedToday: z.boolean(),
    currentStreak: z.number().int().min(0),
    longestStreak: z.number().int().min(0),
  })
  .strict();

const NotificationActivitySchema = z
  .object({
    asOfLocalDate: z.iso.date(),
    timezone: z.string().min(1).max(64),
    journal: StreakSchema,
    emotionCheckIn: StreakSchema,
    interpretation: z.literal('FACTUAL_ACTIVITY_NOT_ADHERENCE_OR_RECOVERY'),
  })
  .strict();

export type NotificationActivity = z.infer<typeof NotificationActivitySchema>;

export interface ReminderActivityClient {
  get(accessToken: string, timezone: string, correlationId: string): Promise<NotificationActivity>;
}

@Injectable()
export class JournalAiReminderActivityClient implements ReminderActivityClient {
  constructor(
    @Inject(CONFIGURATION_TOKEN)
    private readonly configuration: ServiceConfiguration,
  ) {}

  async get(
    accessToken: string,
    timezone: string,
    correlationId: string,
  ): Promise<NotificationActivity> {
    const controller = new AbortController();
    const timeout = setTimeout(() => {
      controller.abort();
    }, this.configuration.JOURNAL_AI_SERVICE_TIMEOUT_MS);
    try {
      const url = new URL(
        '/api/v1/notification-activity',
        this.configuration.JOURNAL_AI_SERVICE_URL,
      );
      url.searchParams.set('timezone', timezone);
      const response = await fetch(url, {
        method: 'GET',
        redirect: 'error',
        signal: controller.signal,
        headers: {
          Accept: 'application/json',
          Authorization: `Bearer ${accessToken}`,
          'X-Correlation-Id': correlationId,
        },
      });
      if (!response.ok) throw new ServiceUnavailableException();
      return NotificationActivitySchema.parse(await response.json());
    } catch (error) {
      if (error instanceof ServiceUnavailableException) throw error;
      throw new ServiceUnavailableException({ cause: error });
    } finally {
      clearTimeout(timeout);
    }
  }
}
