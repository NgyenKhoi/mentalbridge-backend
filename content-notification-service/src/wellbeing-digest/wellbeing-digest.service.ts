import { Inject, Injectable } from '@nestjs/common';
import {
  REMINDER_ACTIVITY_CLIENT_TOKEN,
  REMINDER_CLOCK_TOKEN,
  WELLBEING_DIGEST_REPOSITORY_TOKEN,
  WELLBEING_EMAIL_DELIVERY_TOKEN,
  WELLBEING_RECIPIENT_CLIENT_TOKEN,
} from '../application.tokens.js';
import type { NotificationPreferences } from '../notification-preferences/notification-preference.types.js';
import type { ReminderActivityClient } from '../reminders/reminder-activity.client.js';
import { isQuietHour, type ReminderClock } from '../reminders/reminder-materialization.service.js';
import type { WellbeingDigestRepository } from './wellbeing-digest.repository.js';
import {
  WellbeingRecipientUnavailableError,
  type WellbeingDigestPreview,
  type WellbeingEmailDelivery,
  type WellbeingRecipientClient,
} from './wellbeing-digest.types.js';

function local(instant: Date, timeZone: string): { date: string; minutes: number } {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(instant);
  const value = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((part) => part.type === type)?.value ?? '';
  return {
    date: `${value('year')}-${value('month')}-${value('day')}`,
    minutes: Number(value('hour')) * 60 + Number(value('minute')),
  };
}

const minutes = (time: string): number => {
  const [hour, minute] = time.split(':').map(Number);
  return hour * 60 + minute;
};

