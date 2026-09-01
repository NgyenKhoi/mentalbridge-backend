/**
 * MB-197: Typed resource domain types for the Content/Notification BFF.
 * No hotline catalogue — see ADR 0009.
 */

export type ResourceCategory =
  | 'BREATHING'
  | 'MEDITATION'
  | 'ARTICLE'
  | 'VIDEO'
  | 'JOURNALING'
  | 'COMMUNITY';

export type ResourceStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';

export interface ResourceRow {
  readonly id: string;
  readonly category: ResourceCategory;
  readonly locale: string;
  readonly title: string;
  readonly summary: string;
  readonly external_url: string | null;
  readonly status: ResourceStatus;
  readonly reviewed_at: Date | null;
  readonly created_at: Date;
  readonly updated_at: Date;
}

export interface ResourceSummary {
  readonly id: string;
  readonly category: ResourceCategory;
  readonly locale: string;
  readonly title: string;
  readonly summary: string;
  readonly externalUrl: string | null;
  readonly status: ResourceStatus;
  readonly reviewedAt: string | null;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface ResourceListResponse {
  readonly data: ResourceSummary[];
  readonly count: number;
}

/** MB-199: Explicit neutral fallback payload — no emergency dispatch claim, no hotline number. */
export interface ResourceUnavailableResponse {
  readonly data: [];
  readonly count: 0;
  readonly fallback: 'unavailable';
  readonly message: string;
}

export type ResourceListResult = ResourceListResponse | ResourceUnavailableResponse;
