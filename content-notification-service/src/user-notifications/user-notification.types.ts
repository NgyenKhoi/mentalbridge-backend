import { z } from 'zod';

const EnvelopeSchema = z.object({
  messageId: z.uuid(),
  occurredAt: z.iso.datetime({ offset: true }),
  schemaVersion: z.literal('1.0'),
  correlationId: z.uuid(),
  aggregateId: z.uuid(),
  aggregateVersion: z.number().int().nonnegative(),
});

export const CARE_USER_NOTIFICATION_TOPIC = 'mentalbridge.care.user-notification.v1';
export const CARE_USER_NOTIFICATION_DEAD_LETTER_TOPIC =
  'mentalbridge.content-notification.care-user-notification-dead-letter.v1';

export const CareUserNotificationFactSchema = EnvelopeSchema.extend({
  messageType: z.literal('care.user-notification.fact'),
  producer: z.literal('care-service'),
  payload: z
    .object({
      recipientId: z.uuid(),
      factType: z.enum(['ASSESSMENT_RESULT_AVAILABLE', 'SUPPORT_GUIDE_AVAILABLE']),
      targetId: z.uuid(),
      targetVersion: z.number().int().nonnegative(),
      availableAt: z.iso.datetime({ offset: true }),
    })
    .strict(),
})
  .strict()
  .superRefine((event, context) => {
    if (event.aggregateId !== event.payload.targetId) {
      context.addIssue({ code: 'custom', message: 'aggregateId must match payload.targetId' });
    }
    if (event.aggregateVersion !== event.payload.targetVersion) {
      context.addIssue({ code: 'custom', message: 'aggregateVersion must match targetVersion' });
    }
    if (event.occurredAt !== event.payload.availableAt) {
      context.addIssue({ code: 'custom', message: 'occurredAt must match availableAt' });
    }
  });

export const IdentityUserNotificationFactSchema = EnvelopeSchema.extend({
  messageType: z.literal('identity.user-notification.fact'),
  producer: z.literal('identity-service'),
  aggregateVersion: z.number().int().positive(),
  payload: z
    .object({
      recipientId: z.uuid(),
      actorType: z.enum(['USER', 'SPECIALIST']),
      factType: z.enum(['ACCOUNT_ACTIVATED', 'ACCOUNT_RESTORED']),
      availableAt: z.iso.datetime({ offset: true }),
    })
    .strict(),
})
  .strict()
  .superRefine((event, context) => {
    if (event.aggregateId !== event.payload.recipientId) {
      context.addIssue({ code: 'custom', message: 'aggregateId must match recipientId' });
    }
    if (event.occurredAt !== event.payload.availableAt) {
      context.addIssue({ code: 'custom', message: 'occurredAt must match availableAt' });
    }
  });

export type CareUserNotificationFact = z.infer<typeof CareUserNotificationFactSchema>;
export type IdentityUserNotificationFact = z.infer<typeof IdentityUserNotificationFactSchema>;

export type UserNotificationProjection = Readonly<{
  sourceIdentity: string;
  ownerId: string;
  kind: 'ASSESSMENT_RESULT_AVAILABLE' | 'SUPPORT_GUIDE_AVAILABLE' | 'ACCOUNT_STATUS';
  title: string;
  body: string;
  occurredAt: string;
  preferenceGroup: 'SCREENING_REASSESSMENT' | 'RESOURCE_SYSTEM';
  source: 'CARE_USER_NOTIFICATION_V1' | 'IDENTITY_USER_NOTIFICATION_V1';
  actionType: 'OPEN_ASSESSMENTS' | 'OPEN_SUPPORT_GUIDE' | null;
  actionTargetId: string | null;
}>;

export type UserNotificationDeadLetter = Readonly<{
  schemaVersion: '1.0';
  failedAt: string;
  sourceTopic: string;
  sourcePartition: number;
  sourceOffset: string;
  sourceMessageDigest: string;
  errorCode: 'INVALID_JSON' | 'INVALID_EVENT';
}>;

export interface UserNotificationDeadLetterPublisher {
  publish(deadLetter: UserNotificationDeadLetter): Promise<void>;
}
