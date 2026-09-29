import { describe, expect, it } from 'vitest';

import {
  mondayOf,
  planBingoResources,
  planDailyResources,
  practiceStreak,
} from '../resources/resource-journey.planner.js';
import type { PlannerResource } from '../resources/resource-journey.types.js';

const resources: PlannerResource[] = [
  {
    id: '00000000-0000-4000-8000-000000000201',
    resourceKind: 'LEARNING',
    repeatability: 'ONE_TIME',
    cooldownDays: 0,
    streakEligible: false,
    planTags: ['DEPRESSIVE_SYMPTOMS'],
  },
  {
    id: '00000000-0000-4000-8000-000000000202',
    resourceKind: 'LEARNING',
    repeatability: 'ONE_TIME',
    cooldownDays: 0,
    streakEligible: false,
    planTags: ['ANXIETY_SYMPTOMS'],
  },
  {
    id: '00000000-0000-4000-8000-000000000203',
    resourceKind: 'PRACTICE',
    repeatability: 'REPEATABLE',
    cooldownDays: 1,
    streakEligible: true,
    planTags: ['ANXIETY_SYMPTOMS'],
  },
  {
    id: '00000000-0000-4000-8000-000000000204',
    resourceKind: 'HABIT',
    repeatability: 'REPEATABLE',
    cooldownDays: 1,
    streakEligible: true,
    planTags: ['DEPRESSIVE_SYMPTOMS'],
  },
  {
    id: '00000000-0000-4000-8000-000000000205',
    resourceKind: 'REFLECTION',
    repeatability: 'REPEATABLE',
    cooldownDays: 0,
    streakEligible: false,
    planTags: ['DEPRESSIVE_SYMPTOMS'],
  },
];

describe('resource journey planner', () => {
  it('keeps a balanced plan-selected daily mix and never repeats completed learning', () => {
    const result = planDailyResources(resources, {
      localDate: '2026-09-30',
      selectedResourceIds: new Set([resources[1].id]),
      completedLearningIds: new Set([resources[0].id]),
      latestSessionByResource: new Map(),
    });

    expect(result).toHaveLength(4);
    expect(result[0]).toEqual({ resourceId: resources[1].id, reason: 'PLAN_SELECTED' });
    expect(result.map((item) => item.resourceId)).not.toContain(resources[0].id);
    expect(new Set(result.map((item) => item.resourceId)).size).toBe(result.length);
  });

  it('keeps bingo stable and prioritizes repeatable resources', () => {
    const first = planBingoResources(resources, new Set([resources[4].id]), '2026-09-28');
    const replay = planBingoResources(resources, new Set([resources[4].id]), '2026-09-28');
    expect(replay).toEqual(first);
    expect(first.slice(0, 3)).toContain(resources[4].id);
  });

  it('separates practice streak from learning and daily checklist completion', () => {
    expect(practiceStreak(['2026-09-27', '2026-09-28', '2026-09-29'], '2026-09-30')).toBe(3);
    expect(practiceStreak(['2026-09-27', '2026-09-29'], '2026-09-30')).toBe(1);
    expect(mondayOf('2026-10-04')).toBe('2026-09-28');
  });

  it('keeps a fourteen-day binge journey stable after one-time learning is exhausted', () => {
    const completedLearningIds = new Set<string>();
    const latestSessionByResource = new Map<string, string>();
    const firstRun = new Map<string, readonly { resourceId: string; reason: string }[]>();
    const contexts = new Map<
      string,
      {
        completedLearningIds: Set<string>;
        latestSessionByResource: Map<string, string>;
      }
    >();

    for (let day = 0; day < 14; day += 1) {
      const date = new Date(Date.UTC(2026, 8, 30 + day)).toISOString().slice(0, 10);
      contexts.set(date, {
        completedLearningIds: new Set(completedLearningIds),
        latestSessionByResource: new Map(latestSessionByResource),
      });
      const assignment = planDailyResources(resources, {
        localDate: date,
        selectedResourceIds: new Set([resources[1].id, resources[3].id]),
        completedLearningIds,
        latestSessionByResource,
      });

      firstRun.set(date, assignment);
      expect(new Set(assignment.map((item) => item.resourceId)).size).toBe(assignment.length);
      expect(
        assignment.every((item) => resources.some((resource) => resource.id === item.resourceId)),
      ).toBe(true);

      if (day === 0) {
        resources
          .filter((resource) => resource.resourceKind === 'LEARNING')
          .forEach((resource) => completedLearningIds.add(resource.id));
      } else {
        expect(assignment.some((item) => completedLearningIds.has(item.resourceId))).toBe(false);
      }

      assignment
        .filter(
          (item) =>
            resources.find((resource) => resource.id === item.resourceId)?.repeatability ===
            'REPEATABLE',
        )
        .forEach((item) => latestSessionByResource.set(item.resourceId, date));
    }

    for (const [date, assignment] of firstRun) {
      const context = contexts.get(date)!;
      expect(
        planDailyResources(resources, {
          localDate: date,
          selectedResourceIds: new Set([resources[1].id, resources[3].id]),
          completedLearningIds: context.completedLearningIds,
          latestSessionByResource: context.latestSessionByResource,
        }).map((item) => item.resourceId),
      ).toEqual(assignment.map((item) => item.resourceId));
    }
  });
});
