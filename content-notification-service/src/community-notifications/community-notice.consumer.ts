import {
  Inject,
  Injectable,
  Logger,
  type OnModuleInit,
  type OnApplicationShutdown,
} from '@nestjs/common';
import { createHash } from 'node:crypto';
import { Kafka, logLevel, type Consumer } from 'kafkajs';
import { z } from 'zod';
import {
  CONFIGURATION_TOKEN,
  COMMUNITY_INTERACTION_DEAD_LETTER_PUBLISHER_TOKEN,
} from '../application.tokens.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import {
  CommunityInteractionEnvelopeSchema,
  type CommunityInteractionDeadLetterPublisher,
  type CommunityNotificationProjection,
} from './community-interaction.types.js';
import {
  CommunityNotificationJobs,
  CommunityNoticeIdentityConflict,
} from './community-notification.jobs.js';

export const COMMUNITY_CHAT_TOPIC = 'mentalbridge.community.chat.v1';
export const COMMUNITY_COMMENT_REACTION_TOPIC = 'mentalbridge.community.comment-reaction.v1';
const uuid = z.uuid();
const version = z.string().regex(/^(0|[1-9][0-9]{0,18})$/);
const targeted = {
  conversationId: uuid,
  targetId: uuid,
  actorCommunityProfileId: uuid,
  recipientRoutingReference: uuid,
};
export const CommunityChatFactSchema = z
  .object({
    messageId: uuid,
    messageType: z.literal('community.chat.fact'),
    schemaVersion: z.literal('1.0'),
    producer: z.literal('community-service'),
    occurredAt: z.iso.datetime({ offset: true }),
    correlationId: uuid,
    aggregateId: uuid,
    aggregateVersion: version,
    payload: z.discriminatedUnion('kind', [
      z
        .object({
          kind: z.literal('MESSAGE_SAVED'),
          conversationId: uuid,
          targetId: uuid,
          sequence: version,
        })
        .strict(),
      z.object({ kind: z.literal('AUTHORIZATION_CHANGED'), conversationId: uuid }).strict(),
      z
        .object({
          kind: z.literal('READ_STATE_CHANGED'),
          conversationId: uuid,
          recipientRoutingReference: uuid,
        })
        .strict(),
      z.object({ kind: z.literal('MESSAGE_RECEIVED'), ...targeted }).strict(),
      z.object({ kind: z.literal('DIRECT_REQUESTED'), ...targeted }).strict(),
      z.object({ kind: z.literal('GROUP_INVITED'), ...targeted }).strict(),
    ]),
  })
  .strict()
  .refine((event) => event.aggregateId === event.payload.conversationId);

export const CommunityCommentReactionSchema = CommunityInteractionEnvelopeSchema.extend({
  targetType: z.literal('COMMENT'),
  interactionKind: z.enum(['SUPPORT', 'RELATE', 'THANK_YOU']),
});

export function communityChatProjection(
  event: z.infer<typeof CommunityChatFactSchema>,
): CommunityNotificationProjection | null {
  const payload = event.payload;
  if (!('actorCommunityProfileId' in payload)) return null;
  const kind = payload.kind;
  const type =
    kind === 'MESSAGE_RECEIVED'
      ? 'COMMUNITY_MESSAGE'
      : kind === 'DIRECT_REQUESTED'
        ? 'COMMUNITY_REQUEST'
        : 'COMMUNITY_INVITATION';
  const body =
    kind === 'MESSAGE_RECEIVED'
      ? 'Bạn có tin nhắn mới.'
      : kind === 'DIRECT_REQUESTED'
        ? 'Bạn có lời nhắn chờ chấp nhận.'
        : 'Bạn có lời mời vào nhóm riêng.';
  return {
    eventId: event.messageId,
    ownerId: payload.recipientRoutingReference,
    kind: type,
    title: 'Thông báo cộng đồng',
    body,
    occurredAt: event.occurredAt,
    source: 'COMMUNITY_CHAT_V1',
    sourceIdentity: kind + ':' + payload.targetId,
    actionType:
      kind === 'MESSAGE_RECEIVED'
        ? 'OPEN_COMMUNITY_CONVERSATION'
        : kind === 'DIRECT_REQUESTED'
          ? 'OPEN_COMMUNITY_REQUEST'
          : 'OPEN_COMMUNITY_INVITATION',
    actionTargetId: kind === 'MESSAGE_RECEIVED' ? payload.conversationId : payload.targetId,
    authorization: {
      recipientRoutingReference: payload.recipientRoutingReference,
      kind,
      targetId: payload.targetId,
      conversationId: payload.conversationId,
      actorCommunityProfileId: payload.actorCommunityProfileId,
    },
  };
}

function fingerprint(projection: CommunityNotificationProjection): string {
  return createHash('sha256')
    .update(JSON.stringify({ ...projection, eventId: undefined }))
    .digest('hex');
}

