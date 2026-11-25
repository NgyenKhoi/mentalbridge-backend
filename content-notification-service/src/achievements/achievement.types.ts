export type AchievementVisibility = 'VISIBLE' | 'HIDDEN';
export type AchievementDomain = 'JOURNAL' | 'EMOTION_CHECK_IN' | 'RESOURCE' | 'JOURNEY';
export type AchievementStatus =
  'EARNED' | 'UNEARNED' | 'SOURCE_UNAVAILABLE' | 'GRANDFATHERED' | 'DEFERRED';
export type AchievementKind = 'MILESTONE' | 'CONSISTENCY' | 'JOURNEY';
export type AchievementTier = 'FIRST_STEP' | 'STEADY_STEP' | 'DEEPENING_STEP' | 'JOURNEY';
export type AchievementEvidenceClass =
  | 'OWNER_SOURCE_FACT'
  | 'OWNER_STREAK_MILESTONE'
  | 'OWNER_PRACTICE_WINDOW'
  | 'USER_CONFIRMED_JOURNEY';

export interface AchievementActivityFact {
  readonly distinctDays: number;
  readonly longestStreak: number;
  readonly firstOccurredAt: string | null;
  readonly streakMilestones: readonly {
    readonly days: number;
    readonly reachedAt: string | null;
  }[];
}

export interface AchievementResourceFact {
  readonly kind: 'RESOURCE_COMPLETION' | 'VERIFIED_PRACTICE';
  readonly resourceId: string;
  readonly contentVersion: string;
  readonly localDate: string;
  readonly occurredAt: string;
}

export interface AchievementPreference {
  readonly visibilityState: AchievementVisibility;
  readonly version: string;
}

export interface AchievementItem {
  readonly definitionId: string;
  readonly domain: AchievementDomain;
  readonly kind: AchievementKind;
  readonly tier: AchievementTier;
  readonly title: string;
  readonly description: string;
  readonly status: AchievementStatus;
  readonly evidenceClass: AchievementEvidenceClass;
  readonly unlockedAt: string | null;
  readonly ruleVersion: 'achievement-rules-v1';
  readonly privacy: 'PRIVATE';
  readonly progress: {
    readonly current: number;
    readonly target: number;
    readonly unit: 'EVENT' | 'CONSECUTIVE_DAY' | 'DISTINCT_RESOURCE' | 'PRACTICE_DAY';
  } | null;
}

export interface AchievementCollection {
  readonly visibilityState: AchievementVisibility;
  readonly preferenceVersion: string;
  readonly policyVersion: 'achievement-rules-v1';
  readonly calculatedAt: string;
  readonly sources: readonly {
    readonly domain: AchievementDomain;
    readonly availability: 'AVAILABLE' | 'UNAVAILABLE' | 'DEFERRED';
    readonly asOfLocalDate: string | null;
  }[];
  readonly items: readonly AchievementItem[];
}
