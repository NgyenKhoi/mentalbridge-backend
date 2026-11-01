import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { describe, expect, it, vi } from 'vitest';
import {
  CommunityNoticeConsumer,
  CommunityChatFactSchema,
  CommunityCommentReactionSchema,
  COMMUNITY_CHAT_TOPIC,
  COMMUNITY_COMMENT_REACTION_TOPIC,
} from '../community-notifications/community-notice.consumer.js';
import {
  CommunityNotificationJobs,
  CommunityNoticeIdentityConflict,
} from '../community-notifications/community-notification.jobs.js';
import { communityPushPayload } from '../community-notifications/community-push.js';
import type {
  CommunityNotificationProjection,
  CommunityInteractionDeadLetter,
} from '../community-notifications/community-interaction.types.js';

export function chatFact(kind = 'MESSAGE_RECEIVED') {
  const conversation = randomUUID();
  return {
    messageId: randomUUID(),
    messageType: 'community.chat.fact',
    schemaVersion: '1.0',
    producer: 'community-service',
    occurredAt: new Date().toISOString(),
    correlationId: randomUUID(),
    aggregateId: conversation,
    aggregateVersion: '1',
    payload: {
      kind,
      conversationId: conversation,
      targetId: randomUUID(),
      actorCommunityProfileId: randomUUID(),
      recipientRoutingReference: randomUUID(),
    },
  };
}
export function commentReaction() {
  return {
    eventId: randomUUID(),
    schemaVersion: '1.0',
    actorCommunityProfileId: randomUUID(),
    targetOwnerRoutingReference: randomUUID(),
    targetType: 'COMMENT',
    targetId: randomUUID(),
    interactionKind: 'SUPPORT',
    occurredAt: new Date().toISOString(),
    deepLink: { kind: 'COMMUNITY_POST', postId: randomUUID() },
  };
}

