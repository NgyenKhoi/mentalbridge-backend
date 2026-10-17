import { createHash, randomUUID } from 'node:crypto';
import { Inject, Injectable } from '@nestjs/common';

import { DATABASE_SERVICE_TOKEN } from '../application.tokens.js';
import type { DatabaseClient, DatabaseService } from '../database/database.service.js';
import type {
  EligibilityDeclaration,
  PublishResourceEligibilityRequest,
  ResourceEligibilityBatchResponse,
  ResourceEligibilityPublication,
  ResourceEligibilityQuery,
  ResourceEligibilityResult,
  WithdrawResourceEligibilityRequest,
} from '../generated/resource-eligibility.contract.js';

export interface EligibilityCommandContext {
  readonly actorId: string;
  readonly correlationId: string;
}

type ResourceStateRow = {
  readonly id: string;
  readonly version: string;
  readonly status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
  readonly locale: string;
  readonly reviewed_by: string | null;
  readonly reviewed_at: Date | null;
  readonly effective_at: Date | null;
  readonly expires_at: Date | null;
};

type CommandRow = {
  readonly request_fingerprint: string;
  readonly response_snapshot: ResourceEligibilityPublication;
};

type PublicationRow = {
  readonly id: string;
  readonly resource_id: string;
  readonly content_version: string;
  readonly policy_version: 'content-eligibility-v1';
  readonly locale: string;
  readonly effective_at: Date;
  readonly expires_at: Date | null;
  readonly published_at: Date;
  readonly withdrawn_at: Date | null;
  readonly withdrawal_reason_code: WithdrawResourceEligibilityRequest['reasonCode'] | null;
};

type DeclarationRow = {
  readonly target_domain: EligibilityDeclaration['targetDomain'];
  readonly eligibility_role: EligibilityDeclaration['role'];
  readonly instrument: EligibilityDeclaration['instrument'];
  readonly screening_levels: EligibilityDeclaration['screeningLevels'];
  readonly support_tiers: EligibilityDeclaration['supportTiers'];
};

type ResolutionRow = {
  readonly request_id: string;
  readonly resource_id: string;
  readonly content_version: string;
  readonly resource_exists: boolean;
  readonly current_version: string | null;
  readonly resource_status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED' | null;
  readonly category: ResourceEligibilityResult['category'];
  readonly title: string | null;
  readonly summary: string | null;
  readonly external_url: string | null;
  readonly reviewed: boolean;
  readonly resource_locale: string | null;
  readonly requested_locale: string;
  readonly resource_effective_at: Date | null;
  readonly resource_expires_at: Date | null;
  readonly publication_id: string | null;
  readonly publication_locale: string | null;
  readonly publication_effective_at: Date | null;
  readonly publication_expires_at: Date | null;
  readonly withdrawn_at: Date | null;
  readonly matched_role: EligibilityDeclaration['role'] | null;
  readonly adjunct_only_match: boolean;
  readonly resolved_at: Date;
};

export class EligibilityCommandConflictError extends Error {}
export class EligibilityResourceNotFoundError extends Error {}

@Injectable()
export class ResourceEligibilityRepository {
  constructor(
    @Inject(DATABASE_SERVICE_TOKEN)
    private readonly db: DatabaseService,
  ) {}

