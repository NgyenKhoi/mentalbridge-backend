import { Inject, Injectable } from '@nestjs/common';

import { DATABASE_SERVICE_TOKEN } from '../application.tokens.js';
import type { DatabaseService } from '../database/database.service.js';
import type {
  AchievementPreference,
  AchievementResourceFact,
  AchievementVisibility,
} from './achievement.types.js';

interface FactRow {
  readonly kind: AchievementResourceFact['kind'];
  readonly resource_id: string;
  readonly content_version: string | number;
  readonly local_date: string | Date;
  readonly occurred_at: Date;
}

interface PreferenceRow {
  readonly visibility_state: AchievementVisibility;
  readonly version: string | number;
}

export class AchievementPreferenceVersionMismatchError extends Error {
  constructor() {
    super('Achievement preference version mismatch');
    this.name = 'AchievementPreferenceVersionMismatchError';
  }
}

function localDate(value: string | Date): string {
  return value instanceof Date ? value.toISOString().slice(0, 10) : value;
}

@Injectable()
export class AchievementRepository {
  constructor(
    @Inject(DATABASE_SERVICE_TOKEN)
    private readonly db: DatabaseService,
  ) {}

  async read(ownerId: string): Promise<{
    readonly facts: readonly AchievementResourceFact[];
    readonly preference: AchievementPreference;
  }> {
    const [facts, preference] = await Promise.all([
      this.db.query<FactRow>(
        `WITH completion_facts AS (
           SELECT 'RESOURCE_COMPLETION'::text AS kind, progress.resource_id,
             progress.resource_version AS content_version, progress.local_date,
             progress.completed_at AS occurred_at
           FROM resource_daily_progress progress
           JOIN resource_publication_snapshot publication
             ON publication.resource_id = progress.resource_id
            AND publication.content_version = progress.resource_version
           WHERE progress.owner_id = $1
             AND progress.status = 'COMPLETED'
             AND progress.completed_at IS NOT NULL
         ), practice_facts AS (
           SELECT DISTINCT ON (session.resource_id, session.local_date)
             'VERIFIED_PRACTICE'::text AS kind, session.resource_id,
             session.resource_version AS content_version, session.local_date,
             session.completed_at AS occurred_at
           FROM resource_practice_session session
           JOIN resource_publication_snapshot publication
             ON publication.resource_id = session.resource_id
            AND publication.content_version = session.resource_version
           WHERE session.owner_id = $1
             AND session.support_plan_id IS NOT NULL
             AND session.started_at IS NOT NULL
             AND session.duration_seconds BETWEEN 60 AND 7200
           ORDER BY session.resource_id, session.local_date, session.completed_at, session.id
         )
         SELECT * FROM completion_facts
         UNION ALL
         SELECT * FROM practice_facts
         ORDER BY occurred_at, kind, resource_id`,
        [ownerId],
      ),
      this.db.query<PreferenceRow>(
        `SELECT visibility_state, version
         FROM achievement_preference
         WHERE owner_id = $1`,
        [ownerId],
      ),
    ]);
    return {
      facts: facts.rows.map((row) => ({
        kind: row.kind,
        resourceId: row.resource_id,
        contentVersion: String(row.content_version),
        localDate: localDate(row.local_date),
        occurredAt: row.occurred_at.toISOString(),
      })),
      preference: preference.rows[0]
        ? {
            visibilityState: preference.rows[0].visibility_state,
            version: String(preference.rows[0].version),
          }
        : { visibilityState: 'VISIBLE', version: '0' },
    };
  }

  async setVisibility(
    ownerId: string,
    visibilityState: AchievementVisibility,
    expectedVersion: string,
  ): Promise<void> {
    const result = await this.db.query(
      `INSERT INTO achievement_preference (owner_id, visibility_state, version)
       SELECT $1, $2, 1
       WHERE $3::bigint = 0
       ON CONFLICT (owner_id) DO UPDATE
       SET visibility_state = EXCLUDED.visibility_state,
           version = achievement_preference.version + 1,
           updated_at = now()
       WHERE achievement_preference.version = $3::bigint
         AND achievement_preference.visibility_state IS DISTINCT FROM EXCLUDED.visibility_state`,
      [ownerId, visibilityState, expectedVersion],
    );
    if ((result.rowCount ?? 0) > 0) return;

    const current = await this.db.query<PreferenceRow>(
      `SELECT visibility_state, version FROM achievement_preference WHERE owner_id = $1`,
      [ownerId],
    );
    const row = current.rows.at(0);
    if (
      (!row && expectedVersion === '0' && visibilityState === 'VISIBLE') ||
      (row && String(row.version) === expectedVersion && row.visibility_state === visibilityState)
    ) {
      return;
    }
    throw new AchievementPreferenceVersionMismatchError();
  }
}
