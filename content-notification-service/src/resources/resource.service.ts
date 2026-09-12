import { Inject, Injectable, Logger, ServiceUnavailableException } from '@nestjs/common';
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

function toSummary(row: ResourceRow): ResourceSummary | null {
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
    locale: row.locale,
    title: row.title,
    summary: row.summary,
    externalUrl: row.external_url ?? null,
    status: row.status,
    reviewedAt: row.reviewed_at ? new Date(row.reviewed_at).toISOString() : null,
    createdAt: new Date(row.created_at).toISOString(),
    updatedAt: new Date(row.updated_at).toISOString(),
  };
}

function toDetail(row: ResourceRow): ResourceDetail | null {
  const summary = toSummary(row);
  if (!summary) return null;

  return {
    ...summary,
    contentBody: row.content_body ?? null,
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
    locale: detail.locale,
    title: detail.title,
    summary: detail.summary,
    externalUrl: detail.externalUrl,
    status: detail.status,
    reviewedAt: detail.reviewedAt,
    createdAt: detail.createdAt,
    updatedAt: detail.updatedAt,
    contentBody: detail.contentBody,
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
      (row) => row.status === 'PUBLISHED' && row.reviewed_at !== null,
    );

    const data = publishedRows.map(toSummary).filter((r): r is ResourceSummary => r !== null);

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
    const data = pageRows.map(toSummary).filter((r): r is ResourceSummary => r !== null);

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

  async getPublishedById(id: string, locale: string): Promise<PublicResourceDetail | null> {
    const row = await this.repository.findPublishedEligibleById(id, locale);
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
    return row ? toDetail(row) : null;
  }

  async auditPublishBlocked(
    id: string,
    version: number,
    context: ResourceCommandContext,
  ): Promise<void> {
    await this.repository.auditPublishBlocked(id, version, context);
  }
}
