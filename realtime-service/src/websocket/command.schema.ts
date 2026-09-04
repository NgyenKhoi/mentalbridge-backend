import { z } from 'zod';

const envelope = {
  schemaVersion: z.literal(1),
  commandId: z.uuid(),
  correlationId: z.string().regex(/^[A-Za-z0-9._:-]{1,128}$/),
  sentAt: z.iso.datetime(),
};

export const commandSchema = z.discriminatedUnion('commandType', [
  z
    .object({
      ...envelope,
      commandType: z.literal('presence.heartbeat'),
      payload: z.object({}).strict(),
    })
    .strict(),
  z
    .object({
      ...envelope,
      commandType: z.literal('conversation.subscribe'),
      payload: z.object({ conversationId: z.uuid() }).strict(),
    })
    .strict(),
  z
    .object({
      ...envelope,
      commandType: z.literal('message.send'),
      payload: z
        .object({
          conversationId: z.uuid(),
          clientMessageId: z.uuid(),
          type: z.literal('TEXT'),
          content: z.string().min(1).max(4000),
        })
        .strict(),
    })
    .strict(),
]);

export type RealtimeCommand = z.infer<typeof commandSchema>;
