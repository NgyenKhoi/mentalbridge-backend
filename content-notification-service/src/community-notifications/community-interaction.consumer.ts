import {
  Inject,
  Injectable,
  Logger,
  type OnApplicationShutdown,
  type OnModuleInit,
} from '@nestjs/common';
import { createHash } from 'node:crypto';
import { Kafka, logLevel, type Consumer, type Producer } from 'kafkajs';

import {
  COMMUNITY_INTERACTION_DEAD_LETTER_PUBLISHER_TOKEN,
  CONFIGURATION_TOKEN,
} from '../application.tokens.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import { CommunityNotificationRepository } from './community-notification.repository.js';
import {
  COMMUNITY_INTERACTION_DEAD_LETTER_TOPIC,
  COMMUNITY_INTERACTION_TOPIC,
  CommunityInteractionEventSchema,
  type CommunityInteractionDeadLetter,
  type CommunityInteractionDeadLetterPublisher,
  type CommunityInteractionEvent,
  type CommunityNotificationProjection,
} from './community-interaction.types.js';

const COMMUNITY_INTERACTION_GROUP = 'content-notification-community-interactions-v1';
const MAX_PROJECTION_ATTEMPTS = 3;

const wait = (milliseconds: number): Promise<void> =>
  new Promise((resolve) => setTimeout(resolve, milliseconds));

function eventFingerprint(event: CommunityInteractionEvent): string {
  return createHash('sha256')
    .update(
      JSON.stringify([
        event.eventId,
        event.schemaVersion,
        event.actorCommunityProfileId,
        event.targetOwnerRoutingReference,
        event.targetType,
        event.targetId,
        event.interactionKind,
        event.occurredAt,
        event.deepLink.kind,
        event.deepLink.postId,
      ]),
    )
    .digest('hex');
}

function projection(event: CommunityInteractionEvent): CommunityNotificationProjection {
  if (event.interactionKind === 'COMMENT') {
    return {
      eventId: event.eventId,
      ownerId: event.targetOwnerRoutingReference,
      kind: 'COMMUNITY_COMMENT',
      title: 'Bài viết của bạn có phản hồi mới',
      body: 'Một thành viên đã để lại lời nhắn hỗ trợ.',
      occurredAt: event.occurredAt,
      postId: event.deepLink.postId,
    };
  }
  if (event.interactionKind === 'REPLY') {
    return {
      eventId: event.eventId,
      ownerId: event.targetOwnerRoutingReference,
      kind: 'COMMUNITY_REPLY',
      title: 'Bình luận của bạn có phản hồi mới',
      body: 'Một thành viên đã tiếp tục cuộc trò chuyện hỗ trợ.',
      occurredAt: event.occurredAt,
      postId: event.deepLink.postId,
    };
  }
  return {
    eventId: event.eventId,
    ownerId: event.targetOwnerRoutingReference,
    kind: 'COMMUNITY_REACTION',
    title: 'Bài viết của bạn nhận được sự đồng cảm',
    body:
      event.interactionKind === 'THANK_YOU'
        ? 'Một thành viên đã gửi lời cảm ơn tới chia sẻ của bạn.'
        : event.interactionKind === 'RELATE'
          ? 'Một thành viên cảm thấy đồng điệu với chia sẻ của bạn.'
          : 'Một thành viên đã gửi sự ủng hộ tới chia sẻ của bạn.',
    occurredAt: event.occurredAt,
    postId: event.deepLink.postId,
  };
}

@Injectable()
export class CommunityInteractionProjector {
  constructor(private readonly repository: CommunityNotificationRepository) {}

  async project(event: CommunityInteractionEvent): Promise<void> {
    await this.repository.materialize(projection(event), eventFingerprint(event));
  }
}