  async publish(
    resourceId: string,
    contentVersion: string,
    request: PublishResourceEligibilityRequest,
    idempotencyKey: string,
    context: EligibilityCommandContext,
  ): Promise<ResourceEligibilityPublication> {
    const normalized = normalizePublicationRequest(request);
    const requestFingerprint = fingerprint({ resourceId, contentVersion, ...normalized });
    return this.db.withTransaction(async (client) => {
      const replay = await this.lockAndReplay(
        client,
        'PUBLISH_ELIGIBILITY',
        idempotencyKey,
        requestFingerprint,
        context,
      );
      if (replay) return replay;

      const resource = await this.lockResource(client, resourceId);
      if (!resource) throw new EligibilityResourceNotFoundError();
      validatePublishableResource(resource, contentVersion, request);

      const publicationId = randomUUID();
      const inserted = await client.query<PublicationRow>(
        `INSERT INTO resource_eligibility_publication
          (id, resource_id, content_version, policy_version, locale, effective_at, expires_at, published_by)
         VALUES ($1, $2, $3::bigint, $4, $5, $6, $7, $8)
         RETURNING id, resource_id, content_version, policy_version, locale,
           effective_at, expires_at, published_at, NULL::timestamptz AS withdrawn_at,
           NULL::varchar AS withdrawal_reason_code`,
        [
          publicationId,
          resourceId,
          contentVersion,
          request.policyVersion,
          request.locale,
          new Date(request.effectiveAt),
          request.expiresAt ? new Date(request.expiresAt) : null,
          context.actorId,
        ],
      );

      for (const declaration of normalized.declarations) {
        await client.query(
          `INSERT INTO resource_eligibility_declaration
            (publication_id, target_domain, eligibility_role, instrument, screening_levels, support_tiers)
           VALUES ($1, $2, $3, $4, $5::text[], $6::text[])`,
          [
            publicationId,
            declaration.targetDomain,
            declaration.role,
            declaration.instrument,
            [...declaration.screeningLevels],
            [...declaration.supportTiers],
          ],
        );
      }

      const response = publicationResponse(inserted.rows[0], normalized.declarations);
      await this.recordCommand(
        client,
        'PUBLISH_ELIGIBILITY',
        idempotencyKey,
        requestFingerprint,
        publicationId,
        response,
        context,
      );
      await this.audit(client, 'ELIGIBILITY_PUBLISHED', resourceId, contentVersion, context);
      return response;
    });
  }

  async withdraw(
    resourceId: string,
    contentVersion: string,
    request: WithdrawResourceEligibilityRequest,
    idempotencyKey: string,
    context: EligibilityCommandContext,
  ): Promise<ResourceEligibilityPublication> {
    const requestFingerprint = fingerprint({ resourceId, contentVersion, ...request });
    return this.db.withTransaction(async (client) => {
      const replay = await this.lockAndReplay(
        client,
        'WITHDRAW_ELIGIBILITY',
        idempotencyKey,
        requestFingerprint,
        context,
      );
      if (replay) return replay;

      const publication = await this.selectPublication(client, resourceId, contentVersion, true);
      if (!publication) throw new EligibilityResourceNotFoundError();
      if (publication.withdrawn_at) {
        if (publication.withdrawal_reason_code !== request.reasonCode) {
          throw new EligibilityCommandConflictError('Eligibility was withdrawn for another reason');
        }
      } else {
        await client.query(
          `INSERT INTO resource_eligibility_withdrawal
            (publication_id, reason_code, withdrawn_by)
           VALUES ($1, $2, $3)`,
          [publication.id, request.reasonCode, context.actorId],
        );
      }

      const current = await this.selectPublication(client, resourceId, contentVersion, false);
      if (!current) throw new EligibilityResourceNotFoundError();
      const declarations = await this.selectDeclarations(client, current.id);
      const response = publicationResponse(current, declarations);
      await this.recordCommand(
        client,
        'WITHDRAW_ELIGIBILITY',
        idempotencyKey,
        requestFingerprint,
        current.id,
        response,
        context,
      );
      await this.audit(client, 'ELIGIBILITY_WITHDRAWN', resourceId, contentVersion, context);
      return response;
    });
  }

