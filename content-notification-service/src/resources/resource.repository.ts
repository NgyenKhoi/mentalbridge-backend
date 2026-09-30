import { createHash, randomUUID } from 'node:crypto';
import { Inject, Injectable } from '@nestjs/common';
import type { DatabaseClient, DatabaseService } from '../database/database.service.js';
import { DATABASE_SERVICE_TOKEN } from '../application.tokens.js';
import type {
  ResourceCategory,
  ResourceCompletionMode,
  ResourceInteractionType,
  ResourceJson,
  ResourceKind,
  ResourceRepeatability,
  ResourceRow,
  ResourceSourceReviewStatus,
} from './resource.types.js';

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
  readonly resourceKind?: ResourceKind;
  readonly interactionType?: ResourceInteractionType;
  readonly repeatability?: ResourceRepeatability;
  readonly completionMode?: ResourceCompletionMode;
  readonly streakEligible?: boolean;
  readonly expectedDurationMinutes?: number;
  readonly cooldownDays?: number;
  readonly recommendedFrequencyPerWeek?: number;
  readonly planTags?: readonly string[];
  readonly structuredContent?: ResourceJson;
  readonly interactionConfig?: ResourceJson;
  readonly safetyNotes?: readonly string[];
  readonly catalogueVisibility?: 'LISTED' | 'DIRECT_ONLY';
  readonly contentVersionLabel?: string;
  readonly sourceReviewStatus?: ResourceSourceReviewStatus;
  readonly sourceOrganization?: string | null;
  readonly sourceTitle?: string | null;
  readonly sourceUrl?: string | null;
  readonly sourceReviewNote?: string | null;
  readonly sourceRetrievedAt?: Date | null;
  readonly sourceContentHash?: string | null;
  readonly effectiveAt?: Date | null;
  readonly expiresAt?: Date | null;
}

type NormalizedCreateResourceData = CreateResourceData &
  Required<
    Pick<
      CreateResourceData,
      | 'resourceKind'
      | 'interactionType'
      | 'repeatability'
      | 'completionMode'
      | 'streakEligible'
      | 'expectedDurationMinutes'
      | 'cooldownDays'
      | 'recommendedFrequencyPerWeek'
      | 'planTags'
      | 'structuredContent'
      | 'interactionConfig'
      | 'safetyNotes'
      | 'catalogueVisibility'
      | 'contentVersionLabel'
      | 'sourceReviewStatus'
    >
  >;

export interface UpdateResourceData {
  readonly locale?: string;
  readonly title?: string;
  readonly summary?: string;
  readonly contentBody?: string | null;
  readonly externalUrl?: string | null;
  readonly resourceKind?: ResourceKind;
  readonly interactionType?: ResourceInteractionType;
  readonly repeatability?: ResourceRepeatability;
  readonly completionMode?: ResourceCompletionMode;
  readonly streakEligible?: boolean;
  readonly expectedDurationMinutes?: number;
  readonly cooldownDays?: number;
  readonly recommendedFrequencyPerWeek?: number;
  readonly planTags?: readonly string[];
  readonly structuredContent?: ResourceJson;
  readonly interactionConfig?: ResourceJson;
  readonly safetyNotes?: readonly string[];
  readonly catalogueVisibility?: 'LISTED' | 'DIRECT_ONLY';
  readonly contentVersionLabel?: string;
  readonly sourceReviewStatus?: ResourceSourceReviewStatus;
  readonly sourceOrganization?: string | null;
  readonly sourceTitle?: string | null;
  readonly sourceUrl?: string | null;
  readonly sourceReviewNote?: string | null;
  readonly sourceRetrievedAt?: Date | null;
  readonly sourceContentHash?: string | null;
  readonly effectiveAt?: Date | null;
  readonly expiresAt?: Date | null;
  readonly version: number;
}

type IdempotencyRow = {
  readonly request_fingerprint: string;
  readonly resource_id: string | null;
};

export type ResourceDatabaseRow = Omit<ResourceRow, 'version'> & {
  readonly version: string | number;
};

export const RESOURCE_COLUMNS = `id, category, locale, title, summary, content_body, external_url,
  source_organization, source_title, source_url, source_review_note, catalogue_visibility,
  status, reviewed_by, reviewed_at, effective_at, expires_at,
  created_at, updated_at, version, resource_kind, interaction_type, repeatability,
  completion_mode, streak_eligible, expected_duration_minutes, cooldown_days,
  recommended_frequency_per_week, plan_tags, structured_content, interaction_config,
  safety_notes, source_retrieved_at, source_content_hash, content_version_label,
  source_review_status`;

export class ResourceIdempotencyConflictError extends Error {
  constructor() {
    super('Idempotency key was already used with a different request');
    this.name = 'ResourceIdempotencyConflictError';
  }
}

