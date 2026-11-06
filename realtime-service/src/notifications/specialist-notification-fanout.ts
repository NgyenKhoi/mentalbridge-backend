import { Inject, Injectable, type OnApplicationShutdown, type OnModuleInit } from '@nestjs/common';
import { Kafka, logLevel, type Consumer, type Producer } from 'kafkajs';
import { createClient } from 'redis';
import type { Logger } from 'pino';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import { RedisService } from '../database/redis.service.js';
import type { RealtimeMetrics } from '../observability/metrics.js';
import { CONFIGURATION_TOKEN, LOGGER_TOKEN, METRICS_TOKEN } from '../shared/tokens.js';
import { NotificationRecordHandler } from './notification-record-handler.js';
import {
  NOTIFICATION_TOPIC,
  NOTIFICATION_RETRY_TOPIC,
  NOTIFICATION_CHANNEL,
  NOTIFICATION_GROUP,
  notificationPointerSchema,
  type NotificationPointer,
} from './notification-schema.js';

@Injectable()
export class SpecialistNotificationFanout implements OnModuleInit, OnApplicationShutdown {
  private readonly subscriber: ReturnType<typeof createClient>;
  private readonly consumer: Consumer;
  private readonly producer: Producer;
  private readonly handler: NotificationRecordHandler;
  private readonly listeners = new Set<(pointer: NotificationPointer) => void>();
  private readonly pauses = new Map<string, NodeJS.Timeout>();
  private timer?: NodeJS.Timeout;
  private active: Promise<void> | undefined;
  private stopped = false;
  private kafkaReady = false;
  private redisReady = false;
  private startStage = 'idle';
  private lastKafkaErrorCode = 'NONE';
  private lastKafkaErrorChain: string[] = [];
  private lastKafkaTimeoutApi = 'NONE';
  private readonly receive = (wire: string): void => {
    if (Buffer.byteLength(wire) > 2048) return;
    try {
      const input: unknown = JSON.parse(wire);
      const parsed = notificationPointerSchema.safeParse(input);
      if (parsed.success) for (const listener of this.listeners) listener(parsed.data);
      else this.metrics.commands.inc({ command: 'notification.redis', result: 'invalid' });
    } catch {
      this.metrics.commands.inc({ command: 'notification.redis', result: 'invalid' });
    }
  };

  constructor(
    private readonly redis: RedisService,
    @Inject(CONFIGURATION_TOKEN) private readonly configuration: ServiceConfiguration,
    @Inject(METRICS_TOKEN) private readonly metrics: RealtimeMetrics,
    @Inject(LOGGER_TOKEN) private readonly logger: Logger,
  ) {
    this.subscriber = createClient({
      url: configuration.REDIS_URL,
      socket: {
        connectTimeout: configuration.REDIS_CONNECTION_TIMEOUT_MS,
        reconnectStrategy: false,
      },
    });
    this.subscriber.on('error', () => {
      this.redisReady = false;
    });
    this.subscriber.on('end', () => {
      this.redisReady = false;
    });
    const kafka = new Kafka({
      clientId: 'realtime-specialist-notifications',
      brokers: configuration.SPECIALIST_LIVE.brokers,
      logLevel: logLevel.NOTHING,
      connectionTimeout: 500,
      requestTimeout: 2000,
      retry: {
        retries: 2,
        initialRetryTime: 200,
        maxRetryTime: 1000,
        restartOnFailure: () => Promise.resolve(false),
      },
    });
    this.consumer = kafka.consumer({
      groupId: NOTIFICATION_GROUP,
      allowAutoTopicCreation: false,
      maxWaitTimeInMs: 1000,
      maxBytesPerPartition: 262_144,
      maxBytes: 786_432,
    });
    this.producer = kafka.producer({
      allowAutoTopicCreation: false,
      idempotent: true,
      maxInFlightRequests: 1,
    });
    this.handler = new NotificationRecordHandler(
      this.producer,
      (pointer) => this.publish(pointer),
      metrics,
    );
    this.consumer.on(this.consumer.events.CRASH, (event) => {
      this.kafkaReady = false;
      this.lastKafkaErrorCode = event.payload.error.name;
      this.lastKafkaErrorChain = [];
      let cause: unknown = event.payload.error;
      for (let depth = 0; depth < 5 && cause instanceof Error; depth++) {
        const type: unknown = Reflect.get(cause, 'type');
        this.lastKafkaErrorChain.push(
          `${cause.name.slice(0, 80)}${typeof type === 'string' ? `:${type.slice(0, 80)}` : ''}`,
        );
        const next: unknown = cause.cause;
        if (next === cause) break;
        cause = next;
      }
      this.metrics.commands.inc({ command: 'notification.transport', result: 'kafka_unavailable' });
    });
    this.consumer.on(this.consumer.events.GROUP_JOIN, () => {
      this.kafkaReady = true;
    });
    this.consumer.on(this.consumer.events.REQUEST_TIMEOUT, (event) => {
      this.lastKafkaTimeoutApi = event.payload.apiName;
    });
  }

