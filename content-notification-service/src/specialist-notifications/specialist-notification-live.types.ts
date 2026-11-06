export const LIVE_NOTIFICATION_TOPIC = 'mentalbridge.content.notification-created.v1';

export interface SpecialistNotificationLiveClaim {
  readonly id: string;
  readonly notification_id: string;
  readonly recipient_account_id: string;
  readonly aggregate_version: string | number;
  readonly correlation_id: string;
  readonly causation_id: string;
  readonly occurred_at: Date | string;
  readonly expires_at: Date | string;
  readonly claim_token: string;
  readonly attempt_count: number;
}

export function toNotificationCreatedEvent(row: SpecialistNotificationLiveClaim) {
  return {
    messageId: row.id,
    messageType: 'content.notification.created' as const,
    schemaVersion: '1.0' as const,
    producer: 'content-notification-service' as const,
    correlationId: row.correlation_id,
    causationId: row.causation_id,
    occurredAt: new Date(row.occurred_at).toISOString(),
    aggregateId: row.notification_id,
    aggregateVersion: Number(row.aggregate_version),
    payload: {
      notificationId: row.notification_id,
      recipientAccountId: row.recipient_account_id,
      recipientRole: 'SPECIALIST' as const,
      expiresAt: new Date(row.expires_at).toISOString(),
    },
  };
}
