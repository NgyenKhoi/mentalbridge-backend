import type { NotificationPreferences } from '../notification-preferences/notification-preference.types.js';

export type WellbeingEmailKind = 'DAILY_DIGEST' | 'RESOURCE_REMINDER';

export interface WellbeingDigestPreview {
  readonly localDate: string;
  readonly timeZone: string;
  readonly scheduledTime: string;
  readonly eligibleNow: boolean;
  readonly resourceItems: readonly { id: string; title: string }[];
  readonly includeJournalPrompt: boolean;
  readonly includeEmotionPrompt: boolean;
  readonly empty: boolean;
}

export interface WellbeingEmailCandidate {
  readonly ownerId: string;
  readonly preferences: NotificationPreferences;
}

export interface DeliveryClaim {
  readonly id: string;
  readonly kind: WellbeingEmailKind;
  readonly localDate: string;
}

export interface EmailMessage {
  readonly recipientEmail: string;
  readonly subject: string;
  readonly text: string;
  readonly html: string;
}

export interface WellbeingEmailDelivery {
  send(message: EmailMessage): Promise<{ providerMessageId: string | null }>;
}

export interface WellbeingRecipientClient {
  getEmail(ownerId: string, correlationId: string): Promise<string>;
}

export class WellbeingRecipientUnavailableError extends Error {
  constructor() {
    super('Wellbeing email recipient is unavailable');
    this.name = 'WellbeingRecipientUnavailableError';
  }
}