describe('Community notice transport and privacy contract', () => {
  function fixture() {
    const enqueue = vi.fn().mockResolvedValue(undefined);
    const parked: CommunityInteractionDeadLetter[] = [];
    const consumer = new CommunityNoticeConsumer(
      { CONTENT_COMMUNITY_NOTICE_CONSUMER_ENABLED: false } as never,
      { enqueue } as unknown as CommunityNotificationJobs,
      {
        publish: async (item) => {
          parked.push(item);
        },
      },
    );
    return { consumer, enqueue, parked };
  }
  it.each(['MESSAGE_RECEIVED', 'DIRECT_REQUESTED', 'GROUP_INVITED'])(
    'maps %s to a generic owner-bound notice and logical target identity',
    async (kind) => {
      const { consumer, enqueue } = fixture();
      const event = chatFact(kind);
      await consumer.handle(
        COMMUNITY_CHAT_TOPIC,
        0,
        '1',
        Buffer.from(JSON.stringify(event)),
        Buffer.from(event.aggregateId),
      );
      const [projection, fingerprint] = enqueue.mock.calls[0] as [
        CommunityNotificationProjection,
        string,
      ];
      expect(projection.ownerId).toBe(event.payload.recipientRoutingReference);
      expect(projection.authorization).toEqual(event.payload);
      expect(projection.sourceIdentity).toBe(kind + ':' + event.payload.targetId);
      expect(projection.body).toMatch(/^Bạn có /);
      expect(projection).not.toHaveProperty('content');
      expect(fingerprint).toMatch(/^[0-9a-f]{64}$/);
      await consumer.handle(
        COMMUNITY_CHAT_TOPIC,
        0,
        '2',
        Buffer.from(JSON.stringify({ ...event, messageId: randomUUID() })),
        Buffer.from(event.aggregateId),
      );
      expect(enqueue.mock.calls[1]?.[1]).toBe(fingerprint);
    },
  );
  it('ignores valid transport-only facts without creating a notification', async () => {
    const { consumer, enqueue } = fixture();
    const value = chatFact();
    for (const payload of [
      {
        kind: 'MESSAGE_SAVED',
        conversationId: value.aggregateId,
        targetId: randomUUID(),
        sequence: '2',
      },
      { kind: 'AUTHORIZATION_CHANGED', conversationId: value.aggregateId },
      {
        kind: 'READ_STATE_CHANGED',
        conversationId: value.aggregateId,
        recipientRoutingReference: randomUUID(),
      },
    ])
      await consumer.handle(
        COMMUNITY_CHAT_TOPIC,
        0,
        '1',
        Buffer.from(JSON.stringify({ ...value, payload })),
        Buffer.from(value.aggregateId),
      );
    expect(enqueue).not.toHaveBeenCalled();
  });
  it('rejects a wrong aggregate/key, extra body fields and incompatible variants without logging payloads', async () => {
    const { consumer, enqueue, parked } = fixture();
    const event = chatFact();
    expect(CommunityChatFactSchema.safeParse({ ...event, aggregateId: randomUUID() }).success).toBe(
      false,
    );
    for (const value of [
      { ...event, content: 'synthetic-private-body' },
      { ...event, schemaVersion: '2.0' },
    ])
      await consumer.handle(
        COMMUNITY_CHAT_TOPIC,
        1,
        '9',
        Buffer.from(JSON.stringify(value)),
        Buffer.from(event.aggregateId),
      );
    await consumer.handle(
      COMMUNITY_CHAT_TOPIC,
      1,
      '10',
      Buffer.from(JSON.stringify(event)),
      Buffer.from(randomUUID()),
    );
    expect(enqueue).not.toHaveBeenCalled();
    expect(parked).toHaveLength(3);
    expect(JSON.stringify(parked)).not.toContain('synthetic-private-body');
  });
  it('accepts comment reaction on its separate contract without widening legacy interaction consumption', async () => {
    const { consumer, enqueue } = fixture();
    const event = commentReaction();
    expect(CommunityCommentReactionSchema.safeParse({ ...event, targetType: 'POST' }).success).toBe(
      false,
    );
    await consumer.handle(
      COMMUNITY_COMMENT_REACTION_TOPIC,
      0,
      '1',
      Buffer.from(JSON.stringify(event)),
      Buffer.from(event.targetId),
    );
    expect(enqueue.mock.calls[0]?.[0]).toMatchObject({
      ownerId: event.targetOwnerRoutingReference,
      groupingKey: 'COMMENT:' + event.targetId,
      sourceIdentity: event.targetId + ':' + event.actorCommunityProfileId,
      postId: event.deepLink.postId,
    });
  });
  it('retains a retryable offset on database failure and parks changed identity reuse', async () => {
    const { consumer, enqueue, parked } = fixture();
    const event = chatFact();
    enqueue.mockRejectedValueOnce(new Error('synthetic DB failure'));
    await expect(
      consumer.handle(
        COMMUNITY_CHAT_TOPIC,
        0,
        '1',
        Buffer.from(JSON.stringify(event)),
        Buffer.from(event.aggregateId),
      ),
    ).rejects.toThrow('synthetic DB failure');
    enqueue.mockRejectedValueOnce(new CommunityNoticeIdentityConflict());
    await consumer.handle(
      COMMUNITY_CHAT_TOPIC,
      0,
      '1',
      Buffer.from(JSON.stringify(event)),
      Buffer.from(event.aggregateId),
    );
    expect(parked).toHaveLength(1);
  });
  it('builds exactly the generic PUSH payload contract without activating a provider', async () => {
    const contract = JSON.parse(
      await readFile(
        new URL(
          '../../../contracts/events/community/community-push-v1.schema.json',
          import.meta.url,
        ),
        'utf8',
      ),
    ) as {
      additionalProperties: boolean;
      required: string[];
      properties: { body: { const: string } };
    };
    const payload = communityPushPayload(randomUUID());
    expect(Object.keys(payload).sort()).toEqual(contract.required.sort());
    expect(payload.body).toBe(contract.properties.body.const);
    expect(contract.additionalProperties).toBe(false);
    expect(() => communityPushPayload('sender-name-or-private-text')).toThrow();
  });
});
