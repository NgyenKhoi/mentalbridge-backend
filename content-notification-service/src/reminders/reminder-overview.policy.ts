import type { NotificationPreferences } from '../notification-preferences/notification-preference.types.js';
import { isQuietHour } from './reminder-materialization.service.js';

export function reminderLocalDate(now: Date, timeZone: string): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(now);
  const part = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((value) => value.type === type)?.value ?? '';
  return `${part('year')}-${part('month')}-${part('day')}`;
}

export function nextReminderEligibility(
  preferences: NotificationPreferences,
  now: Date,
  notBefore = '00:00',
): string | null {
  const timeZone = preferences.quietHours.timeZone;
  const date = reminderLocalDate(now, timeZone);
  const formatter = new Intl.DateTimeFormat('en-GB', {
    timeZone,
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  });
  const limit = now.getTime() + 26 * 60 * 60_000;
  for (
    let instant = now.getTime();
    instant <= limit;
    instant = Math.floor(instant / 60_000) * 60_000 + 60_000
  ) {
    const candidate = new Date(instant);
    if (reminderLocalDate(candidate, timeZone) !== date) return null;
    if (formatter.format(candidate) >= notBefore && !isQuietHour(preferences, candidate))
      return candidate.toISOString();
  }
  return null;
}
