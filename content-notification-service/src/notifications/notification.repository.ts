import { Inject, Injectable } from '@nestjs/common';

import { DATABASE_SERVICE_TOKEN } from '../application.tokens.js';
import type { DatabaseService } from '../database/database.service.js';
import type {
  NotificationCreate,
  NotificationCursor,
  NotificationItem,
  NotificationPage,
  NotificationRow,
} from './notification.types.js';

const COLUMNS = `id, recipient_id, category, title, body, action_type, action_target_id,
  priority, read_at, expires_at, occurred_at, created_at, source, source_identity,
  request_fingerprint, delivery_state, deleted_at, version`;

const LEGACY_KIND: Readonly<Record<string, NotificationItem['kind']>> = {
  ASSESSMENT: 'ASSESSMENT_REASSESSMENT',
  CHAT: 'MESSAGE',
  FOLLOW_UP: 'REMINDER',
  SAFETY: 'SYSTEM_RESOURCE',
  SYSTEM: 'SYSTEM_RESOURCE',
};

export class NotificationDedupeConflictError extends Error {
  constructor() {
    super('Notification source identity was reused with different content');
    this.name = 'NotificationDedupeConflictError';
  }
}

export type NotificationMutationResult =
  | Readonly<{ outcome: 'UPDATED'; item: NotificationItem }>
  | Readonly<{ outcome: 'GONE' }>
  | Readonly<{ outcome: 'NOT_FOUND' }>;

function iso(value: Date | string): string {
  return new Date(value).toISOString();
}

function action(row: NotificationRow): NotificationItem['action'] {
  if (!row.action_type) return null;
  const targetId = row.action_target_id;
  const href: Readonly<Record<string, string>> = {
    OPEN_JOURNAL: '/journal',
    OPEN_MESSAGES: '/messages',
    OPEN_APPOINTMENTS: '/appointments',
    OPEN_RESOURCES: '/resources',
    OPEN_ASSESSMENTS: '/assessments',
  };
  return {
    type: row.action_type,
    targetId,
    href:
      row.action_type === 'OPEN_RESOURCE' && targetId
        ? `/resources/${encodeURIComponent(targetId)}`
        : href[row.action_type],
  };
}

export function toNotificationItem(row: NotificationRow): NotificationItem {
  return {
    id: row.id,
    kind: LEGACY_KIND[row.category] ?? (row.category as NotificationItem['kind']),
    title: row.title,
    body: row.body,
    priority: row.priority,
    occurredAt: iso(row.occurred_at),
    createdAt: iso(row.created_at),
    read: row.read_at !== null,
    readAt: row.read_at === null ? null : iso(row.read_at),
    action: action(row),
    lifecycleState: 'ACTIVE',
    expiresAt: iso(row.expires_at),
  };
}

@Injectable()
export class NotificationRepository {
  constructor(
    @Inject(DATABASE_SERVICE_TOKEN)
    private readonly db: DatabaseService,
  ) {}

  async create(command: NotificationCreate, fingerprint: string): Promise<NotificationItem> {
    const expiresAt = command.expiresAt ?? new Date(Date.now() + 90 * 86_400_000).toISOString();
    const inserted = await this.db.query<NotificationRow>(
      `INSERT INTO notification
         (recipient_id, category, title, body, action_type, action_target_id, priority,
          expires_at, occurred_at, source, source_identity, request_fingerprint)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12)
       ON CONFLICT (recipient_id, source, source_identity) DO NOTHING
       RETURNING ${COLUMNS}`,
      [
        command.ownerId,
        command.kind,
        command.title,
        command.body,
        command.action?.type ?? null,
        command.action?.targetId ?? null,
        command.priority,
        expiresAt,
        command.occurredAt,
        command.source,
        command.sourceIdentity,
        fingerprint,
      ],
    );
    if (inserted.rows[0]) return toNotificationItem(inserted.rows[0]);

    const existing = await this.db.query<NotificationRow>(
      `SELECT ${COLUMNS}
       FROM notification
       WHERE recipient_id = $1 AND source = $2 AND source_identity = $3`,
      [command.ownerId, command.source, command.sourceIdentity],
    );
    if (!existing.rows[0] || existing.rows[0].request_fingerprint !== fingerprint) {
      throw new NotificationDedupeConflictError();
    }
    return toNotificationItem(existing.rows[0]);
  }

