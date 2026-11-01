import { Inject, Injectable } from '@nestjs/common';

import { DATABASE_SERVICE_TOKEN } from '../application.tokens.js';
import type { DatabaseClient, DatabaseService } from '../database/database.service.js';
import type {
  ResourceBingoBoardState,
  ResourceBingoItemState,
  ResourceBookmark,
  PlanResourceActions,
  ResourceBingoPlanSync,
  ResourceCompletionEvidence,
} from './resource-engagement.types.js';

interface BookmarkRow {
  readonly resource_id: string;
  readonly resource_version: string | number;
  readonly version: string | number;
  readonly created_at: Date;
}

interface BingoBoardRow {
  readonly support_plan_action_id: string | null;
  readonly retired_at: Date | null;
  readonly week_start: string;
  readonly time_zone: string;
  readonly version: string | number;
  readonly updated_at: Date;
  readonly ordinal: number | null;
  readonly resource_id: string | null;
  readonly resource_version: string | number | null;
  readonly label: string | null;
  readonly self_confirmed_at: Date | null;
  readonly verified_at: Date | null;
  readonly verification_source: ResourceBingoItemState['verificationSource'] | null;
}

interface BingoLockRow {
  readonly id: string;
  readonly version: string | number;
}

interface BingoItemLockRow {
  readonly self_confirmed_at: Date | null;
  readonly retired_at: Date | null;
}

const BINGO_BOARD_QUERY = `WITH latest_board AS (
    SELECT * FROM resource_weekly_bingo
    WHERE owner_id = $1 AND week_start = $2::date
    ORDER BY last_synced_at DESC, created_at DESC, id DESC LIMIT 1
  ) SELECT board.week_start::text, board.time_zone, board.version,
    board.updated_at, item.ordinal, item.resource_id, item.resource_version,
    item.support_plan_action_id, item.retired_at,
    publication.snapshot ->> 'title' AS label, item.self_confirmed_at,
    evidence.occurred_at AS verified_at, evidence.source AS verification_source
  FROM latest_board board
  LEFT JOIN resource_weekly_bingo_item item ON item.board_id = board.id
  LEFT JOIN resource_publication_snapshot publication
    ON publication.resource_id = item.resource_id
   AND publication.content_version = item.resource_version
  LEFT JOIN LATERAL (
    SELECT verified.source, verified.occurred_at
    FROM (
      SELECT 'RESOURCE_COMPLETION'::text AS source, progress.completed_at AS occurred_at
      FROM resource_daily_progress progress
      WHERE progress.owner_id = board.owner_id
        AND progress.resource_id = item.resource_id
        AND progress.resource_version = item.resource_version
        AND progress.status = 'COMPLETED'
        AND progress.completed_at IS NOT NULL
        AND (
          (item.support_plan_action_id IS NOT NULL
            AND (progress.completed_at AT TIME ZONE board.time_zone)::date
              BETWEEN board.week_start AND board.week_start + 6)
          OR (item.support_plan_action_id IS NULL
            AND progress.local_date BETWEEN board.week_start AND board.week_start + 6
            AND EXISTS (
              SELECT 1 FROM resource_daily_assignment assignment
              JOIN resource_daily_assignment_item assignment_item ON assignment_item.assignment_id = assignment.id
              WHERE assignment.owner_id = progress.owner_id
                AND assignment.local_date = progress.local_date
                AND assignment.support_plan_id = board.support_plan_id
                AND assignment_item.resource_id = progress.resource_id)))
      UNION ALL
      SELECT 'PRACTICE_SESSION'::text AS source,
        session.completed_at - session.duration_seconds * interval '1 second' AS occurred_at
      FROM resource_practice_session session
      WHERE session.owner_id = board.owner_id
        AND session.resource_id = item.resource_id
        AND session.resource_version = item.resource_version
        AND session.duration_seconds BETWEEN 60 AND 7200
        AND (
          (item.support_plan_action_id IS NOT NULL
            AND ((session.completed_at - session.duration_seconds * interval '1 second') AT TIME ZONE board.time_zone)::date
              BETWEEN board.week_start AND board.week_start + 6)
          OR (item.support_plan_action_id IS NULL
            AND session.support_plan_id = board.support_plan_id
            AND session.local_date BETWEEN board.week_start AND board.week_start + 6))
    ) verified
    ORDER BY verified.occurred_at, verified.source
    LIMIT 1
  ) evidence ON true
  WHERE board.owner_id = $1 AND board.week_start = $2::date
  ORDER BY item.ordinal`;

