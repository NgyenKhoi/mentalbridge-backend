import { ConflictException, Inject, Injectable, ServiceUnavailableException } from '@nestjs/common';
import {
  CONFIGURATION_TOKEN,
  NOTIFICATION_PREFERENCE_SERVICE_TOKEN,
  REMINDER_ACTIVITY_CLIENT_TOKEN,
  REMINDER_CLOCK_TOKEN,
  WELLBEING_DIGEST_REPOSITORY_TOKEN,
} from '../application.tokens.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import type { NotificationPreferenceService } from '../notification-preferences/notification-preference.service.js';
import type { NotificationPreferences } from '../notification-preferences/notification-preference.types.js';
import type { WellbeingDigestRepository } from '../wellbeing-digest/wellbeing-digest.repository.js';
import type { NotificationActivity, ReminderActivityClient } from './reminder-activity.client.js';
import { isQuietHour, type ReminderClock } from './reminder-materialization.service.js';
import { nextReminderEligibility, reminderLocalDate } from './reminder-overview.policy.js';
import { ReminderOverviewRepository } from './reminder-overview.repository.js';
import type {
  ReminderDeliveryFact,
  ReminderKind,
  ReminderOverview,
  ReminderPreview,
} from './reminder-overview.types.js';

@Injectable()
export class ReminderOverviewService {
  constructor(
    @Inject(NOTIFICATION_PREFERENCE_SERVICE_TOKEN)
    private readonly preferences: NotificationPreferenceService,
    @Inject(REMINDER_ACTIVITY_CLIENT_TOKEN) private readonly activity: ReminderActivityClient,
    @Inject(REMINDER_CLOCK_TOKEN) private readonly clock: ReminderClock,
    @Inject(CONFIGURATION_TOKEN) private readonly configuration: ServiceConfiguration,
    @Inject(WELLBEING_DIGEST_REPOSITORY_TOKEN) private readonly digest: WellbeingDigestRepository,
    @Inject(ReminderOverviewRepository) private readonly repository: ReminderOverviewRepository,
  ) {}

  async get(ownerId: string, correlationId: string): Promise<ReminderOverview> {
    try {
      const preferences = await this.preferences.get(ownerId, 'USER');
      const now = this.clock.now();
      const timeZone = preferences.quietHours.timeZone;
      const localDate = reminderLocalDate(now, timeZone);
      const needActivity =
        preferences.contentGroups.journalReminder || preferences.contentGroups.emotionCheckIn;
      const needResources =
        preferences.channels.email &&
        preferences.contentGroups.resourceSystem &&
        (preferences.email.wellbeingDigestEnabled || preferences.email.resourceRemindersEnabled);
      const [activity, resources, deliveries] = await Promise.all([
        needActivity ? this.activity.get(ownerId, timeZone, correlationId) : Promise.resolve(null),
        needResources ? this.digest.pendingResources(ownerId, localDate) : Promise.resolve([]),
        this.repository.deliveries(ownerId, localDate),
      ]);
      if (
        (activity &&
          (activity.achievementFacts.ownerAccountId !== ownerId ||
            activity.timezone !== timeZone ||
            activity.asOfLocalDate !== localDate)) ||
        reminderLocalDate(this.clock.now(), timeZone) !== localDate
      )
        throw new ServiceUnavailableException();
      const current = await this.preferences.get(ownerId, 'USER');
      if (current.version !== preferences.version)
        throw new ConflictException({
          type: 'https://mentalbridge.io/errors/REMINDER_OVERVIEW_CHANGED',
          title: 'Preferences changed; retry the overview',
          status: 409,
          code: 'REMINDER_OVERVIEW_CHANGED',
        });
      const today = this.today(activity, preferences, localDate);
      const journalPending =
        preferences.contentGroups.journalReminder && activity?.journal.completedToday === false;
      const emotionPending =
        preferences.contentGroups.emotionCheckIn &&
        activity?.emotionCheckIn.completedToday === false;
      const inApp = this.configuration.REMINDER_SCHEDULER_ENABLED;
      const email = this.configuration.WELLBEING_DIGEST_SCHEDULER_ENABLED;
      return {
        schemaVersion: '1.0',
        asOf: now.toISOString(),
        localDate,
        timeZone,
        preferences,
        today,
        previews: [
          this.preview(
            'JOURNAL_REMINDER',
            'IN_APP',
            preferences.contentGroups.journalReminder,
            journalPending,
            inApp,
            preferences,
            now,
            deliveries.JOURNAL_REMINDER,
          ),
          this.preview(
            'EMOTION_CHECKIN_REMINDER',
            'IN_APP',
            preferences.contentGroups.emotionCheckIn,
            emotionPending,
            inApp,
            preferences,
            now,
            deliveries.EMOTION_CHECKIN_REMINDER,
          ),
          this.preview(
            'DAILY_DIGEST',
            'EMAIL',
            preferences.email.wellbeingDigestEnabled &&
              preferences.email.cadence === 'DAILY_DIGEST',
            journalPending || emotionPending || resources.length > 0,
            email,
            preferences,
            now,
            deliveries.DAILY_DIGEST,
            preferences.email.dailyDigestTime,
          ),
          this.preview(
            'RESOURCE_REMINDER',
            'EMAIL',
            preferences.contentGroups.resourceSystem && preferences.email.resourceRemindersEnabled,
            resources.length > 0,
            email,
            preferences,
            now,
            deliveries.RESOURCE_REMINDER,
            preferences.email.resourceReminderTime,
          ),
          {
            kind: 'APPOINTMENT_EMAIL',
            channel: 'EMAIL',
            state: !preferences.notificationsEnabled
              ? 'PAUSED'
              : !preferences.channels.email ||
                  !preferences.contentGroups.appointmentMessage ||
                  !preferences.email.appointmentRemindersEnabled
                ? 'DISABLED'
                : 'UNAVAILABLE',
            nextEligibleAt: null,
            estimate: true,
            latestDelivery: deliveries.APPOINTMENT_EMAIL ?? null,
          },
        ],
        unavailableCategories: ['SUPPORT_PLAN', 'RESOURCES', 'ASSESSMENT', 'PUSH'],
      };
    } catch (error) {
      if (error instanceof ConflictException) throw error;
      throw new ServiceUnavailableException({
        type: 'https://mentalbridge.io/errors/REMINDER_OVERVIEW_UNAVAILABLE',
        title: 'Current reminder facts are unavailable',
        status: 503,
        code: 'REMINDER_OVERVIEW_UNAVAILABLE',
      });
    }
  }

