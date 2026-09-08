export type ResourceCategory =
  'BREATHING' | 'MEDITATION' | 'ARTICLE' | 'VIDEO' | 'JOURNALING' | 'COMMUNITY';

export type ResourceStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';

export interface ResourceRow {
  readonly id: string;
  readonly category: ResourceCategory;
  readonly locale: string;
  readonly title: string;
  readonly summary: string;
  readonly content_body: string | null;
  readonly external_url: string | null;
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
  readonly locale: string;
  readonly title: string;
  readonly summary: string;
  readonly externalUrl: string | null;
  readonly status: ResourceStatus;
  readonly reviewedAt: string | null;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface ResourceDetail extends ResourceSummary {
  readonly contentBody: string | null;
  readonly reviewedBy: string | null;
  readonly effectiveAt: string | null;
  readonly expiresAt: string | null;
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
