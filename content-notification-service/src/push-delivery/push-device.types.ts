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
  readonly notificationId: string;
  readonly actionType: string | null;
  readonly actionTargetId: string | null;
  readonly attemptCount: number;
}
