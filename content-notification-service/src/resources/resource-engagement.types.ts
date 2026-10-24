export interface ResourceBookmark {
  readonly resourceId: string;
  readonly contentVersion: string;
  readonly savedAt: string;
  readonly version: string;
}

export interface ResourceBookmarkList {
  readonly items: readonly ResourceBookmark[];
}

export type ResourceBingoEvidence = 'NONE' | 'SELF_CONFIRMED' | 'CONTENT_VERIFIED';
export type ResourceBingoVerificationSource = 'RESOURCE_COMPLETION' | 'PRACTICE_SESSION';

export interface ResourceBingoItemState {
  readonly actionId: string | null;
  readonly retired: boolean;
  readonly position: number;
  readonly resourceId: string;
  readonly contentVersion: string;
  readonly label: string;
  readonly evidence: ResourceBingoEvidence;
  readonly selfConfirmedAt: string | null;
  readonly verifiedAt: string | null;
  readonly verificationSource: ResourceBingoVerificationSource | null;
}

export interface ResourceBingoBoardState {
  readonly weekStart: string;
  readonly timeZone: string;
  readonly version: string;
  readonly updatedAt: string;
  readonly items: readonly ResourceBingoItemState[];
}

export interface PlanResourceAction {
  readonly actionId: string;
  readonly actionVersion: string;
  readonly resource: {
    readonly resourceId: string;
    readonly resourceVersion: string;
    readonly publicationId: string;
  };
}

export interface PlanResourceActions {
  readonly planState: 'ACTIVE' | 'PAUSED' | 'NONE';
  readonly supportPlanId?: string;
  readonly supportPlanVersion?: string;
  readonly timeZone?: string;
  readonly actions: readonly PlanResourceAction[];
}

export interface ResourceBingoPlanSync extends ResourceBingoBoardState {
  readonly unavailableActions: readonly {
    readonly actionId: string;
    readonly reason: 'RESOURCE_VERSION_UNAVAILABLE' | 'BOARD_FULL';
  }[];
}

export interface ResourceCompletionEvidence {
  readonly factKind: 'RESOURCE_PRACTICE_SESSION' | 'RESOURCE_COMPLETION';
  readonly factId: string;
  readonly resourceVersion: string;
  readonly occurredAt: string;
  readonly verifiedAt: string;
}
