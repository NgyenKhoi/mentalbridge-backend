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

const AchievementActivitySchema = z
  .object({
    distinctDays: z.number().int().min(0),
    longestStreak: z.number().int().min(0),
    firstOccurredAt: z.iso.datetime().nullable(),
    streakMilestones: z
      .array(
        z
          .object({
            days: z.number().int().positive(),
            reachedAt: z.iso.datetime().nullable(),
          })
          .strict(),
      )
      .max(16),
  })
  .strict()
  .superRefine((value, context) => {
    const thresholds = value.streakMilestones.map(({ days }) => days);
    if (
      thresholds.length !== 2 ||
      thresholds[0] !== 3 ||
      thresholds[1] !== 7 ||
      value.longestStreak > value.distinctDays ||
      (value.distinctDays === 0) !== (value.firstOccurredAt === null) ||
      value.streakMilestones.some(
        ({ days, reachedAt }) => value.longestStreak >= days !== (reachedAt !== null),
      )
    ) {
      context.addIssue({ code: 'custom', message: 'Inconsistent achievement activity facts' });
    }
  });

const NotificationActivitySchema = z
  .object({
    asOfLocalDate: z.iso.date(),
    timezone: z.string().min(1).max(64),
    journal: StreakSchema,
    emotionCheckIn: StreakSchema,
    achievementFacts: z
      .object({
        ownerAccountId: z.uuid(),
        journal: AchievementActivitySchema,
        emotionCheckIn: AchievementActivitySchema,
      })
      .strict(),
    interpretation: z.literal('FACTUAL_ACTIVITY_NOT_ADHERENCE_OR_RECOVERY'),
  })
  .strict();

export type NotificationActivity = z.infer<typeof NotificationActivitySchema>;

export interface ReminderActivityClient {
  get(
    ownerAccountId: string,
    timezone: string,
    correlationId: string,
  ): Promise<NotificationActivity>;
}

@Injectable()
export class JournalAiReminderActivityClient implements ReminderActivityClient {
  constructor(
    @Inject(CONFIGURATION_TOKEN)
    private readonly configuration: ServiceConfiguration,
  ) {}

  async get(
    ownerAccountId: string,
    timezone: string,
    correlationId: string,
  ): Promise<NotificationActivity> {
    const controller = new AbortController();
    const timeout = setTimeout(() => {
      controller.abort();
    }, this.configuration.JOURNAL_AI_SERVICE_TIMEOUT_MS);
    try {
      const url = new URL(
        '/internal/v1/notification-activity',
        this.configuration.JOURNAL_AI_SERVICE_URL,
      );
      const serviceToken = this.configuration.JOURNAL_AI_REMINDER_SERVICE_TOKEN;
      if (!serviceToken) throw new ServiceUnavailableException();
      const response = await fetch(url, {
        method: 'POST',
        redirect: 'error',
        signal: controller.signal,
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
          'X-MentalBridge-Service-Token': serviceToken,
          'X-Correlation-Id': correlationId,
        },
        body: JSON.stringify({ ownerAccountId, timezone }),
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