export function toResourceRow(row: ResourceDatabaseRow): ResourceRow {
  return { ...row, version: Number(row.version) };
}

function fingerprint(data: NormalizedCreateResourceData): string {
  return createHash('sha256')
    .update(
      JSON.stringify({
        category: data.category,
        locale: data.locale,
        title: data.title,
        summary: data.summary,
        contentBody: data.contentBody ?? null,
        externalUrl: data.externalUrl ?? null,
        resourceKind: data.resourceKind,
        interactionType: data.interactionType,
        repeatability: data.repeatability,
        completionMode: data.completionMode,
        streakEligible: data.streakEligible,
        expectedDurationMinutes: data.expectedDurationMinutes,
        cooldownDays: data.cooldownDays,
        recommendedFrequencyPerWeek: data.recommendedFrequencyPerWeek,
        planTags: data.planTags,
        structuredContent: data.structuredContent,
        interactionConfig: data.interactionConfig,
        safetyNotes: data.safetyNotes,
        catalogueVisibility: data.catalogueVisibility,
        contentVersionLabel: data.contentVersionLabel,
        sourceReviewStatus: data.sourceReviewStatus,
        sourceOrganization: data.sourceOrganization ?? null,
        sourceTitle: data.sourceTitle ?? null,
        sourceUrl: data.sourceUrl ?? null,
        sourceReviewNote: data.sourceReviewNote ?? null,
        sourceRetrievedAt: data.sourceRetrievedAt?.toISOString() ?? null,
        sourceContentHash: data.sourceContentHash ?? null,
        effectiveAt: data.effectiveAt?.toISOString() ?? null,
        expiresAt: data.expiresAt?.toISOString() ?? null,
      }),
    )
    .digest('hex');
}

function createDefaults(category: ResourceCategory) {
  if (category === 'BREATHING') {
    return {
      resourceKind: 'PRACTICE' as const,
      interactionType: 'BREATHING_PACER' as const,
      repeatability: 'REPEATABLE' as const,
      completionMode: 'TIMED' as const,
      streakEligible: true,
    };
  }
  if (category === 'MEDITATION') {
    return {
      resourceKind: 'PRACTICE' as const,
      interactionType: 'GROUNDING_GUIDE' as const,
      repeatability: 'REPEATABLE' as const,
      completionMode: 'STEPS' as const,
      streakEligible: true,
    };
  }
  if (category === 'VIDEO') {
    return {
      resourceKind: 'LEARNING' as const,
      interactionType: 'VIDEO_TRANSCRIPT' as const,
      repeatability: 'ONE_TIME' as const,
      completionMode: 'VIDEO_CONFIRMATION' as const,
      streakEligible: false,
    };
  }
  if (category === 'JOURNALING') {
    return {
      resourceKind: 'REFLECTION' as const,
      interactionType: 'REFLECTION' as const,
      repeatability: 'REPEATABLE' as const,
      completionMode: 'STEPS' as const,
      streakEligible: false,
    };
  }
  if (category === 'COMMUNITY') {
    return {
      resourceKind: 'HABIT' as const,
      interactionType: 'WALK_TIMER' as const,
      repeatability: 'REPEATABLE' as const,
      completionMode: 'TIMED' as const,
      streakEligible: true,
    };
  }
  return {
    resourceKind: 'LEARNING' as const,
    interactionType: 'STRUCTURED_READER' as const,
    repeatability: 'ONE_TIME' as const,
    completionMode: 'EXPLICIT' as const,
    streakEligible: false,
  };
}

