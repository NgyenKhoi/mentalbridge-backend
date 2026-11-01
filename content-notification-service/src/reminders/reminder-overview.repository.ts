import { Inject, Injectable } from '@nestjs/common';
import { DATABASE_SERVICE_TOKEN } from '../application.tokens.js';
import type { DatabaseService } from '../database/database.service.js';
import type {
  ReminderDeliveryFact,
  ReminderDeliveryState,
  ReminderKind,
} from './reminder-overview.types.js';

@Injectable()
export class ReminderOverviewRepository {
  constructor(@Inject(DATABASE_SERVICE_TOKEN) private readonly db: DatabaseService) {}

  async deliveries(
    ownerId: string,
    localDate: string,
  ): Promise<Partial<Record<ReminderKind, ReminderDeliveryFact>>> {
    const result = await this.db.query<{
      kind: ReminderKind;
      state: ReminderDeliveryState;
      recorded_at: Date | string;
    }>(
      `SELECT kinds.kind, latest.delivery_state AS state, latest.created_at AS recorded_at
       FROM (VALUES ('JOURNAL_REMINDER'), ('EMOTION_CHECKIN_REMINDER')) AS kinds(kind)
       CROSS JOIN LATERAL (
         SELECT delivery_state, created_at FROM notification
         WHERE recipient_id = $1 AND source = 'JOURNAL_AI_ACTIVITY'
           AND source_identity = kinds.kind || ':' || $2
         ORDER BY created_at DESC, id DESC LIMIT 1
       ) latest
       UNION ALL
       SELECT delivery_kind AS kind, state, updated_at AS recorded_at
       FROM wellbeing_email_delivery WHERE owner_id = $1 AND local_date = $2::date
       UNION ALL
       (SELECT 'APPOINTMENT_EMAIL' AS kind, delivery_state AS state, updated_at AS recorded_at
        FROM appointment_email_reminder WHERE recipient_id = $1
        ORDER BY updated_at DESC, id DESC LIMIT 1)`,
      [ownerId, localDate],
    );
    return Object.fromEntries(
      result.rows.map((row) => [
        row.kind,
        {
          state: row.state,
          recordedAt: new Date(row.recorded_at).toISOString(),
        },
      ]),
    );
  }
}
