import { createHash, randomUUID } from 'node:crypto';
import { Inject, Injectable } from '@nestjs/common';
import type { DatabaseClient, DatabaseService } from '../database/database.service.js';
import { DATABASE_SERVICE_TOKEN } from '../application.tokens.js';
import type { ResourceCategory, ResourceRow } from './resource.types.js';

export interface ListResourcesQuery {
  readonly locale?: string;
  readonly category?: ResourceCategory;
  readonly limit: number;
  readonly cursor?: string;
}

export interface ListAdminResourcesQuery extends ListResourcesQuery {
  readonly status?: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
}

export interface ResourceCommandContext {
  readonly actorId: string;
  readonly correlationId: string;
}

export interface CreateResourceData {
  readonly category: ResourceCategory;
  readonly locale: string;
  readonly title: string;
  readonly summary: string;
  readonly contentBody?: string | null;
  readonly externalUrl?: string | null;
  readonly effectiveAt?: Date | null;
  readonly expiresAt?: Date | null;
}

export interface UpdateResourceData {
  readonly locale?: string;
  readonly title?: string;
  readonly summary?: string;
  readonly contentBody?: string | null;
  readonly externalUrl?: string | null;
  readonly effectiveAt?: Date | null;
  readonly expiresAt?: Date | null;
  readonly version: number;
}

type IdempotencyRow = {
  readonly request_fingerprint: string;
  readonly resource_id: string | null;
};

type ResourceDatabaseRow = Omit<ResourceRow, 'version'> & { readonly version: string | number };

const RESOURCE_COLUMNS = `id, category, locale, title, summary, content_body, external_url,
  status, reviewed_by, reviewed_at, effective_at, expires_at,
  created_at, updated_at, version`;

export class ResourceIdempotencyConflictError extends Error {
  constructor() {
    super('Idempotency key was already used with a different request');
    this.name = 'ResourceIdempotencyConflictError';
  }
}

function toResourceRow(row: ResourceDatabaseRow): ResourceRow {
  return { ...row, version: Number(row.version) };
}

function fingerprint(data: CreateResourceData): string {
  return createHash('sha256')
    .update(
      JSON.stringify({
        category: data.category,
        locale: data.locale,
        title: data.title,
        summary: data.summary,
        contentBody: data.contentBody ?? null,
        externalUrl: data.externalUrl ?? null,
        effectiveAt: data.effectiveAt?.toISOString() ?? null,
        expiresAt: data.expiresAt?.toISOString() ?? null,
      }),
    )
    .digest('hex');
}

@Injectable()
export class ResourceRepository {
  constructor(
    @Inject(DATABASE_SERVICE_TOKEN)
    private readonly db: DatabaseService,
  ) {}

  async listPublished(query: ListResourcesQuery): Promise<ResourceRow[]> {
    const params: (string | number)[] = [query.limit + 1];
    const conditions = [
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
        `(r.created_at, r.id) < (SELECT created_at, id FROM resource WHERE id = $${String(index++)})`,
      );
      params.push(query.cursor);
    }