  async resolve(
    requests: readonly ResourceEligibilityQuery[],
  ): Promise<ResourceEligibilityBatchResponse> {
    const result = await this.db.query<ResolutionRow>(
      `WITH requested AS (
         SELECT q.*, item.ordinal
         FROM jsonb_array_elements($1::jsonb) WITH ORDINALITY AS item(value, ordinal)
         CROSS JOIN LATERAL jsonb_to_record(item.value) AS q(
           request_id uuid,
           resource_id uuid,
           content_version bigint,
           target_domain varchar,
           required_role varchar,
           instrument varchar,
           screening_level varchar,
           support_tier varchar,
           locale varchar
         )
       )
       SELECT
         q.request_id::text,
         q.resource_id::text,
         q.content_version::text,
         (r.id IS NOT NULL) AS resource_exists,
         r.version::text AS current_version,
         r.status AS resource_status,
         r.category,
         r.title,
         r.summary,
         r.external_url,
         (r.reviewed_by IS NOT NULL AND r.reviewed_at IS NOT NULL) AS reviewed,
         r.locale AS resource_locale,
         q.locale AS requested_locale,
         r.effective_at AS resource_effective_at,
         r.expires_at AS resource_expires_at,
         p.id::text AS publication_id,
         p.locale AS publication_locale,
         p.effective_at AS publication_effective_at,
         p.expires_at AS publication_expires_at,
         w.withdrawn_at,
         matched.eligibility_role AS matched_role,
         COALESCE(adjunct.adjunct_only_match, false) AS adjunct_only_match,
         statement_timestamp() AS resolved_at
       FROM requested q
       LEFT JOIN resource r ON r.id = q.resource_id
       LEFT JOIN resource_eligibility_publication p
         ON p.resource_id = q.resource_id
        AND p.content_version = q.content_version
        AND p.policy_version = 'content-eligibility-v1'
       LEFT JOIN resource_eligibility_withdrawal w ON w.publication_id = p.id
       LEFT JOIN LATERAL (
         SELECT d.eligibility_role
         FROM resource_eligibility_declaration d
         WHERE d.publication_id = p.id
           AND d.target_domain = q.target_domain
           AND d.instrument = q.instrument
           AND d.screening_levels @> ARRAY[q.screening_level]::text[]
           AND d.support_tiers @> ARRAY[q.support_tier]::text[]
           AND (q.required_role = 'PRIMARY_OR_ADJUNCT' OR d.eligibility_role = 'PRIMARY')
         ORDER BY CASE d.eligibility_role WHEN 'PRIMARY' THEN 0 ELSE 1 END
         LIMIT 1
       ) matched ON true
       LEFT JOIN LATERAL (
         SELECT true AS adjunct_only_match
         FROM resource_eligibility_declaration d
         WHERE d.publication_id = p.id
           AND d.target_domain = q.target_domain
           AND d.instrument = q.instrument
           AND d.screening_levels @> ARRAY[q.screening_level]::text[]
           AND d.support_tiers @> ARRAY[q.support_tier]::text[]
           AND d.eligibility_role = 'ADJUNCT'
         LIMIT 1
       ) adjunct ON true
       ORDER BY q.ordinal`,
      [
        JSON.stringify(
          requests.map((request) => ({
            request_id: request.requestId,
            resource_id: request.resourceId,
            content_version: request.contentVersion,
            target_domain: request.targetDomain,
            required_role: request.requiredRole,
            instrument: request.instrument,
            screening_level: request.screeningLevel,
            support_tier: request.supportTier,
            locale: request.locale,
          })),
        ),
      ],
    );

    const resolvedAt = result.rows[0]?.resolved_at.toISOString() ?? new Date().toISOString();
    return {
      policyVersion: 'content-eligibility-v1',
      resolvedAt,
      results: result.rows.map(resolveRow),
    };
  }

