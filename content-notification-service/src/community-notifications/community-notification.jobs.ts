import { Inject, Injectable } from '@nestjs/common';
import { randomUUID } from 'node:crypto';
import { DATABASE_SERVICE_TOKEN } from '../application.tokens.js';
import type { DatabaseService } from '../database/database.service.js';
import type { CommunityNotificationProjection } from './community-interaction.types.js';

export interface CommunityNoticeJob {
  id: string;
  claim_token: string;
  attempts: number;
  projection: CommunityNotificationProjection;
  request_fingerprint: string;
  expires_at: Date | string;
}

export class CommunityNoticeIdentityConflict extends Error {
  constructor() {
    super('Community notice identity conflict');
  }
}

@Injectable()
export class CommunityNotificationJobs {
  constructor(@Inject(DATABASE_SERVICE_TOKEN) private readonly db: DatabaseService) {}

  async enqueue(projection: CommunityNotificationProjection, fingerprint: string): Promise<void> {
    const occurred = Date.parse(projection.occurredAt);
    if (!Number.isFinite(occurred) || occurred > Date.now() + 300_000)
      throw new CommunityNoticeIdentityConflict();
    await this.db.withTransaction(async (client) => {
      await client.query(
        'INSERT INTO notification_preference(user_id) VALUES($1) ON CONFLICT DO NOTHING',
        [projection.ownerId],
      );
      const preference = await client.query<{ enabled: boolean }>(
        `SELECT owner_type = 'USER' AND notifications_enabled AND channel_in_app_enabled
          AND group_community_interaction_enabled AS enabled FROM notification_preference WHERE user_id = $1 FOR UPDATE`,
        [projection.ownerId],
      );
      const inserted = await client.query<{ request_fingerprint: string }>(
        `INSERT INTO community_notification_job(recipient_id,source,source_identity,request_fingerprint,projection,state,available_at,expires_at)
         VALUES($1,$2,$3,$4,$5::jsonb,$6,now(),$7) ON CONFLICT DO NOTHING RETURNING request_fingerprint`,
        [
          projection.ownerId,
          projection.source ?? 'COMMUNITY_INTERACTION_V1',
          projection.sourceIdentity ?? projection.eventId,
          fingerprint,
          JSON.stringify(projection),
          preference.rows[0]?.enabled && occurred + 90 * 86_400_000 > Date.now()
            ? 'PENDING'
            : 'CANCELLED',
          new Date(occurred + 90 * 86_400_000),
        ],
      );
      if (inserted.rows[0]) return;
      const existing = await client.query<{ request_fingerprint: string }>(
        'SELECT request_fingerprint FROM community_notification_job WHERE recipient_id=$1 AND source=$2 AND source_identity=$3',
        [
          projection.ownerId,
          projection.source ?? 'COMMUNITY_INTERACTION_V1',
          projection.sourceIdentity ?? projection.eventId,
        ],
      );
      if (existing.rows[0]?.request_fingerprint !== fingerprint)
        throw new CommunityNoticeIdentityConflict();
    });
  }

  async claim(now: Date): Promise<CommunityNoticeJob | null> {
    const result = await this.db.query<CommunityNoticeJob>(
      `UPDATE community_notification_job SET claim_token=$2,claim_until=$1::timestamptz + interval '30 seconds',attempts=attempts+1
       WHERE id=(SELECT id FROM community_notification_job WHERE state='PENDING' AND available_at <= $1
         AND (claim_until IS NULL OR claim_until <= $1) ORDER BY available_at,id FOR UPDATE SKIP LOCKED LIMIT 1)
       RETURNING id,claim_token,attempts,projection,request_fingerprint,expires_at`,
      [now, randomUUID()],
    );
    return result.rows[0] ?? null;
  }

  async release(job: CommunityNoticeJob, at: Date): Promise<void> {
    await this.db.query(
      `UPDATE community_notification_job SET claim_token=NULL,claim_until=NULL,available_at=$3
      WHERE id=$1 AND claim_token=$2 AND state='PENDING'`,
      [job.id, job.claim_token, at],
    );
  }

  async cancel(job: CommunityNoticeJob): Promise<void> {
    await this.db.query(
      `UPDATE community_notification_job SET state='CANCELLED',claim_token=NULL,claim_until=NULL
      WHERE id=$1 AND claim_token=$2 AND state='PENDING'`,
      [job.id, job.claim_token],
    );
  }

  async cleanup(now: Date): Promise<void> {
    await this.db.query(
      `DELETE FROM community_notification_job WHERE id IN
      (SELECT id FROM community_notification_job WHERE expires_at <= $1
       AND (claim_until IS NULL OR claim_until <= $1) ORDER BY expires_at,id LIMIT 100)`,
      [now],
    );
  }
}
