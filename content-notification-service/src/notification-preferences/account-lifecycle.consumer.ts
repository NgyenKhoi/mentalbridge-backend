import {
  Inject,
  Injectable,
  Logger,
  type OnApplicationShutdown,
  type OnModuleInit,
} from '@nestjs/common';
import { createHash } from 'node:crypto';
import { Kafka, logLevel, type Consumer, type Producer } from 'kafkajs';
import { z } from 'zod';

import {
  CONFIGURATION_TOKEN,
  NOTIFICATION_PREFERENCE_SERVICE_TOKEN,
} from '../application.tokens.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import type { NotificationPreferenceService } from './notification-preference.service.js';

export const ACCOUNT_LIFECYCLE_TOPIC = 'mentalbridge.identity.account-lifecycle.v1';
export const ACCOUNT_PREFERENCE_DEAD_LETTER_TOPIC =
  'mentalbridge.content-notification.account-preference-dead-letter.v1';

const ACCOUNT_LIFECYCLE_GROUP = 'content-notification-account-preferences-v1';
const MAX_HANDLER_ATTEMPTS = 3;

const AccountRegisteredEventSchema = z
  .object({
    messageId: z.uuid(),
    messageType: z.literal('identity.account.registered'),
    occurredAt: z.iso.datetime({ offset: true }),
    producer: z.literal('identity-service'),
    schemaVersion: z.literal('1.0'),
    correlationId: z.uuid(),
    aggregateId: z.uuid(),
    aggregateVersion: z.number().int().nonnegative(),
    payload: z
      .object({
        accountId: z.uuid(),
        actorType: z.enum(['USER', 'SPECIALIST']),
        status: z.literal('PENDING_EMAIL_VERIFICATION'),
      })
      .strict(),
  })
  .strict();

export type AccountRegisteredEvent = z.infer<typeof AccountRegisteredEventSchema>;

const wait = (milliseconds: number): Promise<void> =>
  new Promise((resolve) => setTimeout(resolve, milliseconds));

@Injectable()
export class AccountLifecycleProjector {
  constructor(
    @Inject(NOTIFICATION_PREFERENCE_SERVICE_TOKEN)
    private readonly preferences: NotificationPreferenceService,
  ) {}

  async project(event: AccountRegisteredEvent): Promise<void> {
    if (event.payload.actorType !== 'USER') return;
    await this.preferences.initializeForAccount(event.payload.accountId);
  }
}

@Injectable()
export class AccountLifecycleConsumer implements OnModuleInit, OnApplicationShutdown {
  private readonly logger = new Logger(AccountLifecycleConsumer.name);
  private consumer?: Consumer;
  private deadLetters?: Producer;
  private runPromise?: Promise<void>;

  constructor(
    @Inject(CONFIGURATION_TOKEN)
    private readonly configuration: ServiceConfiguration,
    private readonly projector: AccountLifecycleProjector,
  ) {}

  async onModuleInit(): Promise<void> {
    if (this.configuration.CONTENT_ACCOUNT_LIFECYCLE_CONSUMER_ENABLED !== 'true') return;
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
    this.consumer = kafka.consumer({ groupId: ACCOUNT_LIFECYCLE_GROUP });
    this.deadLetters = kafka.producer({ allowAutoTopicCreation: false, idempotent: true });
    await Promise.all([this.consumer.connect(), this.deadLetters.connect()]);
    await this.consumer.subscribe({ topic: ACCOUNT_LIFECYCLE_TOPIC, fromBeginning: true });
    this.runPromise = this.consumer.run({
      eachMessage: async ({ topic, partition, message }) => {
        await this.handle(topic, partition, message.offset, message.key, message.value);
      },
    });
    void this.runPromise.catch(() => {
      this.logger.error('Account lifecycle consumer stopped unexpectedly');
    });
  }

  async onApplicationShutdown(): Promise<void> {
    if (this.consumer) await this.consumer.disconnect();
    if (this.deadLetters) await this.deadLetters.disconnect();
  }

  async handle(
    topic: string,
    partition: number,
    offset: string,
    key: Buffer | null,
    value: Buffer | null,
  ): Promise<void> {
    let parsed: unknown;
    try {
      parsed = JSON.parse(value?.toString('utf8') ?? '');
    } catch {
      await this.deadLetter(topic, partition, offset, key, value, 'INVALID_JSON');
      return;
    }

    if (
      !parsed ||
      typeof parsed !== 'object' ||
      !('messageType' in parsed) ||
      parsed.messageType !== 'identity.account.registered'
    ) {
      return;
    }

    const validated = AccountRegisteredEventSchema.safeParse(parsed);
    if (!validated.success) {
      await this.deadLetter(topic, partition, offset, key, value, 'INVALID_EVENT');
      return;
    }

    for (let attempt = 1; attempt <= MAX_HANDLER_ATTEMPTS; attempt += 1) {
      try {
        await this.projector.project(validated.data);
        return;
      } catch {
        if (attempt < MAX_HANDLER_ATTEMPTS) {
          await wait(100 * 2 ** (attempt - 1));
          continue;
        }
      }
    }
    await this.deadLetter(
      topic,
      partition,
      offset,
      key,
      Buffer.from(JSON.stringify(validated.data)),
      'PREFERENCE_PROJECTION_FAILED',
    );
  }

  private async deadLetter(
    sourceTopic: string,
    partition: number,
    offset: string,
    key: Buffer | null,
    value: Buffer | null,
    errorCode: string,
  ): Promise<void> {
    if (!this.deadLetters) throw new Error('Account lifecycle dead-letter producer is unavailable');
    const digest = createHash('sha256')
      .update(value ?? Buffer.alloc(0))
      .digest('hex');
    await this.deadLetters.send({
      topic: ACCOUNT_PREFERENCE_DEAD_LETTER_TOPIC,
      acks: -1,
      messages: [
        {
          key,
          value: JSON.stringify({
            schemaVersion: '1.0',
            failedAt: new Date().toISOString(),
            sourceTopic,
            sourcePartition: partition,
            sourceOffset: offset,
            sourceMessageDigest: digest,
            errorCode,
          }),
        },
      ],
    });
  }
}
