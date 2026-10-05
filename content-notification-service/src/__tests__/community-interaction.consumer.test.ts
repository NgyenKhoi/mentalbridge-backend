import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { describe, expect, it, vi } from 'vitest';

import {
  CommunityInteractionConsumer,
  CommunityInteractionProjector,
} from '../community-notifications/community-interaction.consumer.js';
import type { CommunityNotificationRepository } from '../community-notifications/community-notification.repository.js';
import {
  COMMUNITY_INTERACTION_TOPIC,
  CommunityInteractionEventSchema,
  type CommunityInteractionDeadLetter,
  type CommunityInteractionDeadLetterPublisher,
  type CommunityInteractionEvent,
  type CommunityNotificationProjection,
} from '../community-notifications/community-interaction.types.js';

const event: CommunityInteractionEvent = {
  eventId: '10000000-0000-4000-8000-000000000001',
  schemaVersion: '1.0',
  actorCommunityProfileId: '10000000-0000-4000-8000-000000000002',
  targetOwnerRoutingReference: '10000000-0000-4000-8000-000000000003',
  targetType: 'POST',
  targetId: '10000000-0000-4000-8000-000000000004',
  interactionKind: 'COMMENT',
  occurredAt: '2026-10-05T08:00:00.000Z',
  deepLink: {
    kind: 'COMMUNITY_POST',
    postId: '10000000-0000-4000-8000-000000000004',
  },
};

describe('Community interaction notification consumer', () => {
  it('keeps its strict runtime schema aligned with the frozen Community v1 contract', async () => {
    const contract = JSON.parse(
      await readFile(
        fileURLToPath(
          new URL(
            '../../../contracts/events/community/community-interaction-v1.schema.json',
            import.meta.url,
          ),
        ),
        'utf8',
      ),
    ) as {
      required: string[];
      properties: Record<string, { enum?: string[]; const?: string }>;
    };

    expect(contract.required).toEqual(Object.keys(event));
    expect(contract.properties.schemaVersion.const).toBe('1.0');
    expect(contract.properties.interactionKind.enum).toEqual([
      'COMMENT',
      'REPLY',
      'SUPPORT',
      'RELATE',
      'THANK_YOU',
    ]);
    expect(CommunityInteractionEventSchema.parse(event)).toEqual(event);
    expect(() =>
      CommunityInteractionEventSchema.parse({ ...event, content: 'private body' }),
    ).toThrow();
  });

  it('projects generic copy and only approved routing metadata', async () => {
    const materialize = vi.fn().mockResolvedValue({
      notificationId: '20000000-0000-4000-8000-000000000001',
      delivered: true,
      replayed: false,
    });
    const projector = new CommunityInteractionProjector({
      materialize,
    } as unknown as CommunityNotificationRepository);

    await projector.project(event);

    const [projected, fingerprint] = materialize.mock.calls[0] as [
      CommunityNotificationProjection,
      string,
    ];
    expect(Object.keys(projected)).toEqual([
      'eventId',
      'ownerId',
      'kind',
      'title',
      'body',
      'occurredAt',
      'postId',
    ]);
    expect(projected).toMatchObject({
      eventId: event.eventId,
      ownerId: event.targetOwnerRoutingReference,
      kind: 'COMMUNITY_COMMENT',
      postId: event.deepLink.postId,
    });
    expect(JSON.stringify(projected)).not.toMatch(
      /actorCommunityProfileId|targetId|email|journal|assessment|supportPlan|media|private/i,
    );
    expect(fingerprint).toMatch(/^[0-9a-f]{64}$/);
  });

  it('parks malformed and incompatible messages without copying raw payloads', async () => {
    const parked: CommunityInteractionDeadLetter[] = [];
    const deadLetters: CommunityInteractionDeadLetterPublisher = {
      publish: async (item) => {
        parked.push(item);
      },
    };
    const projector = { project: vi.fn() } as unknown as CommunityInteractionProjector;
    const consumer = new CommunityInteractionConsumer(
      { CONTENT_COMMUNITY_INTERACTION_CONSUMER_ENABLED: false } as never,
      projector,
      deadLetters,
    );

    await consumer.handle(COMMUNITY_INTERACTION_TOPIC, 2, '10', Buffer.from('{raw-private-body'));
    await consumer.handle(
      COMMUNITY_INTERACTION_TOPIC,
      2,
      '11',
      Buffer.from(JSON.stringify({ ...event, schemaVersion: '2.0' })),
    );

    expect(parked.map((item) => item.errorCode)).toEqual(['INVALID_JSON', 'INVALID_EVENT']);
    expect(parked[0]).toEqual(
      expect.objectContaining({
        sourceTopic: COMMUNITY_INTERACTION_TOPIC,
        sourcePartition: 2,
        sourceOffset: '10',
        sourceMessageDigest: expect.stringMatching(/^[0-9a-f]{64}$/),
      }),
    );
    expect(JSON.stringify(parked)).not.toContain('raw-private-body');
    expect(projector.project).not.toHaveBeenCalled();
  });

  it('retries a transient projection failure and then lets the Kafka offset complete', async () => {
    const project = vi
      .fn()
      .mockRejectedValueOnce(new Error('database unavailable'))
      .mockResolvedValue(undefined);
    const consumer = new CommunityInteractionConsumer(
      { CONTENT_COMMUNITY_INTERACTION_CONSUMER_ENABLED: false } as never,
      { project } as unknown as CommunityInteractionProjector,
      { publish: vi.fn() },
    );

    await consumer.handle(COMMUNITY_INTERACTION_TOPIC, 0, '12', Buffer.from(JSON.stringify(event)));

    expect(project).toHaveBeenCalledTimes(2);
  });
});
