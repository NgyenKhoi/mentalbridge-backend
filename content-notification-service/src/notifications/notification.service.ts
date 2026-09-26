import {
  GoneException,
  Inject,
  Injectable,
  NotFoundException,
  ServiceUnavailableException,
  UnprocessableEntityException,
} from '@nestjs/common';
import { createHash } from 'node:crypto';
import { ZodError } from 'zod';

import { NOTIFICATION_REPOSITORY_TOKEN } from '../application.tokens.js';
import { NotificationCreateSchema } from './notification.dto.js';
import {
  decodeNotificationCursor,
  NotificationDedupeConflictError,
  type NotificationRepository,
} from './notification.repository.js';
import type { NotificationItem } from './notification.types.js';

function problem(status: number, code: string, title: string): Record<string, unknown> {
  return { type: `https://mentalbridge.io/errors/${code}`, title, status, code };
}

@Injectable()
export class NotificationService {
  constructor(
    @Inject(NOTIFICATION_REPOSITORY_TOKEN)
    private readonly repository: NotificationRepository,
  ) {}

  async create(payload: unknown): Promise<NotificationItem> {
    let command;
    try {
      command = NotificationCreateSchema.parse(payload);
    } catch (error) {
      if (!(error instanceof ZodError)) throw error;
      throw new UnprocessableEntityException({
        ...problem(422, 'NOTIFICATION_VALIDATION_FAILED', 'Notification is invalid'),
        fieldViolations: error.issues.slice(0, 32).map((issue) => ({
          field: issue.path.join('.') || 'body',
          message: issue.message.slice(0, 300),
        })),
      });
    }
    const now = Date.now();
    const occurredAt = Date.parse(command.occurredAt);
    const expiresAt = command.expiresAt ? Date.parse(command.expiresAt) : undefined;
    if (occurredAt > now + 5 * 60_000) {
      throw new UnprocessableEntityException(
        problem(422, 'NOTIFICATION_VALIDATION_FAILED', 'Occurred time is invalid'),
      );
    }
    if (expiresAt !== undefined && (expiresAt <= now || expiresAt > now + 90 * 86_400_000)) {
      throw new UnprocessableEntityException(
        problem(422, 'NOTIFICATION_VALIDATION_FAILED', 'Retention deadline is invalid'),
      );
    }
    const fingerprint = createHash('sha256').update(JSON.stringify(command)).digest('hex');
    try {
      return await this.repository.create(command, fingerprint);
    } catch (error) {
      if (error instanceof NotificationDedupeConflictError) throw error;
      throw new ServiceUnavailableException();
    }
  }

  async list(ownerId: string, rawLimit: string | undefined, rawCursor: string | undefined) {
    const limit = rawLimit === undefined ? 20 : Number(rawLimit);
    if (!Number.isInteger(limit) || limit < 1 || limit > 50) {
      throw new UnprocessableEntityException(
        problem(422, 'NOTIFICATION_PAGE_INVALID', 'Notification page is invalid'),
      );
    }
    const cursor = rawCursor === undefined ? null : decodeNotificationCursor(rawCursor);
    if (rawCursor !== undefined && !cursor) {
      throw new UnprocessableEntityException(
        problem(422, 'NOTIFICATION_PAGE_INVALID', 'Notification page is invalid'),
      );
    }
    try {
      return await this.repository.list(ownerId, limit, cursor);
    } catch {
      throw new ServiceUnavailableException();
    }
  }

  async markRead(ownerId: string, notificationId: string): Promise<NotificationItem> {
    let result;
    try {
      result = await this.repository.markRead(ownerId, notificationId);
    } catch {
      throw new ServiceUnavailableException();
    }
    if (result.outcome === 'NOT_FOUND') {
      throw new NotFoundException(problem(404, 'NOTIFICATION_NOT_FOUND', 'Notification not found'));
    }
    if (result.outcome === 'GONE') {
      throw new GoneException(
        problem(410, 'NOTIFICATION_GONE', 'Notification is no longer available'),
      );
    }
    return result.item;
  }

  async markAllRead(ownerId: string): Promise<Readonly<{ updatedCount: number }>> {
    try {
      return { updatedCount: await this.repository.markAllRead(ownerId) };
    } catch {
      throw new ServiceUnavailableException();
    }
  }

  async delete(ownerId: string, notificationId: string): Promise<void> {
    let outcome;
    try {
      outcome = await this.repository.delete(ownerId, notificationId);
    } catch {
      throw new ServiceUnavailableException();
    }
    if (outcome === 'NOT_FOUND') {
      throw new NotFoundException(problem(404, 'NOTIFICATION_NOT_FOUND', 'Notification not found'));
    }
  }
}
