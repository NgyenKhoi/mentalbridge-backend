import {
  Inject,
  Injectable,
  Logger,
  Optional,
  ServiceUnavailableException,
  UnprocessableEntityException,
} from '@nestjs/common';
import { randomUUID } from 'node:crypto';
import {
  CONTENT_ADMIN_AUDIT_PUBLISHER_TOKEN,
  type ContentAdminAuditPublisher,
} from '../audit/content-admin-audit.publisher.js';
import type {
  ResourceRepository,
  ListResourcesQuery,
  CreateResourceData,
  UpdateResourceData,
  ResourceCommandContext,
} from './resource.repository.js';
import { E2E_OUTAGE_STATE_TOKEN, RESOURCE_REPOSITORY_TOKEN } from '../application.tokens.js';
import type {
  ResourceCategory,
  ResourceListResult,
  ResourceRow,
  ResourceSummary,
  ResourceDetail,
  PublicResourceDetail,
} from './resource.types.js';
import { VerifiedVideoUrlSchema } from './resource.dto.js';

const UNAVAILABLE_MESSAGE = 'Tài nguyên hỗ trợ tạm thời không khả dụng. Vui lòng thử lại sau.';

const VALID_CATEGORIES = new Set<ResourceCategory>([
  'BREATHING',
  'MEDITATION',
  'ARTICLE',
  'VIDEO',
  'JOURNALING',
  'COMMUNITY',
]);

function isValidCategory(value: unknown): value is ResourceCategory {
  return typeof value === 'string' && VALID_CATEGORIES.has(value as ResourceCategory);
}

export function toResourceSummary(row: ResourceRow): ResourceSummary | null {
  if (
    typeof row.id !== 'string' ||
    !isValidCategory(row.category) ||
    typeof row.title !== 'string' ||
    typeof row.summary !== 'string'
  ) {
    return null;
  }

  return {
    id: row.id,
    category: row.category,
    resourceKind: row.resource_kind,
    interactionType: row.interaction_type,
    repeatability: row.repeatability,
    completionMode: row.completion_mode,
    streakEligible: row.streak_eligible,
    expectedDurationMinutes: row.expected_duration_minutes,
    cooldownDays: row.cooldown_days,
    recommendedFrequencyPerWeek: row.recommended_frequency_per_week,
    planTags: row.plan_tags,
    locale: row.locale,
    title: row.title,
    summary: row.summary,
    externalUrl: row.external_url ?? null,
    sourceOrganization: row.source_organization ?? null,
    status: row.status,
    reviewedAt: row.reviewed_at ? new Date(row.reviewed_at).toISOString() : null,
    createdAt: new Date(row.created_at).toISOString(),
    updatedAt: new Date(row.updated_at).toISOString(),
  };
}

function toDetail(row: ResourceRow): ResourceDetail | null {
  const summary = toResourceSummary(row);
  if (!summary) return null;

  return {
    ...summary,
    contentVersion: String(row.version),
    contentBody: row.content_body ?? null,
    sourceTitle: row.source_title ?? null,
    sourceUrl: row.source_url ?? null,
    sourceReviewNote: row.source_review_note ?? null,
    structuredContent: row.structured_content,
    interactionConfig: row.interaction_config,
    safetyNotes: row.safety_notes,
    sourceRetrievedAt: row.source_retrieved_at
      ? new Date(row.source_retrieved_at).toISOString()
      : null,
    sourceContentHash: row.source_content_hash ?? null,
    contentVersionLabel: row.content_version_label,
    sourceReviewStatus: row.source_review_status,
    reviewedBy: row.reviewed_by ?? null,
    effectiveAt: row.effective_at ? new Date(row.effective_at).toISOString() : null,
    expiresAt: row.expires_at ? new Date(row.expires_at).toISOString() : null,
    version: row.version,
  };
}

