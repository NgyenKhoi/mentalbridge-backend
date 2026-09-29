import { Inject, Injectable } from '@nestjs/common';

import { DATABASE_SERVICE_TOKEN } from '../application.tokens.js';
import type { DatabaseService } from '../database/database.service.js';
import type {
  ResourceProgressItem,
  ResourceProgressRow,
  ResourceProgressUpdate,
} from './resource-progress.types.js';

const COLUMNS = `owner_id, resource_id, local_date, resource_version, status,
  completed_action_ids, completed_at, created_at, updated_at, version`;

function date(value: string | Date): string {
  return value instanceof Date ? value.toISOString().slice(0, 10) : value;
}

function item(row: ResourceProgressRow): ResourceProgressItem {
  return {
    resourceId: row.resource_id,
    localDate: date(row.local_date),
    contentVersion: String(row.resource_version),
    status: row.status,
    completedActionIds: row.completed_action_ids,
    completedAt: row.completed_at?.toISOString() ?? null,
    updatedAt: row.updated_at.toISOString(),
    version: String(row.version),
  };
}

@Injectable()
export class ResourceProgressRepository {
  constructor(
    @Inject(DATABASE_SERVICE_TOKEN)
    private readonly db: DatabaseService,
  ) {}

  async list(ownerId: string, from: string, to: string): Promise<readonly ResourceProgressItem[]> {
    const result = await this.db.query<ResourceProgressRow>(
      `SELECT ${COLUMNS}
       FROM resource_daily_progress
       WHERE owner_id = $1 AND local_date BETWEEN $2::date AND $3::date
       ORDER BY local_date DESC, updated_at DESC, resource_id`,
      [ownerId, from, to],
    );
    return result.rows.map(item);
  }

  async save(
    ownerId: string,
    resourceId: string,
    localDate: string,
    update: ResourceProgressUpdate,
  ): Promise<ResourceProgressItem | null> {
    const result = await this.db.query<ResourceProgressRow>(
      `WITH eligible AS (
         SELECT id, version
         FROM resource
         WHERE id = $2 AND status = 'PUBLISHED'
           AND reviewed_at IS NOT NULL
           AND (effective_at IS NULL OR effective_at <= now())
           AND (expires_at IS NULL OR expires_at > now())
       ), written AS (
         INSERT INTO resource_daily_progress
           (owner_id, resource_id, local_date, resource_version, status,
            completed_action_ids, completed_at)
         SELECT $1, id, $3::date, version, $4::varchar(16), $5::text[],
           CASE WHEN $4::varchar(16) = 'COMPLETED' THEN now() ELSE NULL END
         FROM eligible
         ON CONFLICT (owner_id, resource_id, local_date) DO UPDATE
         SET resource_version = EXCLUDED.resource_version,
             status = CASE
               WHEN resource_daily_progress.status = 'COMPLETED' THEN 'COMPLETED'
               ELSE EXCLUDED.status
             END,
             completed_action_ids = EXCLUDED.completed_action_ids,
             completed_at = CASE
               WHEN resource_daily_progress.completed_at IS NOT NULL
                 THEN resource_daily_progress.completed_at
               WHEN EXCLUDED.status = 'COMPLETED' THEN now()
               ELSE NULL
             END,
             updated_at = now(),
             version = resource_daily_progress.version + 1
         WHERE (
                resource_daily_progress.status <> 'COMPLETED'
                AND resource_daily_progress.status IS DISTINCT FROM EXCLUDED.status
               )
            OR resource_daily_progress.completed_action_ids IS DISTINCT FROM EXCLUDED.completed_action_ids
            OR resource_daily_progress.resource_version IS DISTINCT FROM EXCLUDED.resource_version
         RETURNING ${COLUMNS}
       )
       SELECT ${COLUMNS} FROM written
       UNION ALL
       SELECT ${COLUMNS}
       FROM resource_daily_progress
       WHERE owner_id = $1 AND resource_id = $2 AND local_date = $3::date
         AND NOT EXISTS (SELECT 1 FROM written)
       LIMIT 1`,
      [ownerId, resourceId, localDate, update.status, [...update.completedActionIds]],
    );
    return result.rows[0] ? item(result.rows[0]) : null;
  }
}
