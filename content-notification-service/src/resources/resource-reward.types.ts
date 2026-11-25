export type ResourceRewardVisibility = 'VISIBLE' | 'HIDDEN';
export type ResourceRewardGrantKind = 'RESOURCE_COMPLETION' | 'VERIFIED_PRACTICE';

export interface ResourceRewardSourceFact {
  readonly kind: ResourceRewardGrantKind;
  readonly resourceId: string;
  readonly contentVersion: string;
  readonly localDate: string;
  readonly occurredAt: string;
}

export interface ResourceRewardPreference {
  readonly visibilityState: ResourceRewardVisibility;
  readonly version: string;
}

export interface ResourceRewardGrant {
  readonly grantId: string;
  readonly kind: ResourceRewardGrantKind;
  readonly xp: number;
  readonly resourceId: string;
  readonly contentVersion: string;
  readonly localDate: string;
  readonly grantedAt: string;
  readonly sourceReference: string;
  readonly ruleVersion: string;
}

export interface ResourceRewardReadModel {
  readonly totalXp: number;
  readonly level: number;
  readonly nextLevelXp: number | null;
  readonly visibilityState: ResourceRewardVisibility;
  readonly preferenceVersion: string;
  readonly policyVersion: string;
  readonly sourceAvailability: 'AVAILABLE';
  readonly calculatedAt: string;
  readonly recentGrants: readonly ResourceRewardGrant[];
}