const escapeHtml = (value: string): string =>
  value.replace(
    /[&<>"']/g,
    (character) =>
      ({
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        '"': '&quot;',
        "'": '&#39;',
      })[character] ?? character,
  );

@Injectable()
export class WellbeingDigestService {
  constructor(
    @Inject(WELLBEING_DIGEST_REPOSITORY_TOKEN)
    private readonly repository: WellbeingDigestRepository,
    @Inject(REMINDER_ACTIVITY_CLIENT_TOKEN) private readonly activity: ReminderActivityClient,
    @Inject(WELLBEING_RECIPIENT_CLIENT_TOKEN) private readonly recipients: WellbeingRecipientClient,
    @Inject(WELLBEING_EMAIL_DELIVERY_TOKEN) private readonly delivery: WellbeingEmailDelivery,
    @Inject(REMINDER_CLOCK_TOKEN) private readonly clock: ReminderClock,
  ) {}

  async preview(
    ownerId: string,
    preferences: NotificationPreferences,
    correlationId: string,
  ): Promise<WellbeingDigestPreview> {
    const now = this.clock.now();
    const current = local(now, preferences.quietHours.timeZone);
    const [resources, activity] = await Promise.all([
      this.repository.pendingResources(ownerId, current.date),
      this.activity.get(ownerId, preferences.quietHours.timeZone, correlationId),
    ]);
    if (
      activity.timezone !== preferences.quietHours.timeZone ||
      activity.asOfLocalDate !== current.date
    ) {
      throw new Error('Reminder activity projection is not current for the requested local day');
    }
    const includeJournalPrompt =
      preferences.contentGroups.journalReminder && !activity.journal.completedToday;
    const includeEmotionPrompt =
      preferences.contentGroups.emotionCheckIn && !activity.emotionCheckIn.completedToday;
    const resourceItems = preferences.contentGroups.resourceSystem ? resources : [];
    const empty = resourceItems.length === 0 && !includeJournalPrompt && !includeEmotionPrompt;
    return {
      localDate: current.date,
      timeZone: preferences.quietHours.timeZone,
      scheduledTime: preferences.email.dailyDigestTime,
      eligibleNow:
        preferences.notificationsEnabled &&
        preferences.channels.email &&
        preferences.email.wellbeingDigestEnabled &&
        preferences.email.cadence === 'DAILY_DIGEST' &&
        current.minutes >= minutes(preferences.email.dailyDigestTime) &&
        !isQuietHour(preferences, now) &&
        !empty,
      resourceItems,
      includeJournalPrompt,
      includeEmotionPrompt,
      empty,
    };
  }

  async deliver(
    ownerId: string,
    preferences: NotificationPreferences,
    correlationId: string,
  ): Promise<number> {
    const preview = await this.preview(ownerId, preferences, correlationId);
    let delivered = 0;
    if (preview.eligibleNow) {
      delivered += await this.sendDigest(ownerId, preferences, preview, correlationId);
    }
    const now = this.clock.now();
    const current = local(now, preferences.quietHours.timeZone);
    if (
      preferences.notificationsEnabled &&
      preferences.channels.email &&
      preferences.email.resourceRemindersEnabled &&
      preview.resourceItems[0] &&
      current.minutes >= minutes(preferences.email.resourceReminderTime) &&
      !isQuietHour(preferences, now)
    ) {
      delivered += await this.sendResourceReminder(ownerId, preferences, preview, correlationId);
    }
    return delivered;
  }

  private async sendDigest(
    ownerId: string,
    preferences: NotificationPreferences,
    preview: WellbeingDigestPreview,
    correlationId: string,
  ): Promise<number> {
    const claim = await this.repository.claim(
      ownerId,
      preview.localDate,
      preview.timeZone,
      'DAILY_DIGEST',
      {
        resources: preview.resourceItems.length,
        journalPrompt: preview.includeJournalPrompt ? 1 : 0,
        emotionPrompt: preview.includeEmotionPrompt ? 1 : 0,
      },
    );
    if (!claim) return 0;
    const lines = [
      ...preview.resourceItems.map((item) => `Tài nguyên: ${item.title}`),
      ...(preview.includeJournalPrompt
        ? ['Nhật ký: dành vài phút ghi lại điều bạn đang nghĩ.']
        : []),
      ...(preview.includeEmotionPrompt ? ['Cảm xúc: ghi nhận ngắn cảm xúc hôm nay.'] : []),
    ];
    try {
      const email = await this.recipients.getEmail(ownerId, correlationId);
      const result = await this.delivery.send({
        recipientEmail: email,
        subject: 'Những bước nhỏ dành cho bạn hôm nay',
        text: `MentalBridge đã tổng hợp các việc đang chờ của bạn:\n\n${lines.map((line) => `• ${line}`).join('\n')}\n\nBạn có thể làm theo nhịp phù hợp với mình.`,
        html: `<h1>Những bước nhỏ dành cho bạn hôm nay</h1><p>MentalBridge đã tổng hợp các việc đang chờ của bạn:</p><ul>${lines.map((line) => `<li>${escapeHtml(line)}</li>`).join('')}</ul><p>Bạn có thể làm theo nhịp phù hợp với mình.</p>`,
      });
      await this.repository.delivered(claim.id, result.providerMessageId);
      return 1;
    } catch (error) {
      if (error instanceof WellbeingRecipientUnavailableError) {
        await this.repository.cancelled(claim.id, 'RECIPIENT_INELIGIBLE');
        return 0;
      }
      await this.repository.failed(claim.id, 'PROVIDER_OR_CONTACT_UNAVAILABLE');
      throw new Error('Wellbeing digest delivery failed');
    }
  }

  private async sendResourceReminder(
    ownerId: string,
    preferences: NotificationPreferences,
    preview: WellbeingDigestPreview,
    correlationId: string,
  ): Promise<number> {
    const resource = preview.resourceItems[0];
    const claim = await this.repository.claim(
      ownerId,
      preview.localDate,
      preview.timeZone,
      'RESOURCE_REMINDER',
      { resources: 1 },
    );
    if (!claim) return 0;
    try {
      const email = await this.recipients.getEmail(ownerId, correlationId);
      const result = await this.delivery.send({
        recipientEmail: email,
        subject: 'Một tài nguyên đang chờ bạn',
        text: `${resource.title}\n\nMở MentalBridge khi bạn sẵn sàng. Email này chỉ được gửi một lần trong ngày.`,
        html: `<h1>Một tài nguyên đang chờ bạn</h1><p>${escapeHtml(resource.title)}</p><p>Mở MentalBridge khi bạn sẵn sàng. Email này chỉ được gửi một lần trong ngày.</p>`,
      });
      await this.repository.delivered(claim.id, result.providerMessageId);
      return 1;
    } catch (error) {
      if (error instanceof WellbeingRecipientUnavailableError) {
        await this.repository.cancelled(claim.id, 'RECIPIENT_INELIGIBLE');
        return 0;
      }
      await this.repository.failed(claim.id, 'PROVIDER_OR_CONTACT_UNAVAILABLE');
      throw new Error('Resource reminder delivery failed');
    }
  }
}
