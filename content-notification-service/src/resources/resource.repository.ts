import { Inject, Injectable } from '@nestjs/common';
import type { DatabaseService } from '../database/database.service.js';
import { DATABASE_SERVICE_TOKEN } from '../application.tokens.js';
import type { ResourceCategory, ResourceRow } from './resource.types.js';

export interface ListResourcesQuery {
  readonly locale?: string;
  readonly category?: ResourceCategory;
  readonly limit: number;
  readonly cursor?: string;
}

@Injectable()
export class ResourceRepository {
  constructor(
    @Inject(DATABASE_SERVICE_TOKEN)
    private readonly db: DatabaseService,
  ) {}

  async listPublished(query: ListResourcesQuery): Promise<ResourceRow[]> {
    const params: (string | number)[] = [query.limit + 1];
    const conditions: string[] = [
      "r.status = 'PUBLISHED'",
      'r.reviewed_at IS NOT NULL',
      'r.reviewed_by IS NOT NULL',
      '(r.effective_at IS NULL OR r.effective_at <= now())',
      '(r.expires_at IS NULL OR r.expires_at > now())',
    ];
    let index = 2;

    if (query.locale) {
      conditions.push(`r.locale = $${String(index++)}`);
      params.push(query.locale);
    }

    if (query.category) {
      conditions.push(`r.category = $${String(index++)}`);
      params.push(query.category);
    }

    if (query.cursor) {
      conditions.push(
        `r.created_at < (SELECT created_at FROM resource WHERE id = $${String(index++)})`,
      );
      params.push(query.cursor);
    }

    const where = conditions.join(' AND ');

    const result = await this.db.query<ResourceRow>(
      `SELECT id, category, locale, title, summary, external_url, status,
              reviewed_at, created_at, updated_at
       FROM resource r
       WHERE ${where}
       ORDER BY r.created_at DESC
       LIMIT $1`,
      params,
    );

    return result.rows;
  }
}
