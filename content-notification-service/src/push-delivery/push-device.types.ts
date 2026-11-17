import type { NotificationActionType } from '../notifications/notification.types.js';

export type PushPlatform = 'ANDROID' | 'IOS';
export type PushPermissionStatus = 'GRANTED' | 'DENIED';
export type PushDeviceStatus = 'ACTIVE' | 'DISABLED' | 'REVOKED' | 'INVALID';

export interface PushDeviceRegistrationInput {
  readonly installationId: string;
  readonly platform: PushPlatform;
  readonly provider: 'EXPO';
  readonly providerToken: string;
  readonly permissionStatus: PushPermissionStatus;
  readonly appVersion?: string;
}

export interface PushDeviceRegistration {
  readonly installationId: string;
  readonly platform: PushPlatform;
  readonly provider: 'EXPO';
  readonly permissionStatus: PushPermissionStatus;
  readonly status: PushDeviceStatus;
  readonly appVersion: string | null;
  readonly lastRegisteredAt: string;
  readonly version: number;
}

export interface PushDeliveryClaim {
  readonly id: string;
  readonly deviceId: string;
  readonly tokenCiphertext: string;
  readonly tokenIv: string;
  readonly tokenAuthTag: string;
  readonly title: string;
  readonly body: string;
  readonly kind: string;
  readonly ownerId: string;
  readonly timeZone: string;
  readonly dailyReminderTime: string;
  readonly sourceIdentity: string;
  readonly notificationId: string;
  readonly actionType: NotificationActionType | null;
  readonly actionTargetId: string | null;
  readonly attemptCount: number;
  readonly appointmentReminder: Readonly<{
    recipientId: string;
    appointmentId: string;
    appointmentVersion: number;
    modality: 'IN_APP_CHAT' | 'IN_APP_VIDEO';
    scheduledStartAt: Date;
  }> | null;
}

export interface PushReceiptClaim {
  readonly id: string;
  readonly deviceId: string;
  readonly providerTicketId: string;
  readonly receiptAttemptCount: number;
}