export class ResourceBingoVersionMismatchError extends Error {
  constructor() {
    super('Resource bingo version mismatch');
    this.name = 'ResourceBingoVersionMismatchError';
  }
}

export class ResourceBingoItemRetiredError extends Error {}

function bookmark(row: BookmarkRow): ResourceBookmark {
  return {
    resourceId: row.resource_id,
    contentVersion: String(row.resource_version),
    savedAt: row.created_at.toISOString(),
    version: String(row.version),
  };
}

function board(rows: readonly BingoBoardRow[]): ResourceBingoBoardState | null {
  const first = rows.at(0);
  if (!first) return null;
  return {
    weekStart: first.week_start,
    timeZone: first.time_zone,
    version: String(first.version),
    updatedAt: first.updated_at.toISOString(),
    items: rows.flatMap((row) => {
      if (row.ordinal === null || row.resource_id === null || row.resource_version === null) {
        return [];
      }
      return [
        {
          actionId: row.support_plan_action_id,
          retired: row.retired_at !== null,
          position: row.ordinal + 1,
          resourceId: row.resource_id,
          contentVersion: String(row.resource_version),
          label: row.label ?? 'Hoạt động hỗ trợ',
          evidence: row.verified_at
            ? 'CONTENT_VERIFIED'
            : row.self_confirmed_at
              ? 'SELF_CONFIRMED'
              : 'NONE',
          selfConfirmedAt: row.self_confirmed_at?.toISOString() ?? null,
          verifiedAt: row.verified_at?.toISOString() ?? null,
          verificationSource: row.verification_source,
        } satisfies ResourceBingoItemState,
      ];
    }),
  };
}

export async function readResourceBingoBoard(
  client: DatabaseClient,
  ownerId: string,
  weekStart: string,
): Promise<ResourceBingoBoardState | null> {
  const result = await client.query<BingoBoardRow>(BINGO_BOARD_QUERY, [ownerId, weekStart]);
  return board(result.rows);
}

@Injectable()
export class ResourceEngagementRepository {
  constructor(
    @Inject(DATABASE_SERVICE_TOKEN)
    private readonly db: DatabaseService,
  ) {}

  async listBookmarks(ownerId: string): Promise<readonly ResourceBookmark[]> {
    const result = await this.db.query<BookmarkRow>(
      `SELECT resource_id, resource_version, version, created_at
       FROM resource_bookmark
       WHERE owner_id = $1
       ORDER BY created_at DESC, resource_id`,
      [ownerId],
    );
    return result.rows.map(bookmark);
  }

  async saveBookmark(ownerId: string, resourceId: string): Promise<ResourceBookmark | null> {
    return this.db.withTransaction(async (client) => {
      const eligible = await client.query<{ readonly version: string | number }>(
        `SELECT resource.version
         FROM resource
         JOIN resource_publication_snapshot publication
           ON publication.resource_id = resource.id
          AND publication.content_version = resource.version
         LEFT JOIN resource_publication_withdrawal withdrawal
           ON withdrawal.resource_id = publication.resource_id
          AND withdrawal.content_version = publication.content_version
         WHERE resource.id = $1
           AND resource.status = 'PUBLISHED'
           AND resource.source_review_status = 'REVIEWED'
           AND withdrawal.resource_id IS NULL`,
        [resourceId],
      );
      const current = eligible.rows.at(0);
      if (!current) return null;
      await client.query(
        `INSERT INTO resource_bookmark (owner_id, resource_id, resource_version)
         VALUES ($1, $2, $3::bigint)
         ON CONFLICT (owner_id, resource_id) DO UPDATE
         SET resource_version = EXCLUDED.resource_version,
             version = resource_bookmark.version + 1,
             updated_at = now()
         WHERE resource_bookmark.resource_version IS DISTINCT FROM EXCLUDED.resource_version`,
        [ownerId, resourceId, String(current.version)],
      );
      const result = await client.query<BookmarkRow>(
        `SELECT resource_id, resource_version, version, created_at
         FROM resource_bookmark
         WHERE owner_id = $1 AND resource_id = $2`,
        [ownerId, resourceId],
      );
      return result.rows[0] ? bookmark(result.rows[0]) : null;
    });
  }

