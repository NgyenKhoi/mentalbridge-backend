export const NOTIFICATION_KINDS = [
  'REMINDER',
  'MESSAGE',
  'APPOINTMENT',
  'SYSTEM_RESOURCE',
  'ASSESSMENT_REASSESSMENT',
  'STREAK_MILESTONE',
] as const;

export type NotificationKind = (typeof NOTIFICATION_KINDS)[number];
export type NotificationPriority = 'LOW' | 'NORMAL' | 'HIGH';
export type NotificationActionType =
  | 'OPEN_JOURNAL'
  | 'OPEN_MESSAGES'
  | 'OPEN_APPOINTMENTS'
  | 'OPEN_RESOURCES'
  | 'OPEN_ASSESSMENTS'
  | 'OPEN_RESOURCE';

export interface NotificationAction {
  readonly type: NotificationActionType;
  readonly targetId: string | null;
  readonly href: string;
}

export interface NotificationItem {
  readonly id: string;
  readonly kind: NotificationKind;
  readonly title: string;
  readonly body: string;
  readonly priority: NotificationPriority;
  readonly occurredAt: string;
  readonly createdAt: string;
  readonly read: boolean;
  readonly readAt: string | null;
  readonly action: NotificationAction | null;
  readonly lifecycleState: 'ACTIVE';
  readonly expiresAt: string;
}

export interface NotificationPage {
  readonly items: readonly NotificationItem[];
  readonly nextCursor: string | null;
  readonly hasMore: boolean;
  readonly unreadCount: number;
}

export interface NotificationCursor {
  readonly createdAt: string;
  readonly id: string;
}

export interface NotificationCreate {
  readonly ownerId: string;
  readonly kind: NotificationKind;
  readonly title: string;
  readonly body: string;
  readonly occurredAt: string;
  readonly action?: Readonly<{
    type: NotificationActionType;
    targetId?: string;
  }>;
  readonly source: string;
  readonly sourceIdentity: string;
  readonly priority: NotificationPriority;
  readonly expiresAt?: string;
}

export interface NotificationRow {
  readonly id: string;
  readonly recipient_id: string;
  readonly category: string;
  readonly title: string;
  readonly body: string;
  readonly action_type: NotificationActionType | null;
  readonly action_target_id: string | null;
  readonly priority: NotificationPriority;
  readonly read_at: Date | string | null;
  readonly expires_at: Date | string;
  readonly occurred_at: Date | string;
  readonly created_at: Date | string;
  readonly source: string;
  readonly source_identity: string;
  readonly request_fingerprint: string;
  readonly delivery_state: string;
  readonly deleted_at: Date | string | null;
  readonly version: string | number;
}
