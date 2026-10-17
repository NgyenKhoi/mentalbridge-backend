import { z } from 'zod';

export const COMMUNITY_INTERACTION_TOPIC = 'mentalbridge.community.interaction.v1';
export const COMMUNITY_INTERACTION_DEAD_LETTER_TOPIC =
  'mentalbridge.content-notification.community-interaction-dead-letter.v1';

export const CommunityInteractionEventSchema = z
  .object({
    eventId: z.uuid(),
    schemaVersion: z.literal('1.0'),
    actorCommunityProfileId: z.uuid(),
    targetOwnerRoutingReference: z.uuid(),
    targetType: z.enum(['POST', 'COMMENT']),
    targetId: z.uuid(),
    interactionKind: z.enum(['COMMENT', 'REPLY', 'SUPPORT', 'RELATE', 'THANK_YOU']),
    occurredAt: z.iso.datetime({ offset: true }),
    deepLink: z
      .object({
        kind: z.literal('COMMUNITY_POST'),
        postId: z.uuid(),
      })
      .strict(),
  })
  .strict()
  .superRefine((event, context) => {
    const postTarget = event.targetType === 'POST' && event.targetId === event.deepLink.postId;
    if (event.interactionKind === 'COMMENT' && !postTarget) {
      context.addIssue({ code: 'custom', message: 'COMMENT must target its deep-linked post' });
    }
    if (event.interactionKind === 'REPLY' && event.targetType !== 'COMMENT') {
      context.addIssue({ code: 'custom', message: 'REPLY must target a parent comment' });
    }
    if (['SUPPORT', 'RELATE', 'THANK_YOU'].includes(event.interactionKind) && !postTarget) {
      context.addIssue({ code: 'custom', message: 'Reaction must target its deep-linked post' });
    }
  });

export type CommunityInteractionEvent = z.infer<typeof CommunityInteractionEventSchema>;

export type CommunityNotificationProjection = Readonly<{
  eventId: string;
  ownerId: string;
  kind: 'COMMUNITY_COMMENT' | 'COMMUNITY_REPLY' | 'COMMUNITY_REACTION';
  title: string;
  body: string;
  occurredAt: string;
  postId: string;
}>;

export type CommunityNotificationMaterialization = Readonly<{
  notificationId: string;
  delivered: boolean;
  replayed: boolean;
}>;

export type CommunityInteractionDeadLetter = Readonly<{
  schemaVersion: '1.0';
  failedAt: string;
  sourceTopic: string;
  sourcePartition: number;
  sourceOffset: string;
  sourceMessageDigest: string;
  errorCode: 'INVALID_JSON' | 'INVALID_EVENT';
}>;

export interface CommunityInteractionDeadLetterPublisher {
  publish(deadLetter: CommunityInteractionDeadLetter): Promise<void>;
}
