import { Inject, Injectable } from '@nestjs/common';

import { DATABASE_SERVICE_TOKEN } from '../application.tokens.js';
import type { DatabaseService } from '../database/database.service.js';
import { isQuietHour } from '../reminders/reminder-materialization.service.js';
import type { CommunityNoticeJob } from './community-notification.jobs.js';
import type {
  CommunityNotificationMaterialization,
  CommunityNotificationProjection,
} from './community-interaction.types.js';

type ExistingNotificationRow = Readonly<{
  id: string;
  request_fingerprint: string;
  delivery_state: 'DELIVERED' | 'CANCELLED';
}>;

export class CommunityNotificationDedupeConflictError extends Error {
  constructor() {
    super('Community interaction event identity was reused with different content');
    this.name = 'CommunityNotificationDedupeConflictError';
  }
}

@Injectable()
export class CommunityNotificationRepository {
  constructor(
    @Inject(DATABASE_SERVICE_TOKEN)
    private readonly db: DatabaseService,
  ) {}

  async materialize(
    projection: CommunityNotificationProjection,
    fingerprint: string,
    job?: CommunityNoticeJob,
    now = new Date(),
  ): Promise<CommunityNotificationMaterialization> {
    return this.db.withTransaction(async (client) => {
      await client.query(
        `INSERT INTO notification_preference (user_id)
         VALUES ($1)
         ON CONFLICT (user_id) DO NOTHING`,
        [projection.ownerId],
      );
      const preference = await client.query<{
        enabled: boolean;
        quiet_hours_enabled: boolean;
        quiet_hours_start: string;
        quiet_hours_end: string;
        time_zone: string;
      }>(
        `SELECT owner_type = 'USER' AND notifications_enabled AND channel_in_app_enabled
                AND group_community_interaction_enabled AS enabled,
                quiet_hours_enabled, quiet_hours_start::text, quiet_hours_end::text, time_zone
         FROM notification_preference
         WHERE user_id = $1
         FOR UPDATE`,
        [projection.ownerId],
      );
      if (job) {
        const claim = await client.query<{
          state: string;
          notification_id: string | null;
          current: boolean;
        }>(
          `SELECT state,notification_id,claim_token=$2 AND claim_until > $3 AS current
           FROM community_notification_job WHERE id=$1 FOR UPDATE`,
          [job.id, job.claim_token, now],
        );
        const row = claim.rows.at(0);
        if (!row || row.state !== 'PENDING' || !row.current)
          return {
            notificationId: row?.notification_id ?? null,
            delivered: row?.state === 'DELIVERED',
            replayed: true,
          };
      }
      const finish = async (
        result: CommunityNotificationMaterialization,
      ): Promise<CommunityNotificationMaterialization> => {
        if (job)
          await client.query(
            `UPDATE community_notification_job SET state=$3,notification_id=$4,claim_token=NULL,claim_until=NULL
          WHERE id=$1 AND claim_token=$2`,
            [
              job.id,
              job.claim_token,
              result.delivered ? 'DELIVERED' : 'CANCELLED',
              result.notificationId,
            ],
          );
        return result;
      };
      const source = projection.source ?? 'COMMUNITY_INTERACTION_V1';
      const identity = projection.sourceIdentity ?? projection.eventId;
      const previous = await client.query<ExistingNotificationRow>(
        'SELECT id,request_fingerprint,delivery_state FROM notification WHERE recipient_id=$1 AND source=$2 AND source_identity=$3',
        [projection.ownerId, source, identity],
      );
      if (previous.rows[0]) {
        if (previous.rows[0].request_fingerprint !== fingerprint)
          throw new CommunityNotificationDedupeConflictError();
        return finish({
          notificationId: previous.rows[0].id,
          delivered: previous.rows[0].delivery_state === 'DELIVERED',
          replayed: true,
        });
      }
      const row = preference.rows.at(0);
      if (!row) throw new Error('Community notification preference is unavailable');
      const delivered = row.enabled;
      if (
        delivered &&
        isQuietHour(
          {
            quietHours: {
              enabled: row.quiet_hours_enabled,
              start: row.quiet_hours_start,
              end: row.quiet_hours_end,
              timeZone: row.time_zone,
            },
          },
          now,
        )
      )
        return {
          notificationId: null,
          delivered: false,
          replayed: false,
          deferredUntil: new Date(now.getTime() + 60_000),
        };
      if (delivered && projection.groupingKey) {
        const group = await client.query<{ id: string }>(
          `SELECT id FROM notification
          WHERE recipient_id=$1 AND grouping_key=$2 AND read_at IS NULL AND deleted_at IS NULL
            AND delivery_state='DELIVERED' AND expires_at > $3 ORDER BY created_at DESC,id DESC LIMIT 1 FOR UPDATE`,
          [projection.ownerId, projection.groupingKey, now],
        );
        if (group.rows[0])
          return finish({ notificationId: group.rows[0].id, delivered: true, replayed: false });
      }
      const inserted = await client.query<ExistingNotificationRow>(
        `INSERT INTO notification
           (recipient_id, category, title, body, action_type, action_target_id, priority,
            expires_at, occurred_at, source, source_identity, request_fingerprint,
            delivery_state, grouping_key)
         VALUES ($1,$2,$3,$4,$10,$5,'NORMAL',
           now() + interval '90 days',$6,$11,$7,$8,$9,$12)
         ON CONFLICT (recipient_id, source, source_identity) DO NOTHING
         RETURNING id, request_fingerprint, delivery_state`,
        [
          projection.ownerId,
          projection.kind,
          projection.title,
          projection.body,
          projection.actionTargetId ?? projection.postId ?? null,
          projection.occurredAt,
          identity,
          fingerprint,
          delivered ? 'DELIVERED' : 'CANCELLED',
          projection.actionType ?? 'OPEN_COMMUNITY_POST',
          source,
          projection.groupingKey ?? null,
        ],
      );
      if (inserted.rows[0]) {
        return finish({
          notificationId: inserted.rows[0].id,
          delivered,
          replayed: false,
        });
      }

      const existing = await client.query<ExistingNotificationRow>(
        `SELECT id, request_fingerprint, delivery_state
         FROM notification
         WHERE recipient_id = $1
           AND source = $3
           AND source_identity = $2`,
        [projection.ownerId, identity, source],
      );
      if (!existing.rows[0] || existing.rows[0].request_fingerprint !== fingerprint) {
        throw new CommunityNotificationDedupeConflictError();
      }
      return finish({
        notificationId: existing.rows[0].id,
        delivered: existing.rows[0].delivery_state === 'DELIVERED',
        replayed: true,
      });
    });
  }
}
