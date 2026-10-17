export type ResourceProgressStatus = 'IN_PROGRESS' | 'COMPLETED';

export interface ResourceProgressRow {
  readonly owner_id: string;
  readonly resource_id: string;
  readonly local_date: string | Date;
  readonly resource_version: string | number;
  readonly status: ResourceProgressStatus;
  readonly completed_action_ids: string[];
  readonly completed_at: Date | null;
  readonly created_at: Date;
  readonly updated_at: Date;
  readonly version: string | number;
}

export interface ResourceProgressItem {
  readonly resourceId: string;
  readonly localDate: string;
  readonly contentVersion: string;
  readonly status: ResourceProgressStatus;
  readonly completedActionIds: readonly string[];
  readonly completedAt: string | null;
  readonly updatedAt: string;
  readonly version: string;
}

export interface ResourceProgressUpdate {
  readonly status: ResourceProgressStatus;
  readonly completedActionIds: readonly string[];
  readonly practiceSessionId?: string;
  readonly practiceStartedAt?: string;
  readonly practiceDurationSeconds?: number;
}

export interface ResourceProgressList {
  readonly items: readonly ResourceProgressItem[];
}
