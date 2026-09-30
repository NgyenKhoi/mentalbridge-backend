import { describe, expect, it } from 'vitest';

import {
  mondayOf,
  planBingoResources,
  planDailyResources,
  practiceStreak,
  supportPlanDay,
  supportPlanStage,
} from '../resources/resource-journey.planner.js';
import type { PlannerResource } from '../resources/resource-journey.types.js';

const resources: PlannerResource[] = [
  {
    id: '00000000-0000-4000-8000-000000000201',
    resourceKind: 'LEARNING',
    repeatability: 'ONE_TIME',
    cooldownDays: 0,
    recommendedFrequencyPerWeek: 7,
    streakEligible: false,
    planTags: ['DEPRESSIVE_SYMPTOMS'],
  },
  {
    id: '00000000-0000-4000-8000-000000000202',
    resourceKind: 'LEARNING',
    repeatability: 'ONE_TIME',
    cooldownDays: 0,
    recommendedFrequencyPerWeek: 7,
    streakEligible: false,
    planTags: ['ANXIETY_SYMPTOMS'],
  },
  {
    id: '00000000-0000-4000-8000-000000000203',
    resourceKind: 'PRACTICE',
    repeatability: 'REPEATABLE',
    cooldownDays: 1,
    recommendedFrequencyPerWeek: 7,
    streakEligible: true,
    planTags: ['ANXIETY_SYMPTOMS'],
  },
  {
    id: '00000000-0000-4000-8000-000000000204',
    resourceKind: 'HABIT',
    repeatability: 'REPEATABLE',
    cooldownDays: 1,
    recommendedFrequencyPerWeek: 7,
    streakEligible: true,
    planTags: ['DEPRESSIVE_SYMPTOMS'],
  },
  {
    id: '00000000-0000-4000-8000-000000000205',
    resourceKind: 'REFLECTION',
    repeatability: 'REPEATABLE',
    cooldownDays: 0,
    recommendedFrequencyPerWeek: 7,
    streakEligible: false,
    planTags: ['DEPRESSIVE_SYMPTOMS'],
  },
];

describe('resource journey planner', () => {
  it('keeps a balanced plan-selected daily mix and never repeats completed learning', () => {
    const result = planDailyResources(resources, {
      localDate: '2026-09-30',
      planDay: 1,
      planStage: 'ORIENTATION',
      selectedResourceIds: new Set([resources[1].id]),
      completedLearningIds: new Set([resources[0].id]),
      latestSessionByResource: new Map(),
      scheduledThisWeekByResource: new Map(),
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
        planDay: day + 1,
        planStage: supportPlanStage(day + 1),
        selectedResourceIds: new Set([resources[1].id, resources[3].id]),
        completedLearningIds,
        latestSessionByResource,
        scheduledThisWeekByResource: new Map(),
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
          planDay:
            (Date.parse(`${date}T00:00:00Z`) - Date.parse('2026-09-30T00:00:00Z')) / 86_400_000 + 1,
          planStage: supportPlanStage(
            (Date.parse(`${date}T00:00:00Z`) - Date.parse('2026-09-30T00:00:00Z')) / 86_400_000 + 1,
          ),
          selectedResourceIds: new Set([resources[1].id, resources[3].id]),
          completedLearningIds: context.completedLearningIds,
          latestSessionByResource: context.latestSessionByResource,
          scheduledThisWeekByResource: new Map(),
        }).map((item) => item.resourceId),
      ).toEqual(assignment.map((item) => item.resourceId));
    }
  });

  it('enforces the plan window and derives stages from the activation instant', () => {
    expect(supportPlanDay('2026-09-30T20:00:00Z', 'Asia/Ho_Chi_Minh', '2026-10-01')).toBe(1);
    expect(supportPlanDay('2026-09-30T20:00:00Z', 'Asia/Ho_Chi_Minh', '2026-09-30')).toBeNull();
    expect(supportPlanDay('2026-09-30T20:00:00Z', 'Asia/Ho_Chi_Minh', '2026-10-15')).toBeNull();
    expect(supportPlanStage(1)).toBe('ORIENTATION');
    expect(supportPlanStage(4)).toBe('CORE_PRACTICE');
    expect(supportPlanStage(8)).toBe('REINFORCEMENT');
    expect(supportPlanStage(11)).toBe('MAINTENANCE');
    expect(supportPlanStage(14)).toBe('REVIEW');
  });

  it('never bypasses cooldown or weekly frequency when the pool is exhausted', () => {
    const result = planDailyResources(resources, {
      localDate: '2026-09-30',
      planDay: 5,
      planStage: 'CORE_PRACTICE',
      selectedResourceIds: new Set(),
      completedLearningIds: new Set(
        resources.filter((item) => item.resourceKind === 'LEARNING').map((item) => item.id),
      ),
      latestSessionByResource: new Map([[resources[2].id, '2026-09-30']]),
      scheduledThisWeekByResource: new Map([
        [resources[3].id, resources[3].recommendedFrequencyPerWeek],
        [resources[4].id, resources[4].recommendedFrequencyPerWeek],
      ]),
    });

    expect(result).toEqual([]);
  });
});
