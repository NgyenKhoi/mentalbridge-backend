import type {
  AssignmentReason,
  PlannedResource,
  PlannerResource,
  SupportPlanStage,
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
  readonly planDay: number;
  readonly planStage: SupportPlanStage;
  readonly selectedResourceIds: ReadonlySet<string>;
  readonly completedLearningIds: ReadonlySet<string>;
  readonly latestSessionByResource: ReadonlyMap<string, string>;
  readonly scheduledThisWeekByResource: ReadonlyMap<string, number>;
}

export function localDateInTimeZone(instant: string, timeZone: string): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(new Date(instant));
  const value = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((part) => part.type === type)?.value ?? '';
  return `${value('year')}-${value('month')}-${value('day')}`;
}

export function supportPlanDay(
  activatedAt: string,
  timeZone: string,
  requestedDate: string,
): number | null {
  const activationDate = localDateInTimeZone(activatedAt, timeZone);
  const day = daysBetween(activationDate, requestedDate) + 1;
  return day >= 1 && day <= 14 ? day : null;
}

export function supportPlanStage(planDay: number): SupportPlanStage {
  if (planDay <= 3) return 'ORIENTATION';
  if (planDay <= 7) return 'CORE_PRACTICE';
  if (planDay <= 10) return 'REINFORCEMENT';
  if (planDay <= 13) return 'MAINTENANCE';
  return 'REVIEW';
}

const STAGE_SLOTS: Readonly<
  Record<SupportPlanStage, readonly (readonly PlannerResource['resourceKind'][])[]>
> = {
  ORIENTATION: [
    ['LEARNING'],
    ['PRACTICE'],
    ['HABIT', 'ACTION'],
    ['REFLECTION', 'PRACTICE', 'HABIT', 'ACTION', 'LEARNING'],
  ],
  CORE_PRACTICE: [
    ['PRACTICE'],
    ['LEARNING'],
    ['HABIT', 'ACTION'],
    ['REFLECTION', 'PRACTICE', 'HABIT', 'ACTION', 'LEARNING'],
  ],
  REINFORCEMENT: [
    ['PRACTICE'],
    ['HABIT', 'ACTION'],
    ['REFLECTION'],
    ['LEARNING', 'PRACTICE', 'HABIT', 'ACTION'],
  ],
  MAINTENANCE: [
    ['HABIT', 'ACTION'],
    ['PRACTICE'],
    ['REFLECTION'],
    ['LEARNING', 'PRACTICE', 'HABIT', 'ACTION'],
  ],
  REVIEW: [
    ['REFLECTION'],
    ['PRACTICE'],
    ['HABIT', 'ACTION'],
    ['LEARNING', 'PRACTICE', 'HABIT', 'ACTION'],
  ],
};

export function planDailyResources(
  resources: readonly PlannerResource[],
  context: PlannerContext,
): readonly PlannedResource[] {
  const eligible = resources.filter((resource) => {
    if (resource.resourceKind === 'LEARNING' && context.completedLearningIds.has(resource.id)) {
      return false;
    }
    if (
      resource.repeatability === 'REPEATABLE' &&
      (context.scheduledThisWeekByResource.get(resource.id) ?? 0) >=
        resource.recommendedFrequencyPerWeek
    ) {
      return false;
    }
    const latest = context.latestSessionByResource.get(resource.id);
    return !latest || daysBetween(latest, context.localDate) >= resource.cooldownDays;
  });
  const offset = context.planDay - 1;
  const selected: PlannedResource[] = [];
  const used = new Set<string>();

  const take = (kinds: readonly PlannerResource['resourceKind'][], reason: AssignmentReason) => {
    const matching = eligible
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

  for (const kinds of STAGE_SLOTS[context.planStage]) {
    const reason: AssignmentReason = kinds.includes('LEARNING')
      ? 'PLAN_DOMAIN'
      : kinds.includes('PRACTICE')
        ? 'CONTINUITY'
        : 'BALANCE';
    take(kinds, reason);
  }

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
