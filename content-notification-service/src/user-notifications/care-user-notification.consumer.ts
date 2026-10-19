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
  CARE_USER_NOTIFICATION_DEAD_LETTER_PUBLISHER_TOKEN,
  CONFIGURATION_TOKEN,
} from '../application.tokens.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import { UserNotificationRepository } from './user-notification.repository.js';
import {
  CARE_USER_NOTIFICATION_DEAD_LETTER_TOPIC,
  CARE_USER_NOTIFICATION_TOPIC,
  CareUserNotificationFactSchema,
  type CareUserNotificationFact,
  type UserNotificationDeadLetter,
  type UserNotificationDeadLetterPublisher,
  type UserNotificationProjection,
} from './user-notification.types.js';

const CONSUMER_GROUP = 'content-notification-care-user-facts-v1';
const MAX_ATTEMPTS = 3;
const wait = (milliseconds: number) => new Promise((resolve) => setTimeout(resolve, milliseconds));

function fingerprint(event: CareUserNotificationFact): string {
  return createHash('sha256')
    .update(
      JSON.stringify({
        recipientId: event.payload.recipientId,
        factType: event.payload.factType,
        targetId: event.payload.targetId,
        targetVersion: event.payload.targetVersion,
        availableAt: event.payload.availableAt,
      }),
    )
    .digest('hex');
}

function projection(event: CareUserNotificationFact): UserNotificationProjection {
  if (event.payload.factType === 'ASSESSMENT_RESULT_AVAILABLE') {
    return {
      sourceIdentity: `${event.payload.factType}:${event.payload.targetId}:${String(event.payload.targetVersion)}`,
      ownerId: event.payload.recipientId,
      kind: 'ASSESSMENT_RESULT_AVAILABLE',
      title: 'Kết quả sàng lọc đã sẵn sàng',
      body: 'Bạn có thể xem lại kết quả và các bước hỗ trợ đã được lưu.',
      occurredAt: event.payload.availableAt,
      preferenceGroup: 'SCREENING_REASSESSMENT',
      source: 'CARE_USER_NOTIFICATION_V1',
      actionType: 'OPEN_ASSESSMENTS',
      actionTargetId: null,
    };
  }
  return {
    sourceIdentity: `${event.payload.factType}:${event.payload.targetId}:${String(event.payload.targetVersion)}`,
    ownerId: event.payload.recipientId,
    kind: 'SUPPORT_GUIDE_AVAILABLE',
    title: 'Hướng dẫn hỗ trợ đã sẵn sàng',
    body: 'Hướng dẫn và tài nguyên đã được lưu để bạn xem lại khi thuận tiện.',
    occurredAt: event.payload.availableAt,
    preferenceGroup: 'RESOURCE_SYSTEM',
    source: 'CARE_USER_NOTIFICATION_V1',
    actionType: 'OPEN_SUPPORT_GUIDE',
    actionTargetId: event.payload.targetId,
  };
}

@Injectable()
export class CareUserNotificationProjector {
  constructor(private readonly repository: UserNotificationRepository) {}

  async project(event: CareUserNotificationFact): Promise<void> {
    await this.repository.materialize(projection(event), fingerprint(event));
  }
}

@Injectable()
export class KafkaCareUserNotificationDeadLetterPublisher
  implements UserNotificationDeadLetterPublisher, OnModuleInit, OnApplicationShutdown
{
  private producer?: Producer;

  constructor(@Inject(CONFIGURATION_TOKEN) private readonly configuration: ServiceConfiguration) {}

  async onModuleInit(): Promise<void> {
    if (!this.configuration.CONTENT_CARE_USER_NOTIFICATION_CONSUMER_ENABLED) return;
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

  async publish(deadLetter: UserNotificationDeadLetter): Promise<void> {
    if (!this.producer)
      throw new Error('Care user notification dead-letter producer is unavailable');
    await this.producer.send({
      topic: CARE_USER_NOTIFICATION_DEAD_LETTER_TOPIC,
      acks: -1,
      messages: [{ key: deadLetter.sourceMessageDigest, value: JSON.stringify(deadLetter) }],
    });
  }

  async onApplicationShutdown(): Promise<void> {
    if (this.producer) await this.producer.disconnect();
  }
}

@Injectable()
export class CareUserNotificationConsumer implements OnModuleInit, OnApplicationShutdown {
  private readonly logger = new Logger(CareUserNotificationConsumer.name);
  private consumer?: Consumer;

  constructor(
    @Inject(CONFIGURATION_TOKEN) private readonly configuration: ServiceConfiguration,
    @Inject(CareUserNotificationProjector)
    private readonly projector: CareUserNotificationProjector,
    @Inject(CARE_USER_NOTIFICATION_DEAD_LETTER_PUBLISHER_TOKEN)
    private readonly deadLetters: UserNotificationDeadLetterPublisher,
  ) {}

  async onModuleInit(): Promise<void> {
    if (!this.configuration.CONTENT_CARE_USER_NOTIFICATION_CONSUMER_ENABLED) return;
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
    this.consumer = kafka.consumer({ groupId: CONSUMER_GROUP });
    await this.consumer.connect();
    await this.consumer.subscribe({ topic: CARE_USER_NOTIFICATION_TOPIC, fromBeginning: true });
    void this.consumer
      .run({
        eachMessage: async ({ topic, partition, message }) =>
          this.handle(topic, partition, message.offset, message.value),
      })
      .catch(() => {
        this.logger.error('Care user notification consumer stopped unexpectedly');
      });
  }

  async handle(
    topic: string,
    partition: number,
    offset: string,
    value: Buffer | null,
  ): Promise<void> {
    let parsed: unknown;
    try {
      parsed = JSON.parse(value?.toString('utf8') ?? '');
    } catch {
      await this.park(topic, partition, offset, value, 'INVALID_JSON');
      return;
    }
    const event = CareUserNotificationFactSchema.safeParse(parsed);
    if (!event.success) {
      await this.park(topic, partition, offset, value, 'INVALID_EVENT');
      return;
    }
    for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt += 1) {
      try {
        await this.projector.project(event.data);
        return;
      } catch (error) {
        if (attempt === MAX_ATTEMPTS) throw error;
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
    errorCode: UserNotificationDeadLetter['errorCode'],
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
