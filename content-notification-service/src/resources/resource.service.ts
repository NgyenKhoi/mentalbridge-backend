import { Inject, Injectable, Logger } from '@nestjs/common';
import type { ResourceRepository, ListResourcesQuery } from './resource.repository.js';
import {
  E2E_OUTAGE_STATE_TOKEN,
  RESOURCE_REPOSITORY_TOKEN,
} from '../application.tokens.js';
import type {
  ResourceCategory,
  ResourceListResult,
  ResourceRow,
  ResourceSummary,
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

export interface ListResourcesOptions {
  readonly locale?: string;
  readonly category?: ResourceCategory;
  readonly limit?: number;
  readonly cursor?: string;
}

export interface E2eOutageState {
  enabled: boolean;
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
}