  private async lockAndReplay(
    client: DatabaseClient,
    operation: 'PUBLISH_ELIGIBILITY' | 'WITHDRAW_ELIGIBILITY',
    idempotencyKey: string,
    requestFingerprint: string,
    context: EligibilityCommandContext,
  ): Promise<ResourceEligibilityPublication | null> {
    await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [
      `${context.actorId}:${operation}:${idempotencyKey}`,
    ]);
    const existing = await client.query<CommandRow>(
      `SELECT request_fingerprint, response_snapshot
       FROM resource_eligibility_command_record
       WHERE actor_id = $1 AND operation = $2 AND idempotency_key = $3`,
      [context.actorId, operation, idempotencyKey],
    );
    if (existing.rowCount === 0) return null;
    const command = existing.rows[0];
    if (command.request_fingerprint !== requestFingerprint) {
      throw new EligibilityCommandConflictError('Idempotency key was reused with another request');
    }
    return command.response_snapshot;
  }

  private async lockResource(
    client: DatabaseClient,
    resourceId: string,
  ): Promise<ResourceStateRow | null> {
    const result = await client.query<ResourceStateRow>(
      `SELECT id, version::text, status, locale, reviewed_by, reviewed_at, effective_at, expires_at
       FROM resource WHERE id = $1 FOR UPDATE`,
      [resourceId],
    );
    return result.rows[0] ?? null;
  }

  private async selectPublication(
    client: DatabaseClient,
    resourceId: string,
    contentVersion: string,
    lock: boolean,
  ): Promise<PublicationRow | null> {
    const result = await client.query<PublicationRow>(
      `SELECT p.id, p.resource_id, p.content_version::text, p.policy_version, p.locale,
         p.effective_at, p.expires_at, p.published_at, w.withdrawn_at,
         w.reason_code AS withdrawal_reason_code
       FROM resource_eligibility_publication p
       LEFT JOIN resource_eligibility_withdrawal w ON w.publication_id = p.id
       WHERE p.resource_id = $1 AND p.content_version = $2::bigint
         AND p.policy_version = 'content-eligibility-v1'
       ${lock ? 'FOR UPDATE OF p' : ''}`,
      [resourceId, contentVersion],
    );
    return result.rows[0] ?? null;
  }

  private async selectDeclarations(
    client: DatabaseClient,
    publicationId: string,
  ): Promise<EligibilityDeclaration[]> {
    const result = await client.query<DeclarationRow>(
      `SELECT target_domain, eligibility_role, instrument, screening_levels, support_tiers
       FROM resource_eligibility_declaration
       WHERE publication_id = $1
       ORDER BY target_domain, eligibility_role, instrument`,
      [publicationId],
    );
    return result.rows.map((row) => ({
      targetDomain: row.target_domain,
      role: row.eligibility_role,
      instrument: row.instrument,
      screeningLevels: row.screening_levels,
      supportTiers: row.support_tiers,
    }));
  }

  private async recordCommand(
    client: DatabaseClient,
    operation: 'PUBLISH_ELIGIBILITY' | 'WITHDRAW_ELIGIBILITY',
    idempotencyKey: string,
    requestFingerprint: string,
    publicationId: string,
    response: ResourceEligibilityPublication,
    context: EligibilityCommandContext,
  ): Promise<void> {
    await client.query(
      `INSERT INTO resource_eligibility_command_record
        (actor_id, operation, idempotency_key, request_fingerprint, publication_id, response_snapshot)
       VALUES ($1, $2, $3, $4, $5, $6::jsonb)`,
      [
        context.actorId,
        operation,
        idempotencyKey,
        requestFingerprint,
        publicationId,
        JSON.stringify(response),
      ],
    );
  }

  private async audit(
    client: DatabaseClient,
    action: 'ELIGIBILITY_PUBLISHED' | 'ELIGIBILITY_WITHDRAWN',
    resourceId: string,
    contentVersion: string,
    context: EligibilityCommandContext,
  ): Promise<void> {
    await client.query(
      `INSERT INTO resource_audit_event
        (actor_id, action, resource_id, resource_version, correlation_id)
       VALUES ($1, $2, $3, $4::bigint, $5)`,
      [context.actorId, action, resourceId, contentVersion, context.correlationId],
    );
  }
}

function validatePublishableResource(
  resource: ResourceStateRow,
  contentVersion: string,
  request: PublishResourceEligibilityRequest,
): void {
  if (
    resource.version !== contentVersion ||
    resource.status !== 'PUBLISHED' ||
    !resource.reviewed_by ||
    !resource.reviewed_at
  ) {
    throw new EligibilityCommandConflictError(
      'Exact resource version is not reviewed and published',
    );
  }
  if (resource.locale !== request.locale) {
    throw new EligibilityCommandConflictError(
      'Eligibility locale must match the exact content version',
    );
  }
  const effectiveAt = new Date(request.effectiveAt);
  const expiresAt = request.expiresAt ? new Date(request.expiresAt) : null;
  if (resource.effective_at && effectiveAt < resource.effective_at) {
    throw new EligibilityCommandConflictError(
      'Eligibility cannot start before content publication',
    );
  }
  if (resource.expires_at && (!expiresAt || expiresAt > resource.expires_at)) {
    throw new EligibilityCommandConflictError('Eligibility cannot outlive content publication');
  }
}

function normalizePublicationRequest(
  request: PublishResourceEligibilityRequest,
): PublishResourceEligibilityRequest {
  return {
    ...request,
    declarations: [...request.declarations]
      .map((declaration) => ({
        ...declaration,
        screeningLevels: [...declaration.screeningLevels].sort(),
        supportTiers: [...declaration.supportTiers].sort(),
      }))
      .sort((left, right) =>
        `${left.targetDomain}:${left.instrument}`.localeCompare(
          `${right.targetDomain}:${right.instrument}`,
        ),
      ),
  };
}

