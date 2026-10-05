import { Inject, Injectable } from '@nestjs/common';

import { DATABASE_SERVICE_TOKEN } from '../application.tokens.js';
import type { DatabaseService } from '../database/database.service.js';
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
  ): Promise<CommunityNotificationMaterialization> {
    return this.db.withTransaction(async (client) => {
      await client.query(
        `INSERT INTO notification_preference (user_id)
         VALUES ($1)
         ON CONFLICT (user_id) DO NOTHING`,
        [projection.ownerId],
      );
      const preference = await client.query<{
        notifications_enabled: boolean;
        channel_in_app_enabled: boolean;
        group_community_interaction_enabled: boolean;
      }>(
        `SELECT notifications_enabled, channel_in_app_enabled,
                group_community_interaction_enabled
         FROM notification_preference
         WHERE user_id = $1
         FOR UPDATE`,
        [projection.ownerId],
      );
      const row = preference.rows[0];
      const delivered =
        row.notifications_enabled &&
        row.channel_in_app_enabled &&
        row.group_community_interaction_enabled;
      const inserted = await client.query<ExistingNotificationRow>(
        `INSERT INTO notification
           (recipient_id, category, title, body, action_type, action_target_id, priority,
            expires_at, occurred_at, source, source_identity, request_fingerprint,
            delivery_state)
         VALUES ($1,$2,$3,$4,'OPEN_COMMUNITY_POST',$5,'NORMAL',
           now() + interval '90 days',$6,'COMMUNITY_INTERACTION_V1',$7,$8,$9)
         ON CONFLICT (recipient_id, source, source_identity) DO NOTHING
         RETURNING id, request_fingerprint, delivery_state`,
        [
          projection.ownerId,
          projection.kind,
          projection.title,
          projection.body,
          projection.postId,
          projection.occurredAt,
          projection.eventId,
          fingerprint,
          delivered ? 'DELIVERED' : 'CANCELLED',
        ],
      );
      if (inserted.rows[0]) {
        return {
          notificationId: inserted.rows[0].id,
          delivered,
          replayed: false,
        };
      }

      const existing = await client.query<ExistingNotificationRow>(
        `SELECT id, request_fingerprint, delivery_state
         FROM notification
         WHERE recipient_id = $1
           AND source = 'COMMUNITY_INTERACTION_V1'
           AND source_identity = $2`,
        [projection.ownerId, projection.eventId],
      );
      if (!existing.rows[0] || existing.rows[0].request_fingerprint !== fingerprint) {
        throw new CommunityNotificationDedupeConflictError();
      }
      return {
        notificationId: existing.rows[0].id,
        delivered: existing.rows[0].delivery_state === 'DELIVERED',
        replayed: true,
      };
    });
  }
}
