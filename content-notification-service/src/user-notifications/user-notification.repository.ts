import { Inject, Injectable } from '@nestjs/common';

import { DATABASE_SERVICE_TOKEN } from '../application.tokens.js';
import type { DatabaseService } from '../database/database.service.js';
import type { UserNotificationProjection } from './user-notification.types.js';

type NotificationRow = Readonly<{
  id: string;
  request_fingerprint: string;
  delivery_state: 'DELIVERED' | 'CANCELLED';
}>;

export class UserNotificationDedupeConflictError extends Error {
  constructor() {
    super('User notification fact identity was reused with different content');
    this.name = 'UserNotificationDedupeConflictError';
  }
}

@Injectable()
export class UserNotificationRepository {
  constructor(
    @Inject(DATABASE_SERVICE_TOKEN)
    private readonly db: DatabaseService,
  ) {}

  async materialize(projection: UserNotificationProjection, fingerprint: string): Promise<void> {
    await this.db.withTransaction(async (client) => {
      await client.query(
        `INSERT INTO notification_preference (user_id, owner_type)
         VALUES ($1, 'USER') ON CONFLICT (user_id) DO NOTHING`,
        [projection.ownerId],
      );
      const preference = await client.query<{
        owner_type: string;
        notifications_enabled: boolean;
        channel_in_app_enabled: boolean;
        group_screening_reassessment_enabled: boolean;
        group_resource_system_enabled: boolean;
      }>(
        `SELECT owner_type, notifications_enabled, channel_in_app_enabled,
                group_screening_reassessment_enabled, group_resource_system_enabled
         FROM notification_preference WHERE user_id = $1 FOR UPDATE`,
        [projection.ownerId],
      );
      const row = preference.rows[0];
      const groupEnabled =
        projection.preferenceGroup === 'SCREENING_REASSESSMENT'
          ? row.group_screening_reassessment_enabled
          : row.group_resource_system_enabled;
      const delivered =
        row.owner_type === 'USER' &&
        row.notifications_enabled &&
        row.channel_in_app_enabled &&
        groupEnabled;
      const inserted = await client.query<NotificationRow>(
        `INSERT INTO notification
           (recipient_id, category, title, body, action_type, action_target_id, priority,
            expires_at, occurred_at, source, source_identity, request_fingerprint,
            delivery_state)
         VALUES ($1,$2,$3,$4,$5,$6,'NORMAL',now() + interval '90 days',$7,$8,$9,$10,$11)
         ON CONFLICT (recipient_id, source, source_identity) DO NOTHING
         RETURNING id, request_fingerprint, delivery_state`,
        [
          projection.ownerId,
          projection.kind,
          projection.title,
          projection.body,
          projection.actionType,
          projection.actionTargetId,
          projection.occurredAt,
          projection.source,
          projection.sourceIdentity,
          fingerprint,
          delivered ? 'DELIVERED' : 'CANCELLED',
        ],
      );
      if (inserted.rows[0]) return;

      const existing = await client.query<NotificationRow>(
        `SELECT id, request_fingerprint, delivery_state FROM notification
         WHERE recipient_id = $1 AND source = $2 AND source_identity = $3`,
        [projection.ownerId, projection.source, projection.sourceIdentity],
      );
      if (!existing.rows[0] || existing.rows[0].request_fingerprint !== fingerprint) {
        throw new UserNotificationDedupeConflictError();
      }
    });
  }
}