function fingerprint(value: unknown): string {
  return createHash('sha256').update(JSON.stringify(value)).digest('hex');
}

function publicationResponse(
  row: PublicationRow,
  declarations: readonly EligibilityDeclaration[],
): ResourceEligibilityPublication {
  return {
    publicationId: row.id,
    resourceId: row.resource_id,
    contentVersion: row.content_version,
    policyVersion: row.policy_version,
    locale: row.locale,
    state: row.withdrawn_at ? 'WITHDRAWN' : 'PUBLISHED',
    effectiveAt: row.effective_at.toISOString(),
    expiresAt: row.expires_at?.toISOString() ?? null,
    declarations,
    publishedAt: row.published_at.toISOString(),
    withdrawnAt: row.withdrawn_at?.toISOString() ?? null,
    withdrawalReasonCode: row.withdrawal_reason_code,
  };
}

function resolveRow(row: ResolutionRow): ResourceEligibilityResult {
  const base = {
    requestId: row.request_id,
    resourceId: row.resource_id,
    contentVersion: row.content_version,
    role: null,
    publicationId: row.publication_id,
    category: null,
    title: null,
    summary: null,
    externalUrl: null,
  } as const;
  const now = row.resolved_at.getTime();

  if (!row.resource_exists) return decision(base, 'NOT_FOUND', 'RESOURCE_NOT_FOUND');
  if (!row.publication_id) {
    return row.current_version !== row.content_version
      ? decision(base, 'STALE', 'CONTENT_VERSION_STALE')
      : decision(base, 'INELIGIBLE', 'NO_ELIGIBILITY_PUBLICATION');
  }
  if (row.withdrawn_at) return decision(base, 'WITHDRAWN', 'ELIGIBILITY_WITHDRAWN');
  if (row.resource_status === 'ARCHIVED') return decision(base, 'WITHDRAWN', 'RESOURCE_ARCHIVED');
  if (row.resource_status !== 'PUBLISHED' || !row.reviewed) {
    return decision(base, 'INELIGIBLE', 'RESOURCE_NOT_PUBLISHED');
  }
  if (row.current_version !== row.content_version) {
    return decision(base, 'STALE', 'CONTENT_VERSION_STALE');
  }
  if (
    row.requested_locale !== row.resource_locale ||
    row.requested_locale !== row.publication_locale
  ) {
    return decision(base, 'INELIGIBLE', 'LOCALE_MISMATCH');
  }
  if (row.publication_effective_at && now < row.publication_effective_at.getTime()) {
    return decision(base, 'INELIGIBLE', 'NOT_YET_EFFECTIVE');
  }
  const expiresAt = minimumInstant(row.publication_expires_at, row.resource_expires_at);
  if (expiresAt && now >= expiresAt.getTime()) {
    return decision(base, 'WITHDRAWN', 'EFFECTIVE_WINDOW_ENDED');
  }
  if (row.resource_effective_at && now < row.resource_effective_at.getTime()) {
    return decision(base, 'INELIGIBLE', 'NOT_YET_EFFECTIVE');
  }
  if (!row.matched_role) {
    return row.adjunct_only_match
      ? decision(base, 'INELIGIBLE', 'PRIMARY_REQUIRED')
      : decision(base, 'INELIGIBLE', 'DOMAIN_OR_PATHWAY_NOT_ELIGIBLE');
  }
  return {
    ...base,
    outcome: 'ELIGIBLE',
    reasonCode: 'ELIGIBLE_MATCH',
    role: row.matched_role,
    category: row.category,
    title: row.title,
    summary: row.summary,
    externalUrl: row.external_url,
  };
}

function decision(
  base: Pick<
    ResourceEligibilityResult,
    | 'requestId'
    | 'resourceId'
    | 'contentVersion'
    | 'role'
    | 'publicationId'
    | 'category'
    | 'title'
    | 'summary'
    | 'externalUrl'
  >,
  outcome: ResourceEligibilityResult['outcome'],
  reasonCode: ResourceEligibilityResult['reasonCode'],
): ResourceEligibilityResult {
  return { ...base, outcome, reasonCode };
}

function minimumInstant(left: Date | null, right: Date | null): Date | null {
  if (!left) return right;
  if (!right) return left;
  return left < right ? left : right;
}