  async onModuleInit(): Promise<void> {
    await this.start();
    if (this.stopped) return;
    this.timer = setInterval(() => {
      void this.start();
    }, 5000);
    this.timer.unref();
  }

  onPointer(listener: (pointer: NotificationPointer) => void): void {
    this.listeners.add(listener);
  }

  available(): boolean {
    return this.redisReady && this.kafkaReady && !this.stopped;
  }

  async publish(pointer: NotificationPointer): Promise<void> {
    await this.redis.execute((client) =>
      client.publish(NOTIFICATION_CHANNEL, JSON.stringify(pointer)),
    );
  }

  async onApplicationShutdown(): Promise<void> {
    this.stopped = true;
    if (this.timer) clearInterval(this.timer);
    for (const pause of this.pauses.values()) clearTimeout(pause);
    this.pauses.clear();
    await this.active;
    if (this.subscriber.isOpen) this.subscriber.destroy();
    await Promise.allSettled([this.consumer.disconnect(), this.producer.disconnect()]);
  }

  private isStopped(): boolean {
    return this.stopped;
  }

  private start(): Promise<void> {
    if (this.active || this.stopped) return Promise.resolve();
    const active = this.connect().finally(() => {
      this.active = undefined;
    });
    this.active = active;
    return active;
  }

  private async connect(): Promise<void> {
    try {
      if (!this.redisReady) {
        this.startStage = 'redis_connect';
        if (!this.subscriber.isOpen) await this.subscriber.connect();
        this.startStage = 'redis_subscribe';
        await this.subscriber.subscribe(NOTIFICATION_CHANNEL, this.receive);
        this.redisReady = true;
      }
      if (!this.kafkaReady && !this.isStopped()) {
        this.startStage = 'consumer_disconnect';
        await this.consumer.disconnect();
        if (this.isStopped()) return;
        this.startStage = 'producer_connect';
        await this.producer.connect();
        if (this.isStopped()) return;
        this.startStage = 'consumer_connect';
        await this.consumer.connect();
        if (this.isStopped()) return;
        this.startStage = 'consumer_subscribe';
        await this.consumer.subscribe({
          topics: [NOTIFICATION_TOPIC, NOTIFICATION_RETRY_TOPIC],
          fromBeginning: true,
        });
        if (this.isStopped()) return;
        this.startStage = 'consumer_run';
        await this.consumer.run({
          eachBatchAutoResolve: false,
          autoCommitThreshold: 1,
          partitionsConsumedConcurrently: 3,
          eachBatch: async (payload) => {
            const { batch } = payload;
            for (const message of batch.messages) {
              if (!payload.isRunning() || payload.isStale() || this.stopped) break;
              this.metrics.notificationConsumerLag.set(
                { topic: batch.topic, partition: batch.partition },
                Math.max(0, Number(BigInt(batch.highWatermark) - BigInt(message.offset) - 1n)),
              );
              const deferUntil = await this.handler.handle({
                topic: batch.topic,
                partition: batch.partition,
                message,
              });
              if (deferUntil !== undefined) {
                this.pause(batch.topic, batch.partition, deferUntil);
                await payload.heartbeat();
                break;
              }
              payload.resolveOffset(message.offset);
              await payload.commitOffsetsIfNecessary();
              await payload.heartbeat();
            }
          },
        });
      }
    } catch {
      this.metrics.commands.inc({ command: 'notification.transport', result: 'unavailable' });
      this.logger.warn(
        { errorCode: 'SPECIALIST_NOTIFICATION_TRANSPORT_UNAVAILABLE', stage: this.startStage },
        'Specialist notification transport unavailable',
      );
    } finally {
      this.startStage = 'idle';
    }
  }

  private pause(topic: string, partition: number, until: number): void {
    const key = `${topic}:${String(partition)}`;
    const current = this.pauses.get(key);
    if (current) clearTimeout(current);
    this.consumer.pause([{ topic, partitions: [partition] }]);
    const timer = setTimeout(
      () => {
        this.pauses.delete(key);
        if (!this.stopped) this.consumer.resume([{ topic, partitions: [partition] }]);
      },
      Math.min(30_000, Math.max(1, until - Date.now())),
    );
    timer.unref();
    this.pauses.set(key, timer);
  }
}
