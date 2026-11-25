import { randomUUID } from 'node:crypto';
import { Inject, Injectable, ServiceUnavailableException } from '@nestjs/common';

import {
  ACHIEVEMENT_REPOSITORY_TOKEN,
  REMINDER_ACTIVITY_CLIENT_TOKEN,
} from '../application.tokens.js';
import type { ReminderActivityClient } from '../reminders/reminder-activity.client.js';
import { calculateAchievements, ACHIEVEMENT_POLICY_VERSION } from './achievement.policy.js';
import {
  AchievementPreferenceVersionMismatchError,
  type AchievementRepository,
} from './achievement.repository.js';
import type { AchievementCollection, AchievementVisibility } from './achievement.types.js';

@Injectable()
export class AchievementService {
  constructor(
    @Inject(ACHIEVEMENT_REPOSITORY_TOKEN)
    private readonly repository: AchievementRepository,
    @Inject(REMINDER_ACTIVITY_CLIENT_TOKEN)
    private readonly activityClient: ReminderActivityClient,
  ) {}

  async read(ownerId: string, timezone: string): Promise<AchievementCollection> {
    let source;
    try {
      source = await this.repository.read(ownerId);
    } catch {
      throw new ServiceUnavailableException();
    }

    let activity: Awaited<ReturnType<ReminderActivityClient['get']>> | null = null;
    try {
      const response = await this.activityClient.get(ownerId, timezone, randomUUID());
      if (response.timezone === timezone && response.achievementFacts.ownerAccountId === ownerId) {
        activity = response;
      }
    } catch {
      activity = null;
    }
    const facts = activity?.achievementFacts ?? null;
    return {
      visibilityState: source.preference.visibilityState,
      preferenceVersion: source.preference.version,
      policyVersion: ACHIEVEMENT_POLICY_VERSION,
      calculatedAt: new Date().toISOString(),
      sources: [
        {
          domain: 'JOURNAL',
          availability: facts ? 'AVAILABLE' : 'UNAVAILABLE',
          asOfLocalDate: facts ? (activity?.asOfLocalDate ?? null) : null,
        },
        {
          domain: 'EMOTION_CHECK_IN',
          availability: facts ? 'AVAILABLE' : 'UNAVAILABLE',
          asOfLocalDate: facts ? (activity?.asOfLocalDate ?? null) : null,
        },
        { domain: 'RESOURCE', availability: 'AVAILABLE', asOfLocalDate: null },
        { domain: 'JOURNEY', availability: 'DEFERRED', asOfLocalDate: null },
      ],
      items: calculateAchievements({ activity: facts, resourceFacts: source.facts }),
    };
  }

  async setVisibility(
    ownerId: string,
    timezone: string,
    visibilityState: AchievementVisibility,
    expectedVersion: string,
  ): Promise<AchievementCollection> {
    try {
      await this.repository.setVisibility(ownerId, visibilityState, expectedVersion);
    } catch (error) {
      if (error instanceof AchievementPreferenceVersionMismatchError) throw error;
      throw new ServiceUnavailableException();
    }
    return this.read(ownerId, timezone);
  }
}
