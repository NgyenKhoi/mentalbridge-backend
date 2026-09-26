import { Inject, Injectable, ServiceUnavailableException } from '@nestjs/common';
import { NOTIFICATION_PREFERENCE_REPOSITORY_TOKEN } from '../application.tokens.js';
import type { NotificationPreferenceRepository } from './notification-preference.repository.js';
import { NotificationPreferenceVersionMismatchError } from './notification-preference.repository.js';
import type {
  NotificationPreferences,
  NotificationPreferenceUpdate,
} from './notification-preference.types.js';

function validTimeZone(value: string): boolean {
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: value }).format();
    return true;
  } catch {
    return false;
  }
}

export class InvalidNotificationPreferenceError extends Error {
  constructor(
    readonly field: string,
    message: string,
  ) {
    super(message);
    this.name = 'InvalidNotificationPreferenceError';
  }
}

@Injectable()
export class NotificationPreferenceService {
  constructor(
    @Inject(NOTIFICATION_PREFERENCE_REPOSITORY_TOKEN)
    private readonly repository: NotificationPreferenceRepository,
  ) {}

  async get(userId: string): Promise<NotificationPreferences> {
    try {
      return await this.repository.getOrCreate(userId);
    } catch {
      throw new ServiceUnavailableException();
    }
  }

  async update(
    userId: string,
    expectedVersion: number,
    patch: NotificationPreferenceUpdate,
  ): Promise<NotificationPreferences> {
    let current: NotificationPreferences;
    try {
      current = await this.repository.getOrCreate(userId);
    } catch {
      throw new ServiceUnavailableException();
    }

    const next: NotificationPreferences = {
      notificationsEnabled: patch.notificationsEnabled ?? current.notificationsEnabled,
      channels: { ...current.channels, ...patch.channels },
      contentGroups: { ...current.contentGroups, ...patch.contentGroups },
      quietHours: { ...current.quietHours, ...patch.quietHours },
      email: { ...current.email, ...patch.email },
      version: current.version,
      updatedAt: current.updatedAt,
    };

    if (!validTimeZone(next.quietHours.timeZone)) {
      throw new InvalidNotificationPreferenceError(
        'quietHours.timeZone',
        'must be a valid IANA time zone',
      );
    }
    if (next.quietHours.enabled && next.quietHours.start === next.quietHours.end) {
      throw new InvalidNotificationPreferenceError(
        'quietHours.end',
        'must differ from start when quiet hours are enabled',
      );
    }

    try {
      return await this.repository.update(userId, expectedVersion, next);
    } catch (error) {
      if (error instanceof NotificationPreferenceVersionMismatchError) throw error;
      throw new ServiceUnavailableException();
    }
  }
}
