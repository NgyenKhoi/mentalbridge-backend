import { describe, expect, it } from 'vitest';

import { calculateAchievements } from '../achievements/achievement.policy.js';
import type { AchievementResourceFact } from '../achievements/achievement.types.js';

const activity = {
  journal: {
    distinctDays: 7,
    longestStreak: 3,
    firstOccurredAt: '2026-10-01T01:00:00.000Z',
    streakMilestones: [
      { days: 3, reachedAt: '2026-10-03T01:00:00.000Z' },
      { days: 7, reachedAt: null },
    ],
  },
  emotionCheckIn: {
    distinctDays: 1,
    longestStreak: 1,
    firstOccurredAt: '2026-10-02T01:00:00.000Z',
    streakMilestones: [
      { days: 3, reachedAt: null },
      { days: 7, reachedAt: null },
    ],
  },
} as const;

function resource(
  kind: AchievementResourceFact['kind'],
  resourceId: string,
  localDate: string,
): AchievementResourceFact {
  return {
    kind,
    resourceId,
    contentVersion: '1',
    localDate,
    occurredAt: `${localDate}T02:00:00.000Z`,
  };
}

describe('achievement policy v1', () => {
  it('keeps domains separate and derives only frozen owner facts', () => {
    const items = calculateAchievements({
      activity,
      resourceFacts: [
        resource('RESOURCE_COMPLETION', 'resource-a', '2026-10-01'),
        resource('RESOURCE_COMPLETION', 'resource-b', '2026-10-02'),
        resource('RESOURCE_COMPLETION', 'resource-c', '2026-10-03'),
        resource('VERIFIED_PRACTICE', 'resource-a', '2026-10-01'),
        resource('VERIFIED_PRACTICE', 'resource-a', '2026-10-01'),
        resource('VERIFIED_PRACTICE', 'resource-b', '2026-10-04'),
        resource('VERIFIED_PRACTICE', 'resource-c', '2026-10-07'),
      ],
    });

    expect(
      items.find(({ definitionId }) => definitionId === 'journal.streak-3-days'),
    ).toMatchObject({ status: 'EARNED', unlockedAt: '2026-10-03T01:00:00.000Z' });
    expect(
      items.find(({ definitionId }) => definitionId === 'journal.streak-7-days'),
    ).toMatchObject({ status: 'UNEARNED', progress: { current: 3, target: 7 } });
    expect(
      items.find(({ definitionId }) => definitionId === 'emotion.first-check-in'),
    ).toMatchObject({ status: 'EARNED' });
    expect(items.find(({ definitionId }) => definitionId === 'resource.variety-3')).toMatchObject({
      status: 'EARNED',
      unlockedAt: '2026-10-03T02:00:00.000Z',
    });
    expect(
      items.find(({ definitionId }) => definitionId === 'resource.practice-3-days'),
    ).toMatchObject({ status: 'EARNED', progress: { current: 3, target: 3 } });
    expect(items.find(({ definitionId }) => definitionId === 'journey.new-chapter')).toMatchObject({
      status: 'DEFERRED',
      unlockedAt: null,
      evidenceClass: 'USER_CONFIRMED_JOURNEY',
    });
  });

  it('does not double-count same-resource completions or same-day practice edits', () => {
    const items = calculateAchievements({
      activity,
      resourceFacts: [
        resource('RESOURCE_COMPLETION', 'resource-a', '2026-10-01'),
        resource('RESOURCE_COMPLETION', 'resource-a', '2026-10-02'),
        resource('VERIFIED_PRACTICE', 'resource-a', '2026-10-01'),
        resource('VERIFIED_PRACTICE', 'resource-b', '2026-10-01'),
      ],
    });
    expect(
      items.find(({ definitionId }) => definitionId === 'resource.variety-3')?.progress,
    ).toEqual({
      current: 1,
      target: 3,
      unit: 'DISTINCT_RESOURCE',
    });
    expect(
      items.find(({ definitionId }) => definitionId === 'resource.practice-3-days')?.progress,
    ).toEqual({ current: 1, target: 3, unit: 'PRACTICE_DAY' });
  });

  it('fails journal and emotion closed while preserving resource facts', () => {
    const items = calculateAchievements({
      activity: null,
      resourceFacts: [resource('RESOURCE_COMPLETION', 'resource-a', '2026-10-01')],
    });
    expect(
      items.filter(({ domain }) => domain === 'JOURNAL' || domain === 'EMOTION_CHECK_IN'),
    ).toSatisfy((values) =>
      values.every(({ status, progress }) => status === 'SOURCE_UNAVAILABLE' && progress === null),
    );
    expect(
      items.find(({ definitionId }) => definitionId === 'resource.first-completion')?.status,
    ).toBe('EARNED');
  });
});