  async list(
    ownerId: string,
    limit: number,
    cursor: NotificationCursor | null,
  ): Promise<NotificationPage> {
    return this.db.withTransaction(async (client) => {
      await client.query(
        `UPDATE notification
         SET deleted_at = now(), version = version + 1
         WHERE recipient_id = $1 AND deleted_at IS NULL AND expires_at <= now()`,
        [ownerId],
      );
      const count = await client.query<{ count: string }>(
        `SELECT count(*)::text AS count
         FROM notification
         WHERE recipient_id = $1 AND delivery_state = 'DELIVERED'
           AND deleted_at IS NULL AND expires_at > now() AND read_at IS NULL`,
        [ownerId],
      );
      const page = await client.query<NotificationRow>(
        `SELECT ${COLUMNS}
         FROM notification
         WHERE recipient_id = $1 AND delivery_state = 'DELIVERED'
           AND deleted_at IS NULL AND expires_at > now()
           AND ($2::timestamptz IS NULL OR (created_at, id) < ($2::timestamptz, $3::uuid))
         ORDER BY created_at DESC, id DESC
         LIMIT $4`,
        [ownerId, cursor?.createdAt ?? null, cursor?.id ?? null, limit + 1],
      );
      const hasMore = page.rows.length > limit;
      const visibleRows = page.rows.slice(0, limit);
      const last = hasMore ? visibleRows.at(-1) : undefined;
      return {
        items: visibleRows.map(toNotificationItem),
        nextCursor: last ? encodeNotificationCursor(last.created_at, last.id) : null,
        hasMore,
        unreadCount: Number(count.rows[0]?.count ?? 0),
      };
    });
  }

  async markRead(ownerId: string, notificationId: string): Promise<NotificationMutationResult> {
    const updated = await this.db.query<NotificationRow>(
      `UPDATE notification
       SET read_at = COALESCE(read_at, now()),
           version = version + CASE WHEN read_at IS NULL THEN 1 ELSE 0 END
       WHERE id = $1 AND recipient_id = $2 AND deleted_at IS NULL
         AND expires_at > now() AND delivery_state = 'DELIVERED'
       RETURNING ${COLUMNS}`,
      [notificationId, ownerId],
    );
    if (updated.rows[0]) return { outcome: 'UPDATED', item: toNotificationItem(updated.rows[0]) };
    return {
      outcome: (await this.ownerRecordExists(ownerId, notificationId)) ? 'GONE' : 'NOT_FOUND',
    };
  }

  async markAllRead(ownerId: string): Promise<number> {
    const result = await this.db.query(
      `UPDATE notification
       SET read_at = now(), version = version + 1
       WHERE recipient_id = $1 AND read_at IS NULL AND deleted_at IS NULL
         AND expires_at > now() AND delivery_state = 'DELIVERED'`,
      [ownerId],
    );
    return result.rowCount ?? 0;
  }

  async delete(ownerId: string, notificationId: string): Promise<'DELETED' | 'NOT_FOUND'> {
    const result = await this.db.query(
      `UPDATE notification
       SET deleted_at = COALESCE(deleted_at, now()),
           version = version + CASE WHEN deleted_at IS NULL THEN 1 ELSE 0 END
       WHERE id = $1 AND recipient_id = $2`,
      [notificationId, ownerId],
    );
    return (result.rowCount ?? 0) > 0 ? 'DELETED' : 'NOT_FOUND';
  }

  private async ownerRecordExists(ownerId: string, notificationId: string): Promise<boolean> {
    const result = await this.db.query(
      `SELECT 1 FROM notification WHERE id = $1 AND recipient_id = $2`,
      [notificationId, ownerId],
    );
    return Boolean(result.rows[0]);
  }
}

export function encodeNotificationCursor(createdAt: Date | string, id: string): string {
  return Buffer.from(JSON.stringify([iso(createdAt), id]), 'utf8').toString('base64url');
}

export function decodeNotificationCursor(value: string): NotificationCursor | null {
  try {
    if (!/^[A-Za-z0-9_-]{1,256}$/.test(value)) return null;
    const parsed: unknown = JSON.parse(Buffer.from(value, 'base64url').toString('utf8'));
    if (!Array.isArray(parsed) || parsed.length !== 2) return null;
    const createdAt: unknown = parsed[0];
    const id: unknown = parsed[1];
    if (
      typeof createdAt !== 'string' ||
      !Number.isFinite(Date.parse(createdAt)) ||
      typeof id !== 'string' ||
      !/^[\da-f]{8}-[\da-f]{4}-[1-5][\da-f]{3}-[89ab][\da-f]{3}-[\da-f]{12}$/i.test(id)
    ) {
      return null;
    }
    return { createdAt: new Date(createdAt).toISOString(), id };
  } catch {
    return null;
  }
}
