import { z } from 'zod';

export const NOTIFICATION_TOPIC = 'mentalbridge.content.notification-created.v1';
export const NOTIFICATION_RETRY_TOPIC = 'mentalbridge.realtime.specialist-notification-retry.v1';
export const NOTIFICATION_DEAD_LETTER_TOPIC =
  'mentalbridge.realtime.specialist-notification-dead-letter.v1';
export const NOTIFICATION_GROUP = 'realtime-specialist-notifications-v1';
export const NOTIFICATION_CHANNEL = 'mentalbridge:realtime:specialist-notifications:v1';

export const notificationCreatedSchema = z
  .object({
    messageId: z.uuid(),
    messageType: z.literal('content.notification.created'),
    schemaVersion: z.literal('1.0'),
    producer: z.literal('content-notification-service'),
    correlationId: z.uuid(),
    causationId: z.uuid(),
    occurredAt: z.iso.datetime({ offset: true }),
    aggregateId: z.uuid(),
    aggregateVersion: z.number().int().nonnegative(),
    payload: z
      .object({
        notificationId: z.uuid(),
        recipientAccountId: z.uuid(),
        recipientRole: z.literal('SPECIALIST'),
        expiresAt: z.iso.datetime({ offset: true }),
      })
      .strict(),
  })
  .strict();

export const notificationPointerSchema = z
  .object({
    schemaVersion: z.literal(1),
    eventId: z.uuid(),
    correlationId: z.uuid(),
    occurredAt: z.iso.datetime({ offset: true }),
    notificationId: z.uuid(),
    recipientAccountId: z.uuid(),
    expiresAt: z.iso.datetime({ offset: true }),
  })
  .strict();

export const notificationServerEventSchema = z
  .object({
    schemaVersion: z.literal(1),
    eventId: z.uuid(),
    eventType: z.literal('notification.created'),
    correlationId: z.uuid(),
    occurredAt: z.iso.datetime({ offset: true }),
    payload: z.object({ notificationId: z.uuid() }).strict(),
  })
  .strict();

export type NotificationCreated = z.infer<typeof notificationCreatedSchema>;
export type NotificationPointer = z.infer<typeof notificationPointerSchema>;
export type NotificationServerEvent = z.infer<typeof notificationServerEventSchema>;

export const toNotificationPointer = (event: NotificationCreated): NotificationPointer => ({
  schemaVersion: 1,
  eventId: event.messageId,
  correlationId: event.correlationId,
  occurredAt: event.occurredAt,
  notificationId: event.payload.notificationId,
  recipientAccountId: event.payload.recipientAccountId,
  expiresAt: event.payload.expiresAt,
});

export const toNotificationServerEvent = (
  pointer: NotificationPointer,
): NotificationServerEvent => ({
  schemaVersion: 1,
  eventId: pointer.eventId,
  eventType: 'notification.created',
  correlationId: pointer.correlationId,
  occurredAt: pointer.occurredAt,
  payload: { notificationId: pointer.notificationId },
});