function toPublicDetail(row: ResourceRow): PublicResourceDetail | null {
  const detail = toDetail(row);
  if (!detail) return null;
  return {
    id: detail.id,
    category: detail.category,
    resourceKind: detail.resourceKind,
    interactionType: detail.interactionType,
    repeatability: detail.repeatability,
    completionMode: detail.completionMode,
    streakEligible: detail.streakEligible,
    expectedDurationMinutes: detail.expectedDurationMinutes,
    cooldownDays: detail.cooldownDays,
    recommendedFrequencyPerWeek: detail.recommendedFrequencyPerWeek,
    planTags: detail.planTags,
    locale: detail.locale,
    title: detail.title,
    summary: detail.summary,
    externalUrl: detail.externalUrl,
    sourceOrganization: detail.sourceOrganization,
    status: detail.status,
    reviewedAt: detail.reviewedAt,
    createdAt: detail.createdAt,
    updatedAt: detail.updatedAt,
    contentVersion: detail.contentVersion,
    contentBody: detail.contentBody,
    sourceTitle: detail.sourceTitle,
    sourceUrl: detail.sourceUrl,
    sourceReviewNote: detail.sourceReviewNote,
    structuredContent: detail.structuredContent,
    interactionConfig: detail.interactionConfig,
    safetyNotes: detail.safetyNotes,
    sourceRetrievedAt: detail.sourceRetrievedAt,
    sourceContentHash: detail.sourceContentHash,
    contentVersionLabel: detail.contentVersionLabel,
    sourceReviewStatus: detail.sourceReviewStatus,
    effectiveAt: detail.effectiveAt,
    expiresAt: detail.expiresAt,
  };
}

export interface ListResourcesOptions {
  readonly locale?: string;
  readonly category?: ResourceCategory;
  readonly limit?: number;
  readonly cursor?: string;
}

export interface E2eOutageState {
  enabled: boolean;
}

export interface ListAdminResourcesOptions extends ListResourcesOptions {
  readonly status?: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
}

@Injectable()
export class ResourceService {
  private readonly logger = new Logger(ResourceService.name);

  constructor(
    @Inject(RESOURCE_REPOSITORY_TOKEN)
    private readonly repository: ResourceRepository,
    @Inject(E2E_OUTAGE_STATE_TOKEN)
    private readonly outageState: E2eOutageState,
    @Inject(CONTENT_ADMIN_AUDIT_PUBLISHER_TOKEN)
    private readonly auditPublisher: ContentAdminAuditPublisher,
  ) {}

  async listPublished(options: ListResourcesOptions): Promise<ResourceListResult> {
    const limit = Math.min(Math.max(options.limit ?? 20, 1), 100);
    const query: ListResourcesQuery = {
      locale: options.locale,
      category: options.category,
      limit,
      cursor: options.cursor,
    };

    if (this.outageState.enabled) {
      return {
        data: [],
        count: 0,
        fallback: 'unavailable',
        message: UNAVAILABLE_MESSAGE,
      };
    }

    let rows: ResourceRow[];
    try {
      rows = await this.repository.listPublished(query);
    } catch (error) {
      this.logger.warn({
        event: 'resource_db_unavailable',
        code: (error as NodeJS.ErrnoException).code,
      });
      return {
        data: [],
        count: 0,
        fallback: 'unavailable',
        message: UNAVAILABLE_MESSAGE,
      };
    }

    const hasMore = rows.length > limit;
    const pageRows = hasMore ? rows.slice(0, limit) : rows;

    // Defensive filter: only PUBLISHED resources with valid review status
    const publishedRows = pageRows.filter(
      (row) =>
        row.status === 'PUBLISHED' &&
        row.reviewed_at !== null &&
        row.reviewed_by !== null &&
        row.source_review_status === 'REVIEWED',
    );

    const data = publishedRows
      .map(toResourceSummary)
      .filter((r): r is ResourceSummary => r !== null);

    return {
      data,
      count: data.length,
      ...(hasMore && data.length > 0 ? { nextCursor: data[data.length - 1].id } : {}),
    };
  }