@Injectable()
export class KafkaCommunityInteractionDeadLetterPublisher
  implements CommunityInteractionDeadLetterPublisher, OnModuleInit, OnApplicationShutdown
{
  private producer?: Producer;

  constructor(
    @Inject(CONFIGURATION_TOKEN)
    private readonly configuration: ServiceConfiguration,
  ) {}

  async onModuleInit(): Promise<void> {
    if (!this.configuration.CONTENT_COMMUNITY_INTERACTION_CONSUMER_ENABLED) return;
    const bootstrapServers = this.configuration.KAFKA_BOOTSTRAP_SERVERS;
    if (!bootstrapServers) throw new Error('Kafka bootstrap servers are required');
    const kafka = new Kafka({
      clientId: 'content-notification-service',
      brokers: bootstrapServers
        .split(',')
        .map((broker) => broker.trim())
        .filter(Boolean),
      logLevel: logLevel.NOTHING,
    });
    this.producer = kafka.producer({ allowAutoTopicCreation: false, idempotent: true });
    await this.producer.connect();
  }

  async publish(deadLetter: CommunityInteractionDeadLetter): Promise<void> {
    if (!this.producer)
      throw new Error('Community interaction dead-letter producer is unavailable');
    await this.producer.send({
      topic: COMMUNITY_INTERACTION_DEAD_LETTER_TOPIC,
      acks: -1,
      messages: [{ key: deadLetter.sourceMessageDigest, value: JSON.stringify(deadLetter) }],
    });
  }

  async onApplicationShutdown(): Promise<void> {
    if (this.producer) await this.producer.disconnect();
  }
}

@Injectable()
export class CommunityInteractionConsumer implements OnModuleInit, OnApplicationShutdown {
  private readonly logger = new Logger(CommunityInteractionConsumer.name);
  private consumer?: Consumer;

  constructor(
    @Inject(CONFIGURATION_TOKEN)
    private readonly configuration: ServiceConfiguration,
    @Inject(CommunityInteractionProjector)
    private readonly projector: CommunityInteractionProjector,
    @Inject(COMMUNITY_INTERACTION_DEAD_LETTER_PUBLISHER_TOKEN)
    private readonly deadLetters: CommunityInteractionDeadLetterPublisher,
  ) {}

  async onModuleInit(): Promise<void> {
    if (!this.configuration.CONTENT_COMMUNITY_INTERACTION_CONSUMER_ENABLED) return;
    const bootstrapServers = this.configuration.KAFKA_BOOTSTRAP_SERVERS;
    if (!bootstrapServers) throw new Error('Kafka bootstrap servers are required');
    const kafka = new Kafka({
      clientId: 'content-notification-service',
      brokers: bootstrapServers
        .split(',')
        .map((broker) => broker.trim())
        .filter(Boolean),
      logLevel: logLevel.NOTHING,
    });
    this.consumer = kafka.consumer({ groupId: COMMUNITY_INTERACTION_GROUP });
    await this.consumer.connect();
    await this.consumer.subscribe({ topic: COMMUNITY_INTERACTION_TOPIC, fromBeginning: true });
    void this.consumer
      .run({
        eachMessage: async ({ topic, partition, message }) => {
          await this.handle(topic, partition, message.offset, message.value);
        },
      })
      .catch(() => {
        this.logger.error('Community interaction consumer stopped unexpectedly');
      });
  }

  async handle(
    sourceTopic: string,
    partition: number,
    offset: string,
    value: Buffer | null,
  ): Promise<void> {
    let parsed: unknown;
    try {
      parsed = JSON.parse(value?.toString('utf8') ?? '');
    } catch {
      await this.park(sourceTopic, partition, offset, value, 'INVALID_JSON');
      return;
    }
    const event = CommunityInteractionEventSchema.safeParse(parsed);
    if (!event.success) {
      await this.park(sourceTopic, partition, offset, value, 'INVALID_EVENT');
      return;
    }

    for (let attempt = 1; attempt <= MAX_PROJECTION_ATTEMPTS; attempt += 1) {
      try {
        await this.projector.project(event.data);
        return;
      } catch (error) {
        if (attempt === MAX_PROJECTION_ATTEMPTS) throw error;
        await wait(100 * 2 ** (attempt - 1));
      }
    }
  }

  async onApplicationShutdown(): Promise<void> {
    if (this.consumer) await this.consumer.disconnect();
  }

  private async park(
    sourceTopic: string,
    sourcePartition: number,
    sourceOffset: string,
    value: Buffer | null,
    errorCode: CommunityInteractionDeadLetter['errorCode'],
  ): Promise<void> {
    await this.deadLetters.publish({
      schemaVersion: '1.0',
      failedAt: new Date().toISOString(),
      sourceTopic,
      sourcePartition,
      sourceOffset,
      sourceMessageDigest: createHash('sha256')
        .update(value ?? Buffer.alloc(0))
        .digest('hex'),
      errorCode,
    });
  }
}
