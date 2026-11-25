import type { NotificationPreferences } from '../notification-preferences/notification-preference.types.js';

export type ReminderKind =
  | 'JOURNAL_REMINDER'
  | 'EMOTION_CHECKIN_REMINDER'
  | 'DAILY_DIGEST'
  | 'RESOURCE_REMINDER'
  | 'APPOINTMENT_EMAIL';
export type ReminderDeliveryState =
  | 'PENDING'
  | 'PROCESSING'
  | 'CLAIMED'
  | 'DELIVERED'
  | 'FAILED'
  | 'CANCELLED'
  | 'INVALIDATED'
  | 'SUPPRESSED'
  | 'EXPIRED'
  | 'UNKNOWN';
export interface ReminderDeliveryFact {
  readonly state: ReminderDeliveryState;
  readonly recordedAt: string;
}
export interface ReminderPreview {
  readonly kind: ReminderKind;
  readonly channel: 'IN_APP' | 'EMAIL';
  readonly state:
    | 'ELIGIBLE'
    | 'SCHEDULED'
    | 'QUIET_HOURS'
    | 'PAUSED'
    | 'DISABLED'
    | 'COMPLETED'
    | 'NOT_NEEDED'
    | 'ALREADY_DELIVERED'
    | 'IN_PROGRESS'
    | 'UNAVAILABLE';
  readonly nextEligibleAt: string | null;
  readonly estimate: true;
  readonly latestDelivery: ReminderDeliveryFact | null;
}
export interface ReminderOverview {
  readonly schemaVersion: '1.0';
  readonly asOf: string;
  readonly localDate: string;
  readonly timeZone: string;
  readonly preferences: NotificationPreferences;
  readonly today: readonly {
    readonly id: string;
    readonly category: 'JOURNAL' | 'EMOTION_CHECK_IN';
    readonly status: 'DUE' | 'COMPLETED';
    readonly action: 'OPEN_JOURNAL' | 'OPEN_EMOTION_CHECK_IN';
  }[];
  readonly previews: readonly ReminderPreview[];
  readonly unavailableCategories: readonly ('SUPPORT_PLAN' | 'RESOURCES' | 'ASSESSMENT' | 'PUSH')[];
}
