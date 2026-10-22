import { z } from 'zod';

const uuid = z.uuid();
const peer = z.discriminatedUnion('state', [
  z
    .object({
      state: z.literal('AVAILABLE'),
      communityProfileId: uuid,
      displayName: z
        .string()
        .min(1)
        .refine((value) => Array.from(value).length <= 80),
    })
    .strict(),
  z.object({ state: z.literal('UNAVAILABLE') }).strict(),
]);
const messageIdentity = {
  messageId: uuid,
  conversationId: uuid,
  clientMessageId: uuid,
  sender: peer,
  createdAt: z.iso.datetime({ offset: true }),
};
export const messageSchema = z.discriminatedUnion('state', [
  z
    .object({
      ...messageIdentity,
      state: z.literal('ACTIVE'),
      content: z
        .string()
        .min(1)
        .refine((value) => Array.from(value).length <= 4000),
    })
    .strict(),
  z
    .object({
      ...messageIdentity,
      state: z.literal('RETRACTED'),
      retractedAt: z.iso.datetime({ offset: true }),
    })
    .strict(),
]);
export const readStateSchema = z
  .object({ lastReadMessageId: uuid.nullable(), unreadCount: z.number().int().nonnegative() })
  .strict();
export const replaySchema = z
  .object({
    messageIds: z.array(uuid).max(50),
    cursor: z.string().min(1).max(1024),
    hasMore: z.boolean(),
    readState: readStateSchema,
  })
  .strict()
  .refine((value) => new Set(value.messageIds).size === value.messageIds.length);
const commandHeader = {
  schemaVersion: z.literal(1),
  commandId: uuid,
  correlationId: uuid,
};
export const commandSchema = z.discriminatedUnion('commandType', [
  z
    .object({
      ...commandHeader,
      commandType: z.literal('community.conversation.subscribe'),
      payload: z.object({ conversationId: uuid }).strict(),
    })
    .strict(),
  z
    .object({
      ...commandHeader,
      commandType: z.literal('community.presence.heartbeat'),
      payload: z.object({ foreground: z.boolean() }).strict(),
    })
    .strict(),
]);
export const presencePeersSchema = z
  .object({
    peers: z
      .array(z.object({ communityProfileId: uuid, subjectRoutingReference: uuid }).strict())
      .max(30),
  })
  .strict()
  .refine(
    ({ peers }) =>
      new Set(peers.map((peer) => peer.communityProfileId)).size === peers.length &&
      new Set(peers.map((peer) => peer.subjectRoutingReference)).size === peers.length,
  );
export type PresencePeer = z.infer<typeof presencePeersSchema>['peers'][number];
export const handshakeSchema = z
  .object({ schemaVersion: z.literal(1), credential: z.string().min(32).max(512) })
  .strict();
const decimal = z.string().regex(/^(0|[1-9][0-9]{0,18})$/);
const header = {
  messageId: uuid,
  messageType: z.literal('community.chat.fact'),
  schemaVersion: z.literal('1.0'),
  producer: z.literal('community-service'),
  occurredAt: z.iso.datetime({ offset: true }),
  correlationId: uuid,
  aggregateId: uuid,
  aggregateVersion: decimal,
};
export const factSchema = z
  .union([
    z
      .object({
        ...header,
        payload: z
          .object({
            kind: z.literal('MESSAGE_SAVED'),
            conversationId: uuid,
            targetId: uuid,
            sequence: decimal,
          })
          .strict(),
      })
      .strict(),
    z
      .object({
        ...header,
        payload: z
          .object({
            kind: z.literal('MESSAGE_RETRACTED'),
            conversationId: uuid,
            targetId: uuid,
          })
          .strict(),
      })
      .strict(),
    z
      .object({
        ...header,
        payload: z
          .object({ kind: z.literal('AUTHORIZATION_CHANGED'), conversationId: uuid })
          .strict(),
      })
      .strict(),
    z
      .object({
        ...header,
        payload: z
          .object({
            kind: z.literal('READ_STATE_CHANGED'),
            conversationId: uuid,
            recipientRoutingReference: uuid,
          })
          .strict(),
      })
      .strict(),
    z
      .object({
        ...header,
        payload: z
          .object({
            kind: z.enum(['MESSAGE_RECEIVED', 'DIRECT_REQUESTED', 'GROUP_INVITED']),
            conversationId: uuid,
            targetId: uuid,
            actorCommunityProfileId: uuid,
            recipientRoutingReference: uuid,
          })
          .strict(),
      })
      .strict(),
  ])
  .refine((value) => value.aggregateId === value.payload.conversationId);
export type CommunityFact = z.infer<typeof factSchema>;
export type CommunityMessage = z.infer<typeof messageSchema>;
export type ReadState = z.infer<typeof readStateSchema>;
export type ReplayProjection = z.infer<typeof replaySchema>;
export type CommunityCommand = z.infer<typeof commandSchema>;
export type CommunityErrorCode =
  | 'INVALID_COMMAND'
  | 'UNAUTHENTICATED'
  | 'TARGET_UNAVAILABLE'
  | 'RATE_LIMITED'
  | 'DEPENDENCY_UNAVAILABLE';
export class CommunityError extends Error {
  constructor(readonly code: CommunityErrorCode) {
    super(code);
  }
}
