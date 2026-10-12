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
