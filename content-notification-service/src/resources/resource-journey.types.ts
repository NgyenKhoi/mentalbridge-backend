import type { ResourceKind, ResourceSummary } from './resource.types.js';

export type SupportPlanDomain = 'DEPRESSIVE_SYMPTOMS' | 'ANXIETY_SYMPTOMS';
export type AssignmentReason = 'PLAN_SELECTED' | 'PLAN_DOMAIN' | 'CONTINUITY' | 'BALANCE';

export interface ActiveSupportPlanSnapshot {
  readonly supportPlanId: string;
  readonly version: number;
  readonly status: 'ACTIVE';
  readonly activatedAt: string;
  readonly domains: readonly SupportPlanDomain[];
  readonly selectedResourceIds: readonly string[];
}

export interface ResourceJourneyInput {
  readonly timeZone: string;
  readonly supportPlan: ActiveSupportPlanSnapshot;
}

export interface PlannerResource {
  readonly id: string;
  readonly resourceKind: ResourceKind;
  readonly repeatability: 'ONE_TIME' | 'REPEATABLE';
  readonly cooldownDays: number;
  readonly streakEligible: boolean;
  readonly planTags: readonly string[];
}

export interface PlannedResource {
  readonly resourceId: string;
  readonly reason: AssignmentReason;
}

export interface ResourceJourneyAssignmentItem {
  readonly position: number;
  readonly resource: ResourceSummary;
  readonly reason: AssignmentReason;
}

export interface ResourceJourneyProgress {
  readonly dailyCompleted: number;
  readonly dailyTotal: number;
  readonly learningCompleted: number;
  readonly learningTotal: number;
  readonly practiceStreakDays: number;
}

export interface ResourceJourneyBingoItem {
  readonly position: number;
  readonly resourceId: string;
  readonly label: string;
  readonly stamped: boolean;
}

export interface ResourceJourney {
  readonly assignmentId: string;
  readonly localDate: string;
  readonly planId: string;
  readonly planVersion: number;
  readonly items: readonly ResourceJourneyAssignmentItem[];
  readonly progress: ResourceJourneyProgress;
  readonly weekStart: string;
  readonly bingo: readonly ResourceJourneyBingoItem[];
}