@Injectable()
export class CommunityNoticeConsumer implements OnModuleInit, OnApplicationShutdown {
  private consumer?: Consumer;
  private stopping = false;
  private readonly logger = new Logger(CommunityNoticeConsumer.name);
  constructor(
    @Inject(CONFIGURATION_TOKEN) private readonly configuration: ServiceConfiguration,
    @Inject(CommunityNotificationJobs) private readonly jobs: CommunityNotificationJobs,
    @Inject(COMMUNITY_INTERACTION_DEAD_LETTER_PUBLISHER_TOKEN)
    private readonly deadLetters: CommunityInteractionDeadLetterPublisher,
  ) {}
  async onModuleInit(): Promise<void> {
    if (!this.configuration.CONTENT_COMMUNITY_NOTICE_CONSUMER_ENABLED) return;
    const kafka = new Kafka({
      clientId: 'content-notification-community-notices',
      brokers: (this.configuration.KAFKA_BOOTSTRAP_SERVERS ?? '')
        .split(',')
        .map((broker) => broker.trim()),
      logLevel: logLevel.NOTHING,
    });
    this.consumer = kafka.consumer({ groupId: 'content-notification-community-notices-v1' });
    await this.consumer.connect();
    await this.consumer.subscribe({
      topics: [COMMUNITY_CHAT_TOPIC, COMMUNITY_COMMENT_REACTION_TOPIC],
      fromBeginning: true,
    });
    this.stopping = false;
    void this.consumer
      .run({
        eachMessage: async (input) => {
          const { topic, partition, message } = input;
          for (let attempt = 0; this.accepting(); attempt++) {
            try {
              await this.handle(topic, partition, message.offset, message.value, message.key);
              return;
            } catch {
              this.logger.warn('Community notice intake dependency retry scheduled');
              const retryAt = Date.now() + Math.min(30_000, 1000 * 2 ** Math.min(attempt, 5));
              while (this.accepting() && Date.now() < retryAt) {
                await new Promise<void>((resolve) =>
                  setTimeout(resolve, Math.max(1, Math.min(1000, retryAt - Date.now()))),
                );
                await input.heartbeat();
              }
            }
          }
          throw new Error('Community notice intake stopped before durable acceptance');
        },
      })
      .catch(() => {
        if (!this.stopping) this.logger.error('Community notice consumer stopped unexpectedly');
      });
  }
  async handle(
    topic: string,
    partition: number,
    offset: string,
    value: Buffer | null,
    key: Buffer | null,
  ): Promise<void> {
    let input: unknown;
    try {
      if (!value || value.byteLength > 32768) throw new Error('Invalid envelope');
      input = JSON.parse(value.toString('utf8'));
    } catch {
      await this.park(topic, partition, offset, value, 'INVALID_JSON');
      return;
    }
    let projection: CommunityNotificationProjection | null = null;
    if (topic === COMMUNITY_CHAT_TOPIC) {
      const event = CommunityChatFactSchema.safeParse(input);
      if (!event.success || key?.toString() !== event.data.aggregateId) {
        await this.park(topic, partition, offset, value, 'INVALID_EVENT');
        return;
      }
      projection = communityChatProjection(event.data);
    } else if (topic === COMMUNITY_COMMENT_REACTION_TOPIC) {
      const event = CommunityCommentReactionSchema.safeParse(input);
      if (!event.success || key?.toString() !== event.data.targetId) {
        await this.park(topic, partition, offset, value, 'INVALID_EVENT');
        return;
      }
      projection = {
        eventId: event.data.eventId,
        ownerId: event.data.targetOwnerRoutingReference,
        kind: 'COMMUNITY_REACTION',
        title: 'Bình luận của bạn nhận được sự đồng cảm',
        body: 'Một thành viên đã gửi phản hồi tích cực tới bình luận của bạn.',
        occurredAt: event.data.occurredAt,
        postId: event.data.deepLink.postId,
        source: 'COMMUNITY_COMMENT_REACTION_V1',
        sourceIdentity: event.data.targetId + ':' + event.data.actorCommunityProfileId,
        groupingKey: 'COMMENT:' + event.data.targetId,
      };
    } else {
      await this.park(topic, partition, offset, value, 'INVALID_EVENT');
      return;
    }
    if (!projection) return;
    try {
      await this.jobs.enqueue(projection, fingerprint(projection));
    } catch (error) {
      if (!(error instanceof CommunityNoticeIdentityConflict)) throw error;
      await this.park(topic, partition, offset, value, 'INVALID_EVENT');
    }
  }
  private async park(
    topic: string,
    partition: number,
    offset: string,
    value: Buffer | null,
    errorCode: 'INVALID_JSON' | 'INVALID_EVENT',
  ) {
    await this.deadLetters.publish({
      schemaVersion: '1.0',
      failedAt: new Date().toISOString(),
      sourceTopic: topic,
      sourcePartition: partition,
      sourceOffset: offset,
      sourceMessageDigest: createHash('sha256')
        .update(value ?? Buffer.alloc(0))
        .digest('hex'),
      errorCode,
    });
  }
  async onApplicationShutdown(): Promise<void> {
    this.stopping = true;
    await this.consumer?.disconnect();
  }
  private accepting(): boolean {
    return !this.stopping;
  }
}
