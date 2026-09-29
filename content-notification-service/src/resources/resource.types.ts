export type ResourceCategory =
  'BREATHING' | 'MEDITATION' | 'ARTICLE' | 'VIDEO' | 'JOURNALING' | 'COMMUNITY';

export type ResourceStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';

export type ResourceKind = 'LEARNING' | 'PRACTICE' | 'HABIT' | 'ACTION' | 'REFLECTION';
export type ResourceInteractionType =
  | 'STRUCTURED_READER'
  | 'VIDEO_TRANSCRIPT'
  | 'BREATHING_PACER'
  | 'GROUNDING_GUIDE'
  | 'PROGRESSIVE_RELAXATION'
  | 'WALK_TIMER'
  | 'STRETCH_SEQUENCE'
  | 'PROBLEM_SOLVING_WORKSHEET'
  | 'BEHAVIORAL_ACTIVATION_PLANNER'
  | 'SELF_COMPASSION_PROMPTS'
  | 'UNHOOKING_PROMPTS'
  | 'PREPARE_FOR_SPECIALIST_CHECKLIST'
  | 'REFLECTION';
export type ResourceRepeatability = 'ONE_TIME' | 'REPEATABLE';
export type ResourceCompletionMode = 'EXPLICIT' | 'STEPS' | 'TIMED' | 'VIDEO_CONFIRMATION';
export type ResourceSourceReviewStatus = 'REVIEWED' | 'REVIEW_REQUIRED' | 'NEEDS_SOURCE_REVIEW';
export type ResourceJson = Readonly<Record<string, unknown>>;

export interface ResourceRow {
  readonly id: string;
  readonly category: ResourceCategory;
  readonly resource_kind: ResourceKind;
  readonly interaction_type: ResourceInteractionType;
  readonly repeatability: ResourceRepeatability;
  readonly completion_mode: ResourceCompletionMode;
  readonly streak_eligible: boolean;
  readonly expected_duration_minutes: number;
  readonly cooldown_days: number;
  readonly recommended_frequency_per_week: number;
  readonly plan_tags: readonly string[];
  readonly structured_content: ResourceJson;
  readonly interaction_config: ResourceJson;
  readonly safety_notes: readonly string[];
  readonly source_retrieved_at: Date | null;
  readonly source_content_hash: string | null;
  readonly content_version_label: string;
  readonly source_review_status: ResourceSourceReviewStatus;
  readonly locale: string;
  readonly title: string;
  readonly summary: string;
  readonly content_body: string | null;
  readonly external_url: string | null;
  readonly source_organization: string | null;
  readonly source_title: string | null;
  readonly source_url: string | null;
  readonly source_review_note: string | null;
  readonly catalogue_visibility: 'LISTED' | 'DIRECT_ONLY';
  readonly status: ResourceStatus;
  readonly reviewed_by: string | null;
  readonly reviewed_at: Date | null;
  readonly effective_at: Date | null;
  readonly expires_at: Date | null;
  readonly created_at: Date;
  readonly updated_at: Date;
  readonly version: number;
}

export interface ResourceSummary {
  readonly id: string;
  readonly category: ResourceCategory;
  readonly resourceKind: ResourceKind;
  readonly interactionType: ResourceInteractionType;
  readonly repeatability: ResourceRepeatability;
  readonly completionMode: ResourceCompletionMode;
  readonly streakEligible: boolean;
  readonly expectedDurationMinutes: number;
  readonly cooldownDays: number;
  readonly recommendedFrequencyPerWeek: number;
  readonly planTags: readonly string[];
  readonly locale: string;
  readonly title: string;
  readonly summary: string;
  readonly externalUrl: string | null;
  readonly sourceOrganization: string | null;
  readonly status: ResourceStatus;
  readonly reviewedAt: string | null;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface PublicResourceDetail extends ResourceSummary {
  readonly contentVersion: string;
  readonly contentBody: string | null;
  readonly sourceTitle: string | null;
  readonly sourceUrl: string | null;
  readonly sourceReviewNote: string | null;
  readonly effectiveAt: string | null;
  readonly expiresAt: string | null;
  readonly structuredContent: ResourceJson;
  readonly interactionConfig: ResourceJson;
  readonly safetyNotes: readonly string[];
  readonly sourceRetrievedAt: string | null;
  readonly sourceContentHash: string | null;
  readonly contentVersionLabel: string;
  readonly sourceReviewStatus: ResourceSourceReviewStatus;
}

export interface ResourceDetail extends PublicResourceDetail {
  readonly reviewedBy: string | null;
  readonly version: number;
}

export interface ResourceListResponse {
  readonly data: ResourceSummary[];
  readonly count: number;
  readonly nextCursor?: string;
}

export interface ResourceUnavailableResponse {
  readonly data: [];
  readonly count: 0;
  readonly fallback: 'unavailable';
  readonly message: string;
}

export type ResourceListResult = ResourceListResponse | ResourceUnavailableResponse;
