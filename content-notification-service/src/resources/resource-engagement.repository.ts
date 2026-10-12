import { Inject, Injectable } from '@nestjs/common';

import { DATABASE_SERVICE_TOKEN } from '../application.tokens.js';
import type { DatabaseClient, DatabaseService } from '../database/database.service.js';
import type {
  ResourceBingoBoardState,
  ResourceBingoItemState,
  ResourceBookmark,
} from './resource-engagement.types.js';

interface BookmarkRow {
  readonly resource_id: string;
  readonly resource_version: string | number;
  readonly version: string | number;
  readonly created_at: Date;
}

interface BingoBoardRow {
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
}

const BINGO_BOARD_QUERY = `SELECT board.week_start::text, board.time_zone, board.version,
    board.updated_at, item.ordinal, item.resource_id, item.resource_version,
    publication.snapshot ->> 'title' AS label, item.self_confirmed_at,
    evidence.occurred_at AS verified_at, evidence.source AS verification_source
  FROM resource_weekly_bingo board
  LEFT JOIN resource_weekly_bingo_item item ON item.board_id = board.id
  LEFT JOIN resource_publication_snapshot publication
    ON publication.resource_id = item.resource_id
   AND publication.content_version = item.resource_version
  LEFT JOIN LATERAL (
    SELECT verified.source, verified.occurred_at
    FROM (
      SELECT 'RESOURCE_COMPLETION'::text AS source, progress.completed_at AS occurred_at
      FROM resource_daily_progress progress
      JOIN resource_daily_assignment assignment
        ON assignment.owner_id = progress.owner_id
       AND assignment.local_date = progress.local_date
       AND assignment.support_plan_id = board.support_plan_id
      JOIN resource_daily_assignment_item assignment_item
        ON assignment_item.assignment_id = assignment.id
       AND assignment_item.resource_id = progress.resource_id
      WHERE progress.owner_id = board.owner_id
        AND progress.resource_id = item.resource_id
        AND progress.resource_version = item.resource_version
        AND progress.status = 'COMPLETED'
        AND progress.completed_at IS NOT NULL
        AND progress.local_date BETWEEN board.week_start AND board.week_start + 6
      UNION ALL
      SELECT 'PRACTICE_SESSION'::text AS source, session.completed_at AS occurred_at
      FROM resource_practice_session session
      WHERE session.owner_id = board.owner_id
        AND session.resource_id = item.resource_id
        AND session.resource_version = item.resource_version
        AND session.support_plan_id = board.support_plan_id
        AND session.started_at IS NOT NULL
        AND session.duration_seconds BETWEEN 60 AND 7200
        AND session.local_date BETWEEN board.week_start AND board.week_start + 6
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

async function readBoard(
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
    return readBoard(this.db, ownerId, weekStart);
  }

  async setBingoSelfConfirmed(
    ownerId: string,
    weekStart: string,
    position: number,
    expectedVersion: string,
    selfConfirmed: boolean,
  ): Promise<ResourceBingoBoardState | null> {
    return this.db.withTransaction(async (client) => {
      const boardLock = await client.query<BingoLockRow>(
        `SELECT id, version
         FROM resource_weekly_bingo
         WHERE owner_id = $1 AND week_start = $2::date
         FOR UPDATE`,
        [ownerId, weekStart],
      );
      const currentBoard = boardLock.rows.at(0);
      if (!currentBoard) return null;
      const item = await client.query<BingoItemLockRow>(
        `SELECT self_confirmed_at
         FROM resource_weekly_bingo_item
         WHERE board_id = $1 AND ordinal = $2`,
        [currentBoard.id, position - 1],
      );
      const currentItem = item.rows.at(0);
      if (!currentItem) return null;
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
           SET version = version + 1, updated_at = now()
           WHERE id = $1`,
          [currentBoard.id],
        );
      }
      return readBoard(client, ownerId, weekStart);
    });
  }
}
