import type {
  AssignmentReason,
  PlannedResource,
  PlannerResource,
} from './resource-journey.types.js';

const DAY_MS = 86_400_000;

function dayNumber(localDate: string): number {
  return Math.floor(Date.parse(`${localDate}T00:00:00Z`) / DAY_MS);
}

function daysBetween(earlier: string, later: string): number {
  return Math.floor(
    (Date.parse(`${later}T00:00:00Z`) - Date.parse(`${earlier}T00:00:00Z`)) / DAY_MS,
  );
}

function rotate<T>(values: readonly T[], offset: number): T[] {
  if (values.length === 0) return [];
  const normalized = ((offset % values.length) + values.length) % values.length;
  return [...values.slice(normalized), ...values.slice(0, normalized)];
}

export interface PlannerContext {
  readonly localDate: string;
  readonly selectedResourceIds: ReadonlySet<string>;
  readonly completedLearningIds: ReadonlySet<string>;
  readonly latestSessionByResource: ReadonlyMap<string, string>;
}

export function planDailyResources(
  resources: readonly PlannerResource[],
  context: PlannerContext,
): readonly PlannedResource[] {
  const eligible = resources.filter((resource) => {
    if (resource.resourceKind === 'LEARNING' && context.completedLearningIds.has(resource.id)) {
      return false;
    }
    const latest = context.latestSessionByResource.get(resource.id);
    return !latest || daysBetween(latest, context.localDate) >= resource.cooldownDays;
  });
  const pool =
    eligible.length > 0
      ? eligible
      : resources.filter((resource) => {
          return (
            resource.resourceKind !== 'LEARNING' || !context.completedLearningIds.has(resource.id)
          );
        });
  const offset = dayNumber(context.localDate);
  const selected: PlannedResource[] = [];
  const used = new Set<string>();

  const take = (kinds: readonly PlannerResource['resourceKind'][], reason: AssignmentReason) => {
    const matching = pool
      .filter((resource) => kinds.includes(resource.resourceKind) && !used.has(resource.id))
      .sort((a, b) => a.id.localeCompare(b.id));
    const preferred = rotate(
      matching.filter((resource) => context.selectedResourceIds.has(resource.id)),
      offset + selected.length,
    );
    const remaining = rotate(
      matching.filter((resource) => !context.selectedResourceIds.has(resource.id)),
      offset + selected.length,
    );
    const resource = preferred.at(0) ?? remaining.at(0);
    if (!resource) return;
    used.add(resource.id);
    selected.push({
      resourceId: resource.id,
      reason: context.selectedResourceIds.has(resource.id) ? 'PLAN_SELECTED' : reason,
    });
  };

  take(['LEARNING'], 'PLAN_DOMAIN');
  take(['PRACTICE'], 'CONTINUITY');
  take(['HABIT', 'ACTION'], 'BALANCE');
  take(['REFLECTION', 'PRACTICE', 'HABIT', 'ACTION', 'LEARNING'], 'BALANCE');

  return selected.slice(0, 4);
}

export function planBingoResources(
  resources: readonly PlannerResource[],
  selectedResourceIds: ReadonlySet<string>,
  weekStart: string,
): readonly string[] {
  const byId = (a: PlannerResource, b: PlannerResource) => a.id.localeCompare(b.id);
  const repeatable = resources.filter((resource) => resource.repeatability === 'REPEATABLE');
  const preferred = rotate(
    repeatable.filter((resource) => selectedResourceIds.has(resource.id)).sort(byId),
    dayNumber(weekStart),
  );
  const otherPractice = rotate(
    repeatable.filter((resource) => !selectedResourceIds.has(resource.id)).sort(byId),
    dayNumber(weekStart),
  );
  const fallbackLearning = rotate(
    resources.filter((resource) => resource.repeatability === 'ONE_TIME').sort(byId),
    dayNumber(weekStart),
  );
  return [...preferred, ...otherPractice, ...fallbackLearning]
    .slice(0, 9)
    .map((resource) => resource.id);
}

export function mondayOf(localDate: string): string {
  const date = new Date(`${localDate}T00:00:00Z`);
  const delta = (date.getUTCDay() + 6) % 7;
  date.setUTCDate(date.getUTCDate() - delta);
  return date.toISOString().slice(0, 10);
}

export function practiceStreak(sessionDates: readonly string[], localDate: string): number {
  const dates = new Set(sessionDates);
  let cursor = new Date(`${localDate}T00:00:00Z`);
  if (!dates.has(localDate)) cursor = new Date(cursor.getTime() - DAY_MS);
  let streak = 0;
  while (dates.has(cursor.toISOString().slice(0, 10))) {
    streak += 1;
    cursor = new Date(cursor.getTime() - DAY_MS);
  }
  return streak;
}
