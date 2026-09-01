/**
 * MB-197 / MB-199: Resource service with typed client boundary and safe fallback.
 *
 * - Maps only published resources returned from the database.
 * - Malformed rows are dropped rather than surfaced as invented guidance.
 * - Returns explicit neutral fallback when DB is unavailable or result is empty.
 * - No hotline number, no emergency dispatch claim, no guaranteed-response copy.
 */

import { Inject, Injectable, Logger } from '@nestjs/common';
import type { ResourceRepository, ListResourcesQuery } from './resource.repository.js';
import { RESOURCE_REPOSITORY_TOKEN } from '../application.tokens.js';
import type {
  ResourceCategory,
  ResourceListResult,
  ResourceRow,
  ResourceSummary,
} from './resource.types.js';

/** MB-199: Neutral copy — makes no emergency dispatch, monitoring, or response-time guarantee. */
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

/** MB-199: Drop malformed rows rather than inventing content. */
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

@Injectable()
export class ResourceService {
  private readonly logger = new Logger(ResourceService.name);

  constructor(
    @Inject(RESOURCE_REPOSITORY_TOKEN)
    private readonly repository: ResourceRepository,
  ) {}

  async listPublished(options: ListResourcesOptions): Promise<ResourceListResult> {
    const limit = Math.min(Math.max(options.limit ?? 20, 1), 100);
    const query: ListResourcesQuery = {
      locale: options.locale,
      category: options.category,
      limit,
      cursor: options.cursor,
    };

    let rows: ResourceRow[];
    try {
      rows = await this.repository.listPublished(query);
    } catch (error) {
      // MB-199: Unavailable state — explicit neutral fallback, no invented guidance.
      this.logger.warn({ event: 'resource_db_unavailable', error });
      return {
        data: [],
        count: 0,
        fallback: 'unavailable',
        message: UNAVAILABLE_MESSAGE,
      };
    }

    // Drop malformed rows — never invent support content.
    const data = rows.map(toSummary).filter((r): r is ResourceSummary => r !== null);

    // MB-199: Empty state — return empty array, not invented content.
    return { data, count: data.length };
  }
}