    const result = await this.db.query<ResourceDatabaseRow>(
      `SELECT ${RESOURCE_COLUMNS}
       FROM resource r
       WHERE ${conditions.join(' AND ')}
       ORDER BY r.created_at DESC, r.id DESC
       LIMIT $1`,
      params,
    );
    return result.rows.map(toResourceRow);
  }

  async listAdmin(query: ListAdminResourcesQuery): Promise<ResourceRow[]> {
    const params: (string | number)[] = [query.limit + 1];
    const conditions: string[] = [];
    let index = 2;

    if (query.status) {
      conditions.push(`r.status = $${String(index++)}`);
      params.push(query.status);
    }
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
        `(r.created_at, r.id) < (SELECT created_at, id FROM resource WHERE id = $${String(index++)})`,
      );
      params.push(query.cursor);
    }

    const where = conditions.length > 0 ? `WHERE ${conditions.join(' AND ')}` : '';
    const result = await this.db.query<ResourceDatabaseRow>(
      `SELECT ${RESOURCE_COLUMNS}
       FROM resource r
       ${where}
       ORDER BY r.created_at DESC, r.id DESC
       LIMIT $1`,
      params,
    );
    return result.rows.map(toResourceRow);
  }

  async findById(id: string): Promise<ResourceRow | null> {
    const result = await this.db.query<ResourceDatabaseRow>(
      `SELECT ${RESOURCE_COLUMNS} FROM resource WHERE id = $1`,
      [id],
    );
    return result.rows[0] ? toResourceRow(result.rows[0]) : null;
  }

  async findPublishedEligibleById(id: string, locale: string): Promise<ResourceRow | null> {
    const result = await this.db.query<ResourceDatabaseRow>(
      `SELECT ${RESOURCE_COLUMNS}
       FROM resource
       WHERE id = $1
         AND locale = $2
         AND status = 'PUBLISHED'
         AND reviewed_by IS NOT NULL
         AND reviewed_at IS NOT NULL
         AND (effective_at IS NULL OR effective_at <= now())
         AND (expires_at IS NULL OR expires_at > now())`,
      [id, locale],
    );
    return result.rows[0] ? toResourceRow(result.rows[0]) : null;
  }

  async create(
    data: CreateResourceData,
    idempotencyKey: string,
    context: ResourceCommandContext,
  ): Promise<ResourceRow> {
    const requestFingerprint = fingerprint(data);
    const lockKey = `${context.actorId}:CREATE_RESOURCE:${idempotencyKey}`;

    return this.db.withTransaction(async (client) => {
      await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [lockKey]);
      const existing = await client.query<IdempotencyRow>(
        `SELECT request_fingerprint, resource_id
         FROM resource_idempotency_record
         WHERE actor_id = $1 AND operation = 'CREATE_RESOURCE' AND idempotency_key = $2`,
        [context.actorId, idempotencyKey],
      );
      if ((existing.rowCount ?? 0) > 0) {
        const replay = existing.rows[0];
        if (replay.request_fingerprint !== requestFingerprint) {
          throw new ResourceIdempotencyConflictError();
        }
        if (!replay.resource_id) throw new ResourceIdempotencyConflictError();
        const resource = await this.selectById(client, replay.resource_id);
        if (!resource) throw new ResourceIdempotencyConflictError();
        return resource;
      }

      const resourceId = randomUUID();
      const inserted = await client.query<ResourceDatabaseRow>(
        `INSERT INTO resource
          (id, category, locale, title, summary, content_body, external_url, status,
           effective_at, expires_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, 'DRAFT', $8, $9)
         RETURNING ${RESOURCE_COLUMNS}`,
        [
          resourceId,
          data.category,
          data.locale,
          data.title,
          data.summary,
          data.contentBody ?? null,
          data.externalUrl ?? null,
          data.effectiveAt ?? null,
          data.expiresAt ?? null,
        ],
      );
      await client.query(
        `INSERT INTO resource_idempotency_record
          (actor_id, operation, idempotency_key, request_fingerprint, resource_id)
         VALUES ($1, 'CREATE_RESOURCE', $2, $3, $4)`,
        [context.actorId, idempotencyKey, requestFingerprint, resourceId],
      );
      await this.audit(client, 'RESOURCE_CREATED', resourceId, 0, context);
      return toResourceRow(inserted.rows[0]);
    });
  }

  async update(
    id: string,
    data: UpdateResourceData,
    context: ResourceCommandContext,
  ): Promise<ResourceRow | null> {
    const updates = [
      'updated_at = now()',
      'version = version + 1',
      'reviewed_by = NULL',
      'reviewed_at = NULL',
    ];
    const params: (string | number | Date | null)[] = [id, data.version];
    let index = 3;
    let contentExpression = 'content_body';
    let urlExpression = 'external_url';
    let effectiveExpression = 'effective_at';
    let expiresExpression = 'expires_at';

    for (const [column, value] of [
      ['locale', data.locale],
      ['title', data.title],
      ['summary', data.summary],
      ['content_body', data.contentBody],
      ['external_url', data.externalUrl],
      ['effective_at', data.effectiveAt],
      ['expires_at', data.expiresAt],
    ] as const) {
      if (value !== undefined) {
        const placeholder = `$${String(index++)}`;
        updates.push(`${column} = ${placeholder}`);
        params.push(value ?? null);
        if (column === 'content_body') contentExpression = placeholder;
        if (column === 'external_url') urlExpression = placeholder;
        if (column === 'effective_at') effectiveExpression = placeholder;
        if (column === 'expires_at') expiresExpression = placeholder;
      }
    }

    params.push(context.actorId, context.correlationId);
    const actorIndex = index++;
    const correlationIndex = index;
    const result = await this.db.query<ResourceDatabaseRow>(
      `WITH updated AS (
         UPDATE resource
         SET ${updates.join(', ')}
         WHERE id = $1 AND version = $2 AND status = 'DRAFT'
           AND (${contentExpression} IS NOT NULL OR ${urlExpression} IS NOT NULL)
           AND (${expiresExpression} IS NULL OR ${effectiveExpression} IS NULL
                OR ${expiresExpression} > ${effectiveExpression})
         RETURNING ${RESOURCE_COLUMNS}
       ), audited AS (
         INSERT INTO resource_audit_event
           (actor_id, action, resource_id, resource_version, correlation_id)
         SELECT $${String(actorIndex)}, 'RESOURCE_UPDATED', id, version, $${String(correlationIndex)}
         FROM updated
       )
       SELECT * FROM updated`,
      params,
    );
    return result.rows[0] ? toResourceRow(result.rows[0]) : null;
  }

  async delete(id: string, version: number, context: ResourceCommandContext): Promise<boolean> {
    const result = await this.db.query(
      `WITH deleted AS (
         DELETE FROM resource
         WHERE id = $1 AND version = $2 AND status = 'DRAFT'
         RETURNING id, version
       )
       INSERT INTO resource_audit_event
         (actor_id, action, resource_id, resource_version, correlation_id)
       SELECT $3, 'RESOURCE_DELETED', id, version, $4 FROM deleted
       RETURNING id`,
      [id, version, context.actorId, context.correlationId],
    );
    return (result.rowCount ?? 0) > 0;
  }

  async archive(
    id: string,
    version: number,
    context: ResourceCommandContext,
  ): Promise<ResourceRow | null> {
    const result = await this.db.query<ResourceDatabaseRow>(
      `WITH updated AS (
         UPDATE resource
         SET status = 'ARCHIVED', updated_at = now(), version = version + 1
         WHERE id = $1 AND version = $2 AND status = 'PUBLISHED'
         RETURNING ${RESOURCE_COLUMNS}
       ), audited AS (
         INSERT INTO resource_audit_event
           (actor_id, action, resource_id, resource_version, correlation_id)
         SELECT $3, 'RESOURCE_ARCHIVED', id, version, $4 FROM updated
       )
       SELECT * FROM updated`,
      [id, version, context.actorId, context.correlationId],
    );
    return result.rows[0] ? toResourceRow(result.rows[0]) : null;
  }

  async auditPublishBlocked(
    id: string,
    version: number,
    context: ResourceCommandContext,
  ): Promise<void> {
    await this.db.query(
      `INSERT INTO resource_audit_event
        (actor_id, action, resource_id, resource_version, correlation_id)
       VALUES ($1, 'RESOURCE_PUBLISH_BLOCKED', $2, $3, $4)`,
      [context.actorId, id, version, context.correlationId],
    );
  }

  private async selectById(client: DatabaseClient, id: string): Promise<ResourceRow | null> {
    const result = await client.query<ResourceDatabaseRow>(
      `SELECT ${RESOURCE_COLUMNS} FROM resource WHERE id = $1`,
      [id],
    );
    return result.rows[0] ? toResourceRow(result.rows[0]) : null;
  }

  private async audit(
    client: DatabaseClient,
    action: string,
    resourceId: string,
    version: number,
    context: ResourceCommandContext,
  ): Promise<void> {
    await client.query(
      `INSERT INTO resource_audit_event
        (actor_id, action, resource_id, resource_version, correlation_id)
       VALUES ($1, $2, $3, $4, $5)`,
      [context.actorId, action, resourceId, version, context.correlationId],
    );
  }
}