  async deleteBookmark(ownerId: string, resourceId: string): Promise<void> {
    await this.db.query(`DELETE FROM resource_bookmark WHERE owner_id = $1 AND resource_id = $2`, [
      ownerId,
      resourceId,
    ]);
  }

  async readBingo(ownerId: string, weekStart: string): Promise<ResourceBingoBoardState | null> {
    return readResourceBingoBoard(this.db, ownerId, weekStart);
  }

  async syncPlanBingo(
    ownerId: string,
    weekStart: string,
    timeZone: string,
    projection: PlanResourceActions,
  ): Promise<ResourceBingoPlanSync> {
    return this.db.withTransaction(async (client) => {
      await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [
        `${ownerId}:RESOURCE_BINGO:${weekStart}`,
      ]);
      const unavailableActions: ResourceBingoPlanSync['unavailableActions'][number][] = [];
      if (projection.planState === 'NONE') {
        const latest = (
          await client.query<{ id: string }>(
            `SELECT id FROM resource_weekly_bingo WHERE owner_id = $1 AND week_start = $2::date
           ORDER BY last_synced_at DESC, created_at DESC, id DESC LIMIT 1 FOR UPDATE`,
            [ownerId, weekStart],
          )
        ).rows.at(0);
        if (latest) {
          const retired = await client.query(
            `UPDATE resource_weekly_bingo_item SET retired_at = now() WHERE board_id = $1 AND retired_at IS NULL RETURNING ordinal`,
            [latest.id],
          );
          await client.query(
            `UPDATE resource_weekly_bingo SET last_synced_at = clock_timestamp(),
            version = CASE WHEN $2 > 0 THEN (SELECT max(version) + 1 FROM resource_weekly_bingo WHERE owner_id = $3 AND week_start = $4::date) ELSE version END,
            updated_at = CASE WHEN $2 > 0 THEN now() ELSE updated_at END WHERE id = $1`,
            [latest.id, retired.rows.length ? 1 : 0, ownerId, weekStart],
          );
        }
        return {
          ...((await readResourceBingoBoard(client, ownerId, weekStart)) ?? {
            weekStart,
            timeZone,
            version: '0',
            updatedAt: new Date().toISOString(),
            items: [],
          }),
          unavailableActions,
        };
      }
      const { supportPlanId, supportPlanVersion } = projection;
      if (supportPlanId === undefined || supportPlanVersion === undefined) {
        throw new Error('Current plan metadata is required');
      }
      const storedPlan = (
        await client.query<{ support_plan_version: string }>(
          `SELECT support_plan_version FROM resource_weekly_bingo WHERE owner_id = $1 AND week_start = $2::date AND support_plan_id = $3`,
          [ownerId, weekStart, supportPlanId],
        )
      ).rows.at(0);
      if (storedPlan && BigInt(storedPlan.support_plan_version) > BigInt(supportPlanVersion)) {
        const current = await readResourceBingoBoard(client, ownerId, weekStart);
        if (!current) throw new Error('Resource bingo board not found');
        return {
          ...current,
          unavailableActions,
        };
      }
      const currentBoard = (
        await client.query<{ id: string }>(
          `INSERT INTO resource_weekly_bingo (owner_id, week_start, time_zone, support_plan_id, support_plan_version, plan_tags, last_synced_at, version)
         VALUES ($1, $2::date, $3, $4, $5::bigint, '{}'::text[], clock_timestamp(),
           (SELECT coalesce(max(version) + 1, 0) FROM resource_weekly_bingo WHERE owner_id = $1 AND week_start = $2::date))
         ON CONFLICT (owner_id, week_start, support_plan_id) DO UPDATE
         SET support_plan_version = EXCLUDED.support_plan_version, last_synced_at = clock_timestamp()
         RETURNING id`,
          [ownerId, weekStart, timeZone, supportPlanId, supportPlanVersion],
        )
      ).rows.at(0);
      if (!currentBoard) throw new Error('Resource bingo board not found');
      const existing = (
        await client.query<{
          ordinal: number;
          resource_id: string;
          resource_version: string | number;
          support_plan_action_id: string | null;
          self_confirmed_at: Date | null;
          retired_at: Date | null;
        }>(
          `SELECT ordinal, resource_id, resource_version, support_plan_action_id, self_confirmed_at, retired_at FROM resource_weekly_bingo_item WHERE board_id = $1 ORDER BY ordinal`,
          [currentBoard.id],
        )
      ).rows;
      const planResourceIds = new Set(
        projection.actions.map((action) => action.resource.resourceId),
      );
      const removable = existing.filter(
        (item) =>
          item.support_plan_action_id === null &&
          item.self_confirmed_at === null &&
          !planResourceIds.has(item.resource_id),
      );
      for (const item of removable) {
        await client.query(
          `DELETE FROM resource_weekly_bingo_item WHERE board_id = $1 AND ordinal = $2`,
          [currentBoard.id, item.ordinal],
        );
      }
      const retained = existing.filter((item) => !removable.includes(item));
      const byResource = new Map(retained.map((item) => [item.resource_id, item]));
      const desired = new Set<string>();
      const usedOrdinals = new Set(retained.map((item) => item.ordinal));
      let changed = removable.length > 0;
      for (const action of projection.actions) {
        const { resourceId, resourceVersion } = action.resource;
        const published =
          (
            await client.query(
              `SELECT 1 FROM resource_publication_snapshot publication
           LEFT JOIN resource_publication_withdrawal withdrawal ON withdrawal.resource_id = publication.resource_id AND withdrawal.content_version = publication.content_version
           WHERE publication.resource_id = $1 AND publication.content_version = $2::numeric
             AND publication.snapshot->>'catalogue_visibility' = 'LISTED' AND withdrawal.resource_id IS NULL`,
              [resourceId, resourceVersion],
            )
          ).rows.length > 0;
        if (!published) {
          unavailableActions.push({
            actionId: action.actionId,
            reason: 'RESOURCE_VERSION_UNAVAILABLE',
          });
          continue;
        }
        if (desired.has(resourceId)) continue;
        const item = byResource.get(resourceId);
        if (item) {
          const versionChanged = String(item.resource_version) !== resourceVersion;
          if (
            versionChanged ||
            item.support_plan_action_id !== action.actionId ||
            item.retired_at !== null
          )
            changed = true;
          await client.query(
            `UPDATE resource_weekly_bingo_item SET support_plan_action_id = $3, support_plan_action_version = $4::bigint, resource_version = $5::bigint, retired_at = NULL, self_confirmed_at = CASE WHEN resource_version <> $5::bigint THEN NULL ELSE self_confirmed_at END WHERE board_id = $1 AND ordinal = $2`,
            [currentBoard.id, item.ordinal, action.actionId, action.actionVersion, resourceVersion],
          );
        } else {
          const ordinal = Array.from({ length: 9 }, (_, i) => i).find(
            (value) => !usedOrdinals.has(value),
          );
          if (ordinal === undefined) {
            unavailableActions.push({ actionId: action.actionId, reason: 'BOARD_FULL' });
            continue;
          }
          usedOrdinals.add(ordinal);
          await client.query(
            `INSERT INTO resource_weekly_bingo_item (board_id, ordinal, resource_id, resource_version, support_plan_action_id, support_plan_action_version) VALUES ($1, $2, $3, $4::bigint, $5, $6::bigint)`,
            [
              currentBoard.id,
              ordinal,
              resourceId,
              resourceVersion,
              action.actionId,
              action.actionVersion,
            ],
          );
          changed = true;
        }
        desired.add(resourceId);
      }
      for (const item of retained) {
        if (!desired.has(item.resource_id) && item.retired_at === null) {
          await client.query(
            `UPDATE resource_weekly_bingo_item SET retired_at = now() WHERE board_id = $1 AND ordinal = $2`,
            [currentBoard.id, item.ordinal],
          );
          changed = true;
        }
      }
      if (changed)
        await client.query(
          `UPDATE resource_weekly_bingo SET version = (SELECT max(version) + 1 FROM resource_weekly_bingo WHERE owner_id = $2 AND week_start = $3::date), updated_at = now() WHERE id = $1`,
          [currentBoard.id, ownerId, weekStart],
        );
      const current = await readResourceBingoBoard(client, ownerId, weekStart);
      if (!current) throw new Error('Resource bingo board not found');
      return { ...current, unavailableActions };
    });
  }

  async completionEvidence(
    ownerId: string,
    resourceId: string,
    resourceVersion: string,
    localDate: string,
    timeZone: string,
    excludedFactIds: readonly string[] = [],
  ): Promise<ResourceCompletionEvidence | null> {
    const result = await this.db.query<{
      fact_kind: ResourceCompletionEvidence['factKind'];
      fact_id: string;
      resource_version: string | number;
      occurred_at: Date;
      verified_at: Date;
    }>(
      `SELECT facts.*, greatest(now(), occurred_at) AS verified_at FROM (
        SELECT 'RESOURCE_COMPLETION'::text AS fact_kind, resource_id::text || ':' || local_date::text AS fact_id, resource_version, completed_at AS occurred_at, completed_at AS recorded_at
        FROM resource_daily_progress WHERE owner_id = $1 AND resource_id = $2 AND resource_version = $3::numeric AND status = 'COMPLETED' AND completed_at IS NOT NULL
        UNION ALL
        SELECT 'RESOURCE_PRACTICE_SESSION'::text AS fact_kind, id::text AS fact_id, resource_version, completed_at - duration_seconds * interval '1 second' AS occurred_at, completed_at AS recorded_at
        FROM resource_practice_session WHERE owner_id = $1 AND resource_id = $2 AND resource_version = $3::numeric AND duration_seconds BETWEEN 60 AND 7200
      ) facts WHERE (occurred_at AT TIME ZONE $5)::date = $4::date
        AND NOT (fact_id = ANY($6::text[]))
        AND EXISTS (SELECT 1 FROM resource_publication_snapshot publication WHERE publication.resource_id = $2 AND publication.content_version = facts.resource_version)
        ORDER BY recorded_at DESC, fact_kind, fact_id LIMIT 1`,
      [ownerId, resourceId, resourceVersion, localDate, timeZone, [...excludedFactIds]],
    );
    const fact = result.rows.at(0);
    return fact
      ? {
          factKind: fact.fact_kind,
          factId: fact.fact_id,
          resourceVersion: String(fact.resource_version),
          occurredAt: fact.occurred_at.toISOString(),
          verifiedAt: fact.verified_at.toISOString(),
        }
      : null;
  }

  async setBingoSelfConfirmed(
    ownerId: string,
    weekStart: string,
    position: number,
    expectedVersion: string,
    selfConfirmed: boolean,
  ): Promise<ResourceBingoBoardState | null> {
    return this.db.withTransaction(async (client) => {
      await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [
        `${ownerId}:RESOURCE_BINGO:${weekStart}`,
      ]);
      const boardLock = await client.query<BingoLockRow>(
        `SELECT id, version
         FROM resource_weekly_bingo
         WHERE owner_id = $1 AND week_start = $2::date
         ORDER BY last_synced_at DESC, created_at DESC, id DESC LIMIT 1 FOR UPDATE`,
        [ownerId, weekStart],
      );
      const currentBoard = boardLock.rows.at(0);
      if (!currentBoard) return null;
      const item = await client.query<BingoItemLockRow>(
        `SELECT self_confirmed_at, retired_at
         FROM resource_weekly_bingo_item
         WHERE board_id = $1 AND ordinal = $2`,
        [currentBoard.id, position - 1],
      );
      const currentItem = item.rows.at(0);
      if (!currentItem) return null;
      if (currentItem.retired_at !== null) throw new ResourceBingoItemRetiredError();
      const alreadyDesired = (currentItem.self_confirmed_at !== null) === selfConfirmed;
      if (!alreadyDesired && String(currentBoard.version) !== expectedVersion) {
        throw new ResourceBingoVersionMismatchError();
      }
      if (!alreadyDesired) {
        await client.query(
          `UPDATE resource_weekly_bingo_item
           SET self_confirmed_at = CASE WHEN $3 THEN now() ELSE NULL END
           WHERE board_id = $1 AND ordinal = $2`,
          [currentBoard.id, position - 1, selfConfirmed],
        );
        await client.query(
          `UPDATE resource_weekly_bingo
           SET version = (SELECT max(version) + 1 FROM resource_weekly_bingo WHERE owner_id = $2 AND week_start = $3::date), updated_at = now()
           WHERE id = $1`,
          [currentBoard.id, ownerId, weekStart],
        );
      }
      return readResourceBingoBoard(client, ownerId, weekStart);
    });
  }
}
