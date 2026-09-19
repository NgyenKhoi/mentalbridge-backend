import { createHash, randomUUID } from 'node:crypto';
import { Inject, Injectable } from '@nestjs/common';

import { DATABASE_SERVICE_TOKEN } from '../application.tokens.js';
import type { DatabaseClient, DatabaseService } from '../database/database.service.js';
import type { SafetyDirectoryEntryWrite, SafetyDirectoryLookup } from './safety-directory.dto.js';
import type { SafetyDirectoryCoverage, SafetyDirectoryRow } from './safety-directory.types.js';

export interface SafetyDirectoryCommandContext {
  readonly actorId: string;
}

type DirectoryDatabaseRow = Omit<SafetyDirectoryRow, 'record_version' | 'coverage'> & {
  readonly record_version: string | number;
  readonly coverage: SafetyDirectoryCoverage[] | string;
};

type CommandRow = {
  readonly request_fingerprint: string;
  readonly entry_id: string;
};

type ResolvedArea = {
  readonly provinceCode: string;
  readonly districtCode: string | null;
};

const ENTRY_SELECT = `
  e.id, e.name, e.entry_type, e.phone, e.address, e.active,
  e.source_name, e.source_reference, e.source_retrieved_at, e.source_checksum,
  e.reviewed_by, e.reviewed_at, e.verified_by, e.verified_at, e.seed_key,
  e.created_at, e.updated_at, e.record_version,
  CASE
    WHEN NOT e.active THEN 'INACTIVE'
    WHEN e.reviewed_at IS NULL OR e.verified_at IS NULL THEN 'UNREVIEWED'
    WHEN now() >= e.verified_at + interval '90 days' THEN 'STALE'
    ELSE 'CURRENT'
  END AS review_state,
  COALESCE((SELECT json_agg(json_build_object(
    'level', c.coverage_level,
    'provinceCode', c.province_code,
    'provinceName', c.province_name,
    'districtCode', c.district_code,
    'districtName', c.district_name
  ) ORDER BY c.ordinal)
  FROM safety_directory_coverage c WHERE c.entry_id = e.id), '[]'::json) AS coverage`;

export class SafetyDirectoryIdempotencyConflictError extends Error {
  constructor() {
    super('Directory idempotency key was already used with a different request');
    this.name = 'SafetyDirectoryIdempotencyConflictError';
  }
}

function toRow(row: DirectoryDatabaseRow): SafetyDirectoryRow {
  let coverage: SafetyDirectoryCoverage[];
  if (typeof row.coverage === 'string') {
    const parsed: unknown = JSON.parse(row.coverage);
    if (!Array.isArray(parsed)) throw new Error('Invalid safety directory coverage payload');
    coverage = parsed as SafetyDirectoryCoverage[];
  } else {
    coverage = row.coverage;
  }
  return { ...row, record_version: Number(row.record_version), coverage };
}

function fingerprint(data: SafetyDirectoryEntryWrite): string {
  return createHash('sha256')
    .update(
      JSON.stringify({
        ...data,
        sourceRetrievedAt: data.sourceRetrievedAt.toISOString(),
        sourceChecksum: data.sourceChecksum ?? null,
      }),
    )
    .digest('hex');
}

@Injectable()
export class SafetyDirectoryRepository {
  constructor(
    @Inject(DATABASE_SERVICE_TOKEN)
    private readonly db: DatabaseService,
  ) {}

  async listAdmin(): Promise<SafetyDirectoryRow[]> {
    const result = await this.db.query<DirectoryDatabaseRow>(
      `SELECT ${ENTRY_SELECT}
       FROM safety_directory_entry e
       ORDER BY e.created_at DESC, e.id DESC`,
    );
    return result.rows.map(toRow);
  }

