import { ConflictException, Inject, Injectable } from '@nestjs/common';

import { DATABASE_SERVICE_TOKEN } from '../application.tokens.js';
import type { DatabaseService } from '../database/database.service.js';
import type {
  ResourceProgressItem,
  ResourceProgressRow,
  ResourceProgressUpdate,
} from './resource-progress.types.js';

interface ProgressResourceRow {
  readonly id: string;
  readonly version: string | number;
  readonly repeatability: 'ONE_TIME' | 'REPEATABLE';
  readonly resource_kind: 'LEARNING' | 'PRACTICE' | 'HABIT' | 'ACTION' | 'REFLECTION';
}

interface AssignedPlanRow {
  readonly support_plan_id: string;
}

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
    return this.db.withTransaction(async (client) => {
      await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [
        `${ownerId}:RESOURCE_PROGRESS:${resourceId}:${localDate}`,
      ]);
      if (update.resourceVersion !== undefined) {
        const completed = (
          await client.query<{ resource_version: string | number }>(
            `SELECT resource_version FROM resource_daily_progress
           WHERE owner_id = $1 AND resource_id = $2 AND local_date = $3::date AND status = 'COMPLETED'`,
            [ownerId, resourceId, localDate],
          )
        ).rows.at(0);
        if (completed && String(completed.resource_version) !== update.resourceVersion) {
          throw new ConflictException({
            type: 'https://mentalbridge.io/errors/RESOURCE_VERSION_UNAVAILABLE',
            title: 'Completed progress belongs to another resource version',
            detail:
              'This resource already has completed progress for a different version on this local date.',
            status: 409,
            code: 'RESOURCE_VERSION_UNAVAILABLE',
          });
        }
      }
      const eligible = await client.query<ProgressResourceRow>(
        update.resourceVersion === undefined
          ? `SELECT id, version, repeatability, resource_kind
         FROM resource WHERE id = $1 AND status = 'PUBLISHED'
           AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL
           AND source_review_status = 'REVIEWED'
           AND (effective_at IS NULL OR effective_at <= now())
           AND (expires_at IS NULL OR expires_at > now())`
          : `SELECT publication.resource_id AS id, publication.content_version AS version,
             publication.snapshot->>'repeatability' AS repeatability,
             publication.snapshot->>'resource_kind' AS resource_kind
           FROM resource_publication_snapshot publication
           LEFT JOIN resource_publication_withdrawal withdrawal ON withdrawal.resource_id = publication.resource_id AND withdrawal.content_version = publication.content_version
           WHERE publication.resource_id = $1 AND publication.content_version = $2::numeric
             AND publication.snapshot->>'catalogue_visibility' = 'LISTED' AND withdrawal.resource_id IS NULL`,
        update.resourceVersion === undefined ? [resourceId] : [resourceId, update.resourceVersion],
      );
      const resource = eligible.rows.at(0);
      if (!resource) {
        if (update.resourceVersion !== undefined) {
          throw new ConflictException({
            type: 'https://mentalbridge.io/errors/RESOURCE_VERSION_UNAVAILABLE',
            title: 'Resource version is unavailable',
            status: 409,
            code: 'RESOURCE_VERSION_UNAVAILABLE',
          });
        }
        return null;
      }
      const assignedPlan = (
        await client.query<AssignedPlanRow>(
          `SELECT assignment.support_plan_id
           FROM resource_daily_assignment assignment
           JOIN resource_daily_assignment_item item ON item.assignment_id = assignment.id
           WHERE assignment.owner_id = $1
             AND assignment.local_date = $2::date
             AND item.resource_id = $3`,
          [ownerId, localDate, resourceId],
        )
      ).rows.at(0);

      const result = await client.query<ResourceProgressRow>(
        `WITH written AS (
         INSERT INTO resource_daily_progress
           (owner_id, resource_id, local_date, resource_version, status,
            completed_action_ids, completed_at)
         VALUES ($1, $2, $3::date, $4, $5::varchar(16), $6::text[],
           CASE WHEN $5::varchar(16) = 'COMPLETED' THEN now() ELSE NULL END)
         ON CONFLICT (owner_id, resource_id, local_date) DO UPDATE
         SET resource_version = CASE
               WHEN resource_daily_progress.status = 'COMPLETED'
                 THEN resource_daily_progress.resource_version
               ELSE EXCLUDED.resource_version
             END,
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
            OR (
              resource_daily_progress.status <> 'COMPLETED'
              AND resource_daily_progress.resource_version IS DISTINCT FROM EXCLUDED.resource_version
            )
         RETURNING ${COLUMNS}
       )
       SELECT ${COLUMNS} FROM written
       UNION ALL
       SELECT ${COLUMNS}
       FROM resource_daily_progress
       WHERE owner_id = $1 AND resource_id = $2 AND local_date = $3::date
         AND NOT EXISTS (SELECT 1 FROM written)
       LIMIT 1`,
        [
          ownerId,
          resourceId,
          localDate,
          String(resource.version),
          update.status,
          [...update.completedActionIds],
        ],
      );

      if (update.status === 'COMPLETED') {
        if (resource.repeatability === 'ONE_TIME' || resource.resource_kind === 'LEARNING') {
          await client.query(
            `INSERT INTO resource_learning_completion
               (owner_id, resource_id, resource_version, support_plan_id, local_date)
             VALUES ($1, $2, $3, $4, $5::date)
             ON CONFLICT (owner_id, resource_id) DO NOTHING`,
            [
              ownerId,
              resourceId,
              String(result.rows[0]?.resource_version ?? resource.version),
              assignedPlan?.support_plan_id ?? null,
              localDate,
            ],
          );
        } else if (update.practiceSessionId) {
          await client.query(
            `INSERT INTO resource_practice_session
               (id, owner_id, resource_id, resource_version, support_plan_id, local_date,
                started_at, duration_seconds)
             VALUES ($1, $2, $3, $4, $5, $6::date, CASE WHEN $7::integer IS NOT NULL THEN now() - $7::integer * interval '1 second' ELSE NULL END, $7::integer)
             ON CONFLICT (id) DO NOTHING`,
            [
              update.practiceSessionId,
              ownerId,
              resourceId,
              String(resource.version),
              assignedPlan?.support_plan_id ?? null,
              localDate,
              update.practiceDurationSeconds ?? null,
            ],
          );
        }
      }

      return result.rows[0] ? item(result.rows[0]) : null;
    });
  }
}