function normalizeCreateData(data: CreateResourceData): NormalizedCreateResourceData {
  const defaults = createDefaults(data.category);
  return {
    ...data,
    resourceKind: data.resourceKind ?? defaults.resourceKind,
    interactionType: data.interactionType ?? defaults.interactionType,
    repeatability: data.repeatability ?? defaults.repeatability,
    completionMode: data.completionMode ?? defaults.completionMode,
    streakEligible: data.streakEligible ?? defaults.streakEligible,
    expectedDurationMinutes: data.expectedDurationMinutes ?? 5,
    cooldownDays: data.cooldownDays ?? 0,
    recommendedFrequencyPerWeek: data.recommendedFrequencyPerWeek ?? 1,
    planTags: data.planTags ?? ['DEPRESSIVE_SYMPTOMS', 'ANXIETY_SYMPTOMS'],
    structuredContent: data.structuredContent ?? {},
    interactionConfig: data.interactionConfig ?? {},
    safetyNotes: data.safetyNotes ?? [],
    catalogueVisibility: data.catalogueVisibility ?? 'DIRECT_ONLY',
    contentVersionLabel: data.contentVersionLabel ?? 'draft-v1',
    sourceReviewStatus: data.sourceReviewStatus ?? 'NEEDS_SOURCE_REVIEW',
  };
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
      "r.source_review_status = 'REVIEWED'",
      "r.catalogue_visibility = 'LISTED'",
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

  async findPublishedEligibleById(
    id: string,
    locale: string,
    contentVersion?: string,
  ): Promise<ResourceRow | null> {
    const versionPredicate = contentVersion === undefined ? '' : 'AND version::text = $3';
    const result = await this.db.query<ResourceDatabaseRow>(
      `SELECT ${RESOURCE_COLUMNS}
       FROM resource
       WHERE id = $1
         AND locale = $2
         ${versionPredicate}
         AND status = 'PUBLISHED'
         AND reviewed_by IS NOT NULL
         AND reviewed_at IS NOT NULL
         AND source_review_status = 'REVIEWED'
         AND (effective_at IS NULL OR effective_at <= now())
         AND (expires_at IS NULL OR expires_at > now())`,
      contentVersion === undefined ? [id, locale] : [id, locale, contentVersion],
    );
    return result.rows[0] ? toResourceRow(result.rows[0]) : null;
  }

  async create(
    data: CreateResourceData,
    idempotencyKey: string,
    context: ResourceCommandContext,
  ): Promise<ResourceRow> {
    const normalized = normalizeCreateData(data);
    const requestFingerprint = fingerprint(normalized);
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
          (id, category, locale, title, summary, content_body, external_url,
           source_organization, source_title, source_url, source_review_note, status,
           effective_at, expires_at, resource_kind, interaction_type, repeatability,
           completion_mode, streak_eligible, expected_duration_minutes, cooldown_days,
           recommended_frequency_per_week, plan_tags, structured_content,
           interaction_config, safety_notes, catalogue_visibility, content_version_label,
           source_review_status, source_retrieved_at, source_content_hash)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, 'DRAFT', $12, $13,
           $14, $15, $16, $17, $18, $19, $20, $21, $22::text[], $23::jsonb,
           $24::jsonb, $25::text[], $26, $27, $28, $29, $30)
         RETURNING ${RESOURCE_COLUMNS}`,
        [
          resourceId,
          normalized.category,
          normalized.locale,
          normalized.title,
          normalized.summary,
          normalized.contentBody ?? null,
          normalized.externalUrl ?? null,
          normalized.sourceOrganization ?? null,
          normalized.sourceTitle ?? null,
          normalized.sourceUrl ?? null,
          normalized.sourceReviewNote ?? null,
          normalized.effectiveAt ?? null,
          normalized.expiresAt ?? null,
          normalized.resourceKind,
          normalized.interactionType,
          normalized.repeatability,
          normalized.completionMode,
          normalized.streakEligible,
          normalized.expectedDurationMinutes,
          normalized.cooldownDays,
          normalized.recommendedFrequencyPerWeek,
          normalized.planTags,
          JSON.stringify(normalized.structuredContent),
          JSON.stringify(normalized.interactionConfig),
          normalized.safetyNotes,
          normalized.catalogueVisibility,
          normalized.contentVersionLabel,
          normalized.sourceReviewStatus,
          normalized.sourceRetrievedAt ?? null,
          normalized.sourceContentHash ?? null,
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
    const params: (string | number | boolean | Date | readonly string[] | null)[] = [
      id,
      data.version,
    ];
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
      ['resource_kind', data.resourceKind],
      ['interaction_type', data.interactionType],
      ['repeatability', data.repeatability],
      ['completion_mode', data.completionMode],
      ['streak_eligible', data.streakEligible],
      ['expected_duration_minutes', data.expectedDurationMinutes],
      ['cooldown_days', data.cooldownDays],
      ['recommended_frequency_per_week', data.recommendedFrequencyPerWeek],
      ['plan_tags', data.planTags],
      [
        'structured_content',
        data.structuredContent === undefined ? undefined : JSON.stringify(data.structuredContent),
      ],
      [
        'interaction_config',
        data.interactionConfig === undefined ? undefined : JSON.stringify(data.interactionConfig),
      ],
      ['safety_notes', data.safetyNotes],
      ['catalogue_visibility', data.catalogueVisibility],
      ['content_version_label', data.contentVersionLabel],
      ['source_review_status', data.sourceReviewStatus],
      ['source_organization', data.sourceOrganization],
      ['source_title', data.sourceTitle],
      ['source_url', data.sourceUrl],
      ['source_review_note', data.sourceReviewNote],
      ['source_retrieved_at', data.sourceRetrievedAt],
      ['source_content_hash', data.sourceContentHash],
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