  async create(
    data: SafetyDirectoryEntryWrite,
    idempotencyKey: string,
    context: SafetyDirectoryCommandContext,
  ): Promise<SafetyDirectoryRow> {
    const requestFingerprint = fingerprint(data);
    const lockKey = `${context.actorId}:CREATE_DIRECTORY_ENTRY:${idempotencyKey}`;
    return this.db.withTransaction(async (client) => {
      await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [lockKey]);
      const existing = await client.query<CommandRow>(
        `SELECT request_fingerprint, entry_id
         FROM safety_directory_command_record
         WHERE actor_id = $1 AND operation = 'CREATE_DIRECTORY_ENTRY' AND idempotency_key = $2`,
        [context.actorId, idempotencyKey],
      );
      if ((existing.rowCount ?? 0) > 0) {
        const command = existing.rows[0];
        if (command.request_fingerprint !== requestFingerprint) {
          throw new SafetyDirectoryIdempotencyConflictError();
        }
        const replay = await this.selectById(client, command.entry_id);
        if (!replay) throw new SafetyDirectoryIdempotencyConflictError();
        return replay;
      }

      const entryId = randomUUID();
      await client.query(
        `INSERT INTO safety_directory_entry
          (id, name, entry_type, phone, address, source_name, source_reference,
           source_retrieved_at, source_checksum)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)`,
        [
          entryId,
          data.name,
          data.type,
          data.phone,
          data.address,
          data.sourceName,
          data.sourceReference,
          data.sourceRetrievedAt,
          data.sourceChecksum ?? null,
        ],
      );
      await this.replaceCoverage(client, entryId, data.coverage);
      await client.query(
        `INSERT INTO safety_directory_command_record
          (actor_id, operation, idempotency_key, request_fingerprint, entry_id)
         VALUES ($1, 'CREATE_DIRECTORY_ENTRY', $2, $3, $4)`,
        [context.actorId, idempotencyKey, requestFingerprint, entryId],
      );
      const created = await this.selectById(client, entryId);
      if (!created) throw new Error('Created directory entry is unavailable');
      return created;
    });
  }

  async update(
    id: string,
    version: number,
    data: SafetyDirectoryEntryWrite,
  ): Promise<SafetyDirectoryRow | null> {
    return this.db.withTransaction(async (client) => {
      const updated = await client.query<{ id: string }>(
        `UPDATE safety_directory_entry
         SET name = $3, entry_type = $4, phone = $5, address = $6,
             source_name = $7, source_reference = $8, source_retrieved_at = $9,
             source_checksum = $10, active = false, reviewed_by = NULL,
             reviewed_at = NULL, verified_by = NULL, verified_at = NULL,
             updated_at = now(), record_version = record_version + 1
         WHERE id = $1 AND record_version = $2
         RETURNING id`,
        [
          id,
          version,
          data.name,
          data.type,
          data.phone,
          data.address,
          data.sourceName,
          data.sourceReference,
          data.sourceRetrievedAt,
          data.sourceChecksum ?? null,
        ],
      );
      if ((updated.rowCount ?? 0) === 0) return null;
      await client.query('DELETE FROM safety_directory_coverage WHERE entry_id = $1', [id]);
      await this.replaceCoverage(client, id, data.coverage);
      return this.selectById(client, id);
    });
  }

  async review(
    id: string,
    version: number,
    context: SafetyDirectoryCommandContext,
  ): Promise<SafetyDirectoryRow | null> {
    return this.db.withTransaction(async (client) => {
      const updated = await client.query<{
        record_version: string | number;
        source_reference: string;
      }>(
        `UPDATE safety_directory_entry e
         SET active = true, reviewed_by = $3, reviewed_at = now(),
             verified_by = $3, verified_at = now(), updated_at = now(),
             record_version = record_version + 1
         WHERE e.id = $1 AND e.record_version = $2
           AND e.source_retrieved_at <= now()
           AND EXISTS (SELECT 1 FROM safety_directory_coverage c WHERE c.entry_id = e.id)
         RETURNING record_version, source_reference`,
        [id, version, context.actorId],
      );
      if ((updated.rowCount ?? 0) === 0) return null;
      const row = updated.rows[0];
      await client.query(
        `INSERT INTO safety_directory_review_history
          (entry_id, record_version, action, actor_id, source_reference)
         VALUES ($1, $2, 'REVIEWED', $3, $4)`,
        [id, row.record_version, context.actorId, row.source_reference],
      );
      return this.selectById(client, id);
    });
  }

  async deactivate(
    id: string,
    version: number,
    context: SafetyDirectoryCommandContext,
  ): Promise<SafetyDirectoryRow | null> {
    return this.db.withTransaction(async (client) => {
      const current = await this.selectById(client, id);
      if (!current || current.record_version !== version) return null;
      if (!current.active) return current;
      const updated = await client.query<{
        record_version: string | number;
        source_reference: string;
      }>(
        `UPDATE safety_directory_entry
         SET active = false, updated_at = now(), record_version = record_version + 1
         WHERE id = $1 AND record_version = $2
         RETURNING record_version, source_reference`,
        [id, version],
      );
      if ((updated.rowCount ?? 0) === 0) return null;
      const row = updated.rows[0];
      await client.query(
        `INSERT INTO safety_directory_review_history
          (entry_id, record_version, action, actor_id, source_reference)
         VALUES ($1, $2, 'DEACTIVATED', $3, $4)`,
        [id, row.record_version, context.actorId, row.source_reference],
      );
      return this.selectById(client, id);
    });
  }

  async lookup(
    query: SafetyDirectoryLookup,
  ): Promise<{ area: ResolvedArea | null; entries: SafetyDirectoryRow[] }> {
    const area = query.manualLocation
      ? await this.resolveManualLocation(query.manualLocation)
      : { provinceCode: query.provinceCode ?? '', districtCode: query.districtCode ?? null };
    if (!area) return { area: null, entries: [] };

    const result = await this.db.query<DirectoryDatabaseRow>(
      `SELECT ${ENTRY_SELECT}
       FROM safety_directory_entry e
       WHERE e.active
         AND e.reviewed_at IS NOT NULL
         AND e.verified_at IS NOT NULL
         AND e.verified_at <= now()
         AND now() < e.verified_at + interval '90 days'
         AND e.source_retrieved_at <= now()
         AND EXISTS (
           SELECT 1 FROM safety_directory_coverage match_coverage
           WHERE match_coverage.entry_id = e.id AND (
             match_coverage.coverage_level = 'NATIONWIDE' OR
             (match_coverage.province_code = $1 AND match_coverage.coverage_level = 'PROVINCE') OR
             (match_coverage.province_code = $1 AND match_coverage.district_code = $2
               AND match_coverage.coverage_level = 'DISTRICT')
           )
         )
       ORDER BY e.name, e.id
       LIMIT 100`,
      [area.provinceCode, area.districtCode],
    );
    return { area, entries: result.rows.map(toRow) };
  }

  private async resolveManualLocation(value: string): Promise<ResolvedArea | null> {
    // Resolution is performed exclusively against the reviewed, versioned area
    // vocabulary (safety_directory_area_alias). This table is independent of
    // directory-entry coverage rows, so the result is deterministic regardless
    // of how many entries cover a province or district.
    const result = await this.db.query<{
      province_code: string;
      district_code: string | null;
    }>(
      `SELECT province_code, district_code
       FROM safety_directory_area_alias
       WHERE lower(btrim(alias_text)) = lower(btrim($1))
       LIMIT 1`,
      [value.trim()],
    );
    if (result.rows.length !== 1) return null;
    return {
      provinceCode: result.rows[0].province_code,
      districtCode: result.rows[0].district_code,
    };
  }

  private async replaceCoverage(
    client: DatabaseClient,
    entryId: string,
    coverage: readonly SafetyDirectoryCoverage[],
  ): Promise<void> {
    for (const [ordinal, item] of coverage.entries()) {
      await client.query(
        `INSERT INTO safety_directory_coverage
          (entry_id, ordinal, coverage_level, province_code, province_name,
           district_code, district_name)
         VALUES ($1, $2, $3, $4, $5, $6, $7)`,
        [
          entryId,
          ordinal,
          item.level,
          item.provinceCode,
          item.provinceName,
          item.districtCode,
          item.districtName,
        ],
      );
    }
  }

  private async selectById(client: DatabaseClient, id: string): Promise<SafetyDirectoryRow | null> {
    const result = await client.query<DirectoryDatabaseRow>(
      `SELECT ${ENTRY_SELECT}
       FROM safety_directory_entry e
       WHERE e.id = $1`,
      [id],
    );
    return result.rows[0] ? toRow(result.rows[0]) : null;
  }
}