  async listAdmin(options: ListAdminResourcesOptions): Promise<ResourceListResult> {
    const limit = Math.min(Math.max(options.limit ?? 20, 1), 100);

    let rows: ResourceRow[];
    try {
      rows = await this.repository.listAdmin({
        locale: options.locale,
        category: options.category,
        status: options.status,
        limit,
        cursor: options.cursor,
      });
    } catch (error) {
      this.logger.warn({
        event: 'admin_resource_db_unavailable',
        code: (error as NodeJS.ErrnoException).code,
      });
      throw new ServiceUnavailableException({
        type: 'https://mentalbridge.io/errors/DEPENDENCY_UNAVAILABLE',
        title: 'Resource administration is temporarily unavailable',
        status: 503,
        code: 'DEPENDENCY_UNAVAILABLE',
      });
    }

    const hasMore = rows.length > limit;
    const pageRows = hasMore ? rows.slice(0, limit) : rows;
    const data = pageRows.map(toResourceSummary).filter((r): r is ResourceSummary => r !== null);

    return {
      data,
      count: data.length,
      ...(hasMore && data.length > 0 ? { nextCursor: data[data.length - 1].id } : {}),
    };
  }

  async getAdminById(id: string): Promise<ResourceDetail | null> {
    const row = await this.repository.findById(id);
    return row ? toDetail(row) : null;
  }

  async getPublishedById(
    id: string,
    locale: string,
    contentVersion?: string,
  ): Promise<PublicResourceDetail | null> {
    const row = await this.repository.findPublishedEligibleById(id, locale, contentVersion);
    return row ? toPublicDetail(row) : null;
  }

  async create(
    data: CreateResourceData,
    idempotencyKey: string,
    context: ResourceCommandContext,
  ): Promise<ResourceDetail> {
    const row = await this.repository.create(data, idempotencyKey, context);
    const detail = toDetail(row);
    if (!detail) {
      throw new Error('Failed to create resource');
    }
    return detail;
  }

  async update(
    id: string,
    data: UpdateResourceData,
    context: ResourceCommandContext,
  ): Promise<ResourceDetail | null> {
    const current = await this.repository.findById(id);
    if (!current || current.status !== 'DRAFT' || current.version !== data.version) return null;
    const externalUrl = data.externalUrl === undefined ? current.external_url : data.externalUrl;
    if (current.category === 'VIDEO' && !VerifiedVideoUrlSchema.safeParse(externalUrl).success) {
      throw new UnprocessableEntityException({
        type: 'https://mentalbridge.io/errors/VALIDATION_ERROR',
        title: 'Validation failed',
        status: 422,
        code: 'VALIDATION_ERROR',
        fieldViolations: [
          {
            field: 'externalUrl',
            message: 'VIDEO resources require a verified HTTPS YouTube URL',
          },
        ],
      });
    }
    const row = await this.repository.update(id, data, context);
    return row ? toDetail(row) : null;
  }

  async delete(id: string, version: number, context: ResourceCommandContext): Promise<boolean> {
    return this.repository.delete(id, version, context);
  }

  async archive(
    id: string,
    version: number,
    context: ResourceCommandContext,
  ): Promise<ResourceDetail | null> {
    const row = await this.repository.archive(id, version, context);
    if (!row) return null;
    if (this.auditPublisher) {
      await this.auditPublisher.publish({
        eventId: randomUUID(),
        eventType: 'content.resource.archived',
        occurredAt: new Date().toISOString(),
        producer: 'content-notification-service',
        schemaVersion: '1.0',
        sourceService: 'CONTENT',
        domain: 'RESOURCE_MANAGEMENT',
        actorId: context.actorId,
        actorType: 'ADMIN',
        action: 'RESOURCE_ARCHIVED',
        result: 'SUCCEEDED',
        reasonCode: 'RESOURCE_ARCHIVED',
        correlationId: context.correlationId,
        targetAccountId: null,
        targetIdentifier: null,
      });
    }
    return toDetail(row);
  }

  async auditPublishBlocked(
    id: string,
    version: number,
    context: ResourceCommandContext,
  ): Promise<void> {
    await this.repository.auditPublishBlocked(id, version, context);
  }
}