  private today(
    activity: NotificationActivity | null,
    preferences: NotificationPreferences,
    localDate: string,
  ): ReminderOverview['today'] {
    if (!activity) return [];
    const result: ReminderOverview['today'][number][] = [];
    if (preferences.contentGroups.journalReminder)
      result.push({
        id: `JOURNAL:${localDate}`,
        category: 'JOURNAL',
        status: activity.journal.completedToday ? 'COMPLETED' : 'DUE',
        action: 'OPEN_JOURNAL',
      });
    if (preferences.contentGroups.emotionCheckIn)
      result.push({
        id: `EMOTION_CHECK_IN:${localDate}`,
        category: 'EMOTION_CHECK_IN',
        status: activity.emotionCheckIn.completedToday ? 'COMPLETED' : 'DUE',
        action: 'OPEN_EMOTION_CHECK_IN',
      });
    return result;
  }

  private preview(
    kind: ReminderKind,
    channel: 'IN_APP' | 'EMAIL',
    enabled: boolean,
    pending: boolean,
    runtimeEnabled: boolean,
    preferences: NotificationPreferences,
    now: Date,
    latestDelivery: ReminderDeliveryFact | undefined,
    notBefore?: string,
  ): ReminderPreview {
    const base = {
      kind,
      channel,
      estimate: true as const,
      latestDelivery: latestDelivery ?? null,
      nextEligibleAt: null,
    };
    if (!preferences.notificationsEnabled) return { ...base, state: 'PAUSED' };
    if (
      !enabled ||
      !(channel === 'IN_APP' ? preferences.channels.inApp : preferences.channels.email)
    )
      return { ...base, state: 'DISABLED' };
    if (!pending) return { ...base, state: channel === 'IN_APP' ? 'COMPLETED' : 'NOT_NEEDED' };
    if (latestDelivery?.state === 'DELIVERED') return { ...base, state: 'ALREADY_DELIVERED' };
    if (latestDelivery && ['CLAIMED', 'PROCESSING', 'PENDING'].includes(latestDelivery.state))
      return { ...base, state: 'IN_PROGRESS' };
    if (
      !runtimeEnabled ||
      (latestDelivery &&
        ['CANCELLED', 'INVALIDATED', 'SUPPRESSED', 'EXPIRED', 'UNKNOWN', 'FAILED'].includes(
          latestDelivery.state,
        ))
    )
      return { ...base, state: 'UNAVAILABLE' };
    const nextEligibleAt = nextReminderEligibility(preferences, now, notBefore);
    if (!nextEligibleAt) return { ...base, state: 'UNAVAILABLE' };
    return {
      ...base,
      nextEligibleAt,
      state: isQuietHour(preferences, now)
        ? 'QUIET_HOURS'
        : nextEligibleAt === now.toISOString()
          ? 'ELIGIBLE'
          : 'SCHEDULED',
    };
  }
}
