import { Inject, Injectable } from '@nestjs/common';
import { DATABASE_SERVICE_TOKEN } from '../application.tokens.js';
import type { DatabaseService } from '../database/database.service.js';
import type { DeliveryClaim, WellbeingEmailKind } from './wellbeing-digest.types.js';

interface ClaimRow {
  readonly id: string;
  readonly attempt_count: number | string;
}
interface ResourceRow {
  readonly id: string;
  readonly title: string;
}

@Injectable()
export class WellbeingDigestRepository {
  constructor(@Inject(DATABASE_SERVICE_TOKEN) private readonly db: DatabaseService) {}

  async pendingResources(ownerId: string, localDate: string): Promise<readonly ResourceRow[]> {
    const result = await this.db.query<ResourceRow>(
      `SELECT resource.id, resource.title
       FROM resource_daily_assignment assignment
       JOIN resource_daily_assignment_item item ON item.assignment_id = assignment.id
       JOIN resource ON resource.id = item.resource_id
       LEFT JOIN resource_daily_progress progress
         ON progress.owner_id = assignment.owner_id
        AND progress.local_date = assignment.local_date
        AND progress.resource_id = item.resource_id
       WHERE assignment.owner_id = $1 AND assignment.local_date = $2::date
         AND COALESCE(progress.status, 'IN_PROGRESS') <> 'COMPLETED'
         AND resource.status = 'PUBLISHED' AND resource.source_review_status = 'REVIEWED'
       ORDER BY item.ordinal`,
      [ownerId, localDate],
    );
    return result.rows;
  }

  async claim(
    ownerId: string,
    localDate: string,
    timeZone: string,
    kind: WellbeingEmailKind,
    counts: Readonly<Record<string, number>>,
  ): Promise<DeliveryClaim | null> {
    return this.db.withTransaction(async (client) => {
      await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [
        `${ownerId}:WELLBEING_EMAIL:${localDate}:${kind}`,
      ]);
      const inserted = await client.query(
        `INSERT INTO wellbeing_email_delivery
           (owner_id, local_date, delivery_kind, time_zone, content_counts)
         VALUES ($1, $2::date, $3, $4, $5::jsonb)
         ON CONFLICT (owner_id, local_date, delivery_kind) DO NOTHING`,
        [ownerId, localDate, kind, timeZone, JSON.stringify(counts)],
      );
      const current = await client.query<ClaimRow & { state: string; claimed_at: Date | string }>(
        `SELECT id, state, attempt_count, claimed_at
         FROM wellbeing_email_delivery
         WHERE owner_id = $1 AND local_date = $2::date AND delivery_kind = $3`,
        [ownerId, localDate, kind],
      );
      const row = current.rows[0];
      if (row.state === 'DELIVERED' || row.state === 'CANCELLED') return null;
      const insertedNow = (inserted.rowCount ?? 0) === 1;
      const freshClaim =
        row.state === 'CLAIMED' && Date.now() - new Date(row.claimed_at).getTime() < 5 * 60_000;
      if (!insertedNow && freshClaim) return null;
      if (!insertedNow) {
        if (Number(row.attempt_count) >= 10) return null;
        await client.query(
          `UPDATE wellbeing_email_delivery
           SET state = 'CLAIMED', attempt_count = attempt_count + 1, claimed_at = now(),
               failure_code = NULL, content_counts = $2::jsonb, updated_at = now()
           WHERE id = $1`,
          [row.id, JSON.stringify(counts)],
        );
      }
      return { id: row.id, kind, localDate };
    });
  }

  async delivered(id: string, providerMessageId: string | null): Promise<void> {
    await this.db.query(
      `UPDATE wellbeing_email_delivery
       SET state = 'DELIVERED', provider_message_id = $2, attempted_at = now(),
           delivered_at = now(), updated_at = now()
       WHERE id = $1 AND state = 'CLAIMED'`,
      [id, providerMessageId],
    );
  }

  async failed(id: string, code: string): Promise<void> {
    await this.db.query(
      `UPDATE wellbeing_email_delivery
       SET state = 'FAILED', failure_code = $2, attempted_at = now(), updated_at = now()
       WHERE id = $1 AND state = 'CLAIMED'`,
      [id, code],
    );
  }

  async cancelled(id: string, code: string): Promise<void> {
    await this.db.query(
      `UPDATE wellbeing_email_delivery
       SET state = 'CANCELLED', failure_code = $2, attempted_at = now(), updated_at = now()
       WHERE id = $1 AND state = 'CLAIMED'`,
      [id, code],
    );
  }
}
