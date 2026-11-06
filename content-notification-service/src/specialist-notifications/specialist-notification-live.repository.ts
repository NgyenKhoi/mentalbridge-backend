import { Inject, Injectable } from '@nestjs/common';
import { randomUUID } from 'node:crypto';

import { DATABASE_SERVICE_TOKEN } from '../application.tokens.js';
import type { DatabaseService } from '../database/database.service.js';
import type { SpecialistNotificationLiveClaim } from './specialist-notification-live.types.js';

const ELIGIBILITY = `n.category='APPOINTMENT' AND n.source='CONSULTATION_APPOINTMENT_V1'
  AND n.delivery_state='DELIVERED' AND n.deleted_at IS NULL AND n.expires_at > clock_timestamp()
  AND p.owner_type='SPECIALIST' AND p.notifications_enabled AND p.channel_in_app_enabled
  AND p.group_appointment_message_enabled`;

@Injectable()
export class SpecialistNotificationLiveRepository {
  constructor(@Inject(DATABASE_SERVICE_TOKEN) private readonly db: DatabaseService) {}

  async eligible(notificationId: string, accountId: string): Promise<string | null> {
    const result = await this.db.query<{ id: string }>(
      `SELECT n.id FROM notification n
       JOIN notification_preference p ON p.user_id=n.recipient_id
       WHERE n.id=$1 AND n.recipient_id=$2 AND ${ELIGIBILITY}`,
      [notificationId, accountId],
    );
    return result.rows[0]?.id ?? null;
  }

  async claim(): Promise<SpecialistNotificationLiveClaim | null> {
    return this.db.withTransaction(async (client) => {
      await client.query(
        `UPDATE specialist_notification_live_outbox
         SET state='FAILED', next_attempt_at=NULL, claim_token=NULL, claim_until=NULL,
             last_error_code='CLAIM_EXHAUSTED'
         WHERE state='PENDING' AND attempt_count >= 5
           AND (claim_until IS NULL OR claim_until <= clock_timestamp())`,
      );
      const result = await client.query<SpecialistNotificationLiveClaim>(
        `WITH candidate AS (
           SELECT id FROM specialist_notification_live_outbox
           WHERE state='PENDING' AND next_attempt_at <= clock_timestamp() AND attempt_count < 5
             AND (claim_until IS NULL OR claim_until <= clock_timestamp())
           ORDER BY next_attempt_at,id FOR UPDATE SKIP LOCKED LIMIT 1
         )
         UPDATE specialist_notification_live_outbox o
         SET claim_token=$1, claim_until=clock_timestamp()+interval '30 seconds',
             attempt_count=attempt_count+1
         FROM candidate WHERE o.id=candidate.id
         RETURNING o.id,o.notification_id,o.recipient_account_id,o.aggregate_version,
           o.correlation_id,o.causation_id,o.occurred_at,o.expires_at,o.claim_token,o.attempt_count`,
        [randomUUID()],
      );
      return result.rows[0] ?? null;
    });
  }

  async eligibleClaim(row: SpecialistNotificationLiveClaim): Promise<boolean> {
    const result = await this.db.query(
      `SELECT o.id FROM specialist_notification_live_outbox o
       JOIN notification n ON n.id=o.notification_id AND n.recipient_id=o.recipient_account_id
       JOIN notification_preference p ON p.user_id=n.recipient_id
       WHERE o.id=$1 AND o.claim_token=$2 AND o.claim_until > clock_timestamp()
         AND o.state='PENDING' AND o.recipient_role='SPECIALIST'
         AND o.expires_at > clock_timestamp() AND ${ELIGIBILITY}`,
      [row.id, row.claim_token],
    );
    return result.rows.length > 0;
  }

  async published(row: SpecialistNotificationLiveClaim): Promise<boolean> {
    const result = await this.db.query(
      `UPDATE specialist_notification_live_outbox
       SET state='PUBLISHED', published_at=clock_timestamp(), next_attempt_at=NULL,
           claim_token=NULL, claim_until=NULL, last_error_code=NULL
       WHERE id=$1 AND claim_token=$2 AND state='PENDING' AND claim_until > clock_timestamp()`,
      [row.id, row.claim_token],
    );
    return result.rowCount === 1;
  }

  async suppress(row: SpecialistNotificationLiveClaim): Promise<boolean> {
    const result = await this.db.query(
      `UPDATE specialist_notification_live_outbox
       SET state='SUPPRESSED', next_attempt_at=NULL, claim_token=NULL, claim_until=NULL,
           last_error_code='NOT_ELIGIBLE'
       WHERE id=$1 AND claim_token=$2 AND state='PENDING' AND claim_until > clock_timestamp()`,
      [row.id, row.claim_token],
    );
    return result.rowCount === 1;
  }

  async retry(row: SpecialistNotificationLiveClaim): Promise<boolean> {
    const delays = [1_000, 5_000, 30_000, 60_000] as const;
    const delay = delays[Math.min(row.attempt_count - 1, delays.length - 1)];
    const result = await this.db.query(
      `UPDATE specialist_notification_live_outbox
       SET state=CASE WHEN attempt_count >= 5 THEN 'FAILED' ELSE 'PENDING' END,
           next_attempt_at=CASE WHEN attempt_count >= 5 THEN NULL
             ELSE clock_timestamp()+($3::integer * interval '1 millisecond') END,
           claim_token=NULL, claim_until=NULL, last_error_code='PUBLISH_UNAVAILABLE'
       WHERE id=$1 AND claim_token=$2 AND state='PENDING' AND claim_until > clock_timestamp()`,
      [row.id, row.claim_token, delay],
    );
    return result.rowCount === 1;
  }
}
