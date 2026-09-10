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

export interface ListAdminResourcesQuery {
  readonly locale?: string;
  readonly category?: ResourceCategory;
  readonly status?: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
  readonly limit: number;
  readonly cursor?: string;
}

export interface CreateResourceData {
  readonly category: ResourceCategory;
  readonly locale: string;
  readonly title: string;
  readonly summary: string;
  readonly contentBody?: string | null;
  readonly externalUrl?: string | null;
}

export interface UpdateResourceData {
  readonly title?: string;
  readonly summary?: string;
  readonly contentBody?: string | null;
  readonly externalUrl?: string | null;
  readonly version: number;
}

export interface PublishResourceData {
  readonly reviewedBy: string;
  readonly version: number;
  readonly effectiveAt?: Date | null;
  readonly expiresAt?: Date | null;
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
      conditions.push(`r.locale = $${index++}`);
      params.push(query.locale);
    }

    if (query.category) {
      conditions.push(`r.category = $${index++}`);
      params.push(query.category);
    }

    if (query.cursor) {
      conditions.push(
        `(r.created_at, r.id) < (SELECT created_at, id FROM resource WHERE id = $${index++})`,
      );
      params.push(query.cursor);
    }

    const where = conditions.join(' AND ');

    const result = await this.db.query<ResourceRow>(
      `SELECT id, category, locale, title, summary, external_url, status,
              reviewed_by, reviewed_at, created_at, updated_at
       FROM resource r
       WHERE ${where}
       ORDER BY r.created_at DESC, r.id DESC
       LIMIT $1`,
      params,
    );

    return result.rows;
  }

  async listAdmin(query: ListAdminResourcesQuery): Promise<ResourceRow[]> {
    const params: (string | number)[] = [query.limit + 1];
    const conditions: string[] = [];
    let index = 2;

    if (query.status) {
      conditions.push(`r.status = $${index++}`);
      params.push(query.status);
    }

    if (query.locale) {
      conditions.push(`r.locale = $${index++}`);
      params.push(query.locale);
    }

    if (query.category) {
      conditions.push(`r.category = $${index++}`);
      params.push(query.category);
    }

    if (query.cursor) {
      conditions.push(
        `(r.created_at, r.id) < (SELECT created_at, id FROM resource WHERE id = $${index++})`,
      );
      params.push(query.cursor);
    }

    const where = conditions.length > 0 ? `WHERE ${conditions.join(' AND ')}` : '';

    const result = await this.db.query<ResourceRow>(
      `SELECT id, category, locale, title, summary, external_url, status,
              reviewed_by, reviewed_at, created_at, updated_at
       FROM resource r
       ${where}
       ORDER BY r.created_at DESC, r.id DESC
       LIMIT $1`,
      params,
    );

    return result.rows;
  }

  async findById(id: string): Promise<ResourceRow | null> {
    const result = await this.db.query<ResourceRow>(
      `SELECT id, category, locale, title, summary, content_body, external_url,
              status, reviewed_by, reviewed_at, effective_at, expires_at,
              created_at, updated_at, version
       FROM resource
       WHERE id = $1`,
      [id],
    );
    const row = result.rows[0];
    // eslint-disable-next-line @typescript-eslint/no-unnecessary-condition
    if (!row) return null;
    return {
      ...row,
      // eslint-disable-next-line @typescript-eslint/no-unnecessary-type-assertion
      version: Number(row.version as unknown),
    };
  }

  async create(data: CreateResourceData, idempotencyKey?: string): Promise<ResourceRow> {
    if (idempotencyKey) {
      // Check if resource with this idempotency key already exists
      const existing = await this.db.query<ResourceRow>(
        `SELECT id, category, locale, title, summary, content_body, external_url,
                status, reviewed_by, reviewed_at, effective_at, expires_at,
                created_at, updated_at, version
         FROM resource
         WHERE idempotency_key = $1`,
        [idempotencyKey],
      );
      if (existing.rows[0]) {
        return {
          ...existing.rows[0],
          version: Number(existing.rows[0].version as unknown),
        };
      }
    }

    const result = await this.db.query<ResourceRow>(
      `INSERT INTO resource (category, locale, title, summary, content_body, external_url, status, idempotency_key)
       VALUES ($1, $2, $3, $4, $5, $6, 'DRAFT', $7)
       RETURNING id, category, locale, title, summary, content_body, external_url,
                 status, reviewed_by, reviewed_at, effective_at, expires_at,
                 created_at, updated_at, version`,
      [
        data.category,
        data.locale,
        data.title,
        data.summary,
        data.contentBody || null,
        data.externalUrl || null,
        idempotencyKey || null,
      ],
    );
    const row = result.rows[0];
    return {
      ...row,
      // eslint-disable-next-line @typescript-eslint/no-unnecessary-type-assertion
      version: Number(row.version as unknown),
    };
  }

  async update(id: string, data: UpdateResourceData): Promise<ResourceRow | null> {
    const updates: string[] = ['updated_at = now()', 'version = version + 1'];
    const params: (string | number | null)[] = [id, data.version];
    let index = 3;

    if (data.title !== undefined) {
      updates.push(`title = $${index++}`);
      params.push(data.title);
    }
    if (data.summary !== undefined) {
      updates.push(`summary = $${index++}`);
      params.push(data.summary);
    }
    if (data.contentBody !== undefined) {
      updates.push(`content_body = $${index++}`);
      params.push(data.contentBody);
    }
    if (data.externalUrl !== undefined) {
      updates.push(`external_url = $${index++}`);
      params.push(data.externalUrl);
    }

    const result = await this.db.query<ResourceRow>(
      `UPDATE resource
       SET ${updates.join(', ')}
       WHERE id = $1 AND version = $2 AND status = 'DRAFT'
       RETURNING id, category, locale, title, summary, content_body, external_url,
                 status, reviewed_by, reviewed_at, effective_at, expires_at,
                 created_at, updated_at, version`,
      params,
    );
    const row = result.rows[0];
    // eslint-disable-next-line @typescript-eslint/no-unnecessary-condition
    if (!row) return null;
    return {
      ...row,
      // eslint-disable-next-line @typescript-eslint/no-unnecessary-type-assertion
      version: Number(row.version as unknown),
    };
  }

  async delete(id: string, version: number): Promise<boolean> {
    const result = await this.db.query(
      `DELETE FROM resource WHERE id = $1 AND version = $2 AND status = 'DRAFT'`,
      [id, version],
    );
    return (result.rowCount ?? 0) > 0;
  }

  async publish(id: string, data: PublishResourceData): Promise<ResourceRow | null> {
    const result = await this.db.query<ResourceRow>(
      `UPDATE resource
       SET status = 'PUBLISHED',
           reviewed_by = $2,
           reviewed_at = now(),
           effective_at = $3,
           expires_at = $4,
           updated_at = now(),
           version = version + 1
       WHERE id = $1 AND version = $5 AND status = 'DRAFT'
       RETURNING id, category, locale, title, summary, content_body, external_url,
                 status, reviewed_by, reviewed_at, effective_at, expires_at,
                 created_at, updated_at, version`,
      [id, data.reviewedBy, data.effectiveAt || null, data.expiresAt || null, data.version],
    );
    const row = result.rows[0];
    // eslint-disable-next-line @typescript-eslint/no-unnecessary-condition
    if (!row) return null;
    return {
      ...row,
      // eslint-disable-next-line @typescript-eslint/no-unnecessary-type-assertion
      version: Number(row.version as unknown),
    };
  }

  async archive(id: string, version: number): Promise<ResourceRow | null> {
    const result = await this.db.query<ResourceRow>(
      `UPDATE resource
       SET status = 'ARCHIVED',
           updated_at = now(),
           version = version + 1
       WHERE id = $1 AND version = $2 AND status = 'PUBLISHED'
       RETURNING id, category, locale, title, summary, content_body, external_url,
                 status, reviewed_by, reviewed_at, effective_at, expires_at,
                 created_at, updated_at, version`,
      [id, version],
    );
    const row = result.rows[0];
    // eslint-disable-next-line @typescript-eslint/no-unnecessary-condition
    if (!row) return null;
    return {
      ...row,
      // eslint-disable-next-line @typescript-eslint/no-unnecessary-type-assertion
      version: Number(row.version as unknown),
    };
  }
}
