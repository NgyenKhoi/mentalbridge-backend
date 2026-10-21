import { Inject, Injectable, type OnApplicationShutdown, type OnModuleInit } from '@nestjs/common';
import { Kafka, logLevel, type Consumer, type Producer } from 'kafkajs';
import { createClient } from 'redis';
import type { Logger } from 'pino';
import { z } from 'zod';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import { RedisService } from '../database/redis.service.js';
import type { RealtimeMetrics } from '../observability/metrics.js';
import { CONFIGURATION_TOKEN, METRICS_TOKEN, LOGGER_TOKEN } from '../shared/tokens.js';
import { factSchema, type CommunityFact } from './community-schema.js';

export const COMMUNITY_TOPIC = 'mentalbridge.community.chat.v1';
export const COMMUNITY_DEAD_LETTER_TOPIC = 'mentalbridge.community.chat.realtime-dead-letter.v1';
type FactListener = (fact: CommunityFact) => void;
const presenceSignal = z
  .object({ schemaVersion: z.literal(1), subjectRoutingReference: z.uuid() })
  .strict();
@Injectable()
export class CommunityFanout implements OnModuleInit, OnApplicationShutdown {
  private readonly subscriber: ReturnType<typeof createClient>;
  private readonly consumer: Consumer;
  private readonly producer: Producer;
  private readonly listeners = new Set<FactListener>();
  private readonly presenceListeners = new Set<(subject: string) => void>();
  private readonly failures = new Set<() => void>();
  private timer?: NodeJS.Timeout;
  private starting = false;
  private stopped = false;
  private kafkaReady = false;
  private redisReady = false;
  constructor(
    private readonly redis: RedisService,
    @Inject(CONFIGURATION_TOKEN) private readonly configuration: ServiceConfiguration,
    @Inject(METRICS_TOKEN) private readonly metrics: RealtimeMetrics,
    @Inject(LOGGER_TOKEN) private readonly logger: Logger,
  ) {
    this.subscriber = createClient({
      url: configuration.REDIS_URL,
      socket: { connectTimeout: 1000, reconnectStrategy: false },
    });
    this.subscriber.on('error', () => {
      this.lostRedis();
    });
    this.subscriber.on('end', () => {
      this.lostRedis();
    });
    const kafka = new Kafka({
      clientId: 'realtime-community',
      brokers: configuration.COMMUNITY.brokers,
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
      groupId: configuration.COMMUNITY.groupId,
      allowAutoTopicCreation: false,
      maxBytesPerPartition: 262_144,
    });
    this.producer = kafka.producer({
      allowAutoTopicCreation: false,
      idempotent: true,
      maxInFlightRequests: 1,
    });
    this.consumer.on(this.consumer.events.CRASH, (event) => {
      this.kafkaReady = false;
      this.fail();
      this.logger.warn({ errorCode: event.payload.error.name }, 'Community consumer stopped');
    });
    this.consumer.on(this.consumer.events.GROUP_JOIN, () => {
      this.kafkaReady = true;
    });
  }
  async onModuleInit(): Promise<void> {
    await this.start();
    this.timer = setInterval(() => {
      void this.start();
    }, 5000);
    this.timer.unref();
  }
  available(): boolean {
    return this.redisReady && this.kafkaReady && !this.stopped;
  }
  onFact(listener: FactListener): void {
    this.listeners.add(listener);
  }
  onPresence(listener: (subject: string) => void): void {
    this.presenceListeners.add(listener);
  }
  async publishPresence(subject: string): Promise<void> {
    await this.redis.execute((client) =>
      client.publish(
        this.configuration.COMMUNITY.channel + ':presence',
        JSON.stringify({ schemaVersion: 1, subjectRoutingReference: subject }),
      ),
    );
  }
  onFailure(listener: () => void): void {
    this.failures.add(listener);
  }
  async publish(fact: CommunityFact): Promise<void> {
    await this.redis.execute((client) =>
      client.publish(this.configuration.COMMUNITY.channel, JSON.stringify(fact)),
    );
  }
  async onApplicationShutdown(): Promise<void> {
    this.stopped = true;
    if (this.timer) clearInterval(this.timer);
    await Promise.allSettled([
      this.consumer.disconnect(),
      this.producer.disconnect(),
      this.subscriber.isOpen ? this.subscriber.quit() : Promise.resolve(),
    ]);
  }
  private async start(): Promise<void> {
    if (this.starting || this.stopped) return;
    this.starting = true;
    let stage = 'redis';
    try {
      if (!this.redisReady) {
        if (!this.subscriber.isOpen) await this.subscriber.connect();
        await this.subscriber.subscribe(this.configuration.COMMUNITY.channel, (wire) => {
          this.receive(wire);
        });
        await this.subscriber.subscribe(
          this.configuration.COMMUNITY.channel + ':presence',
          (wire) => {
            if (Buffer.byteLength(wire) > 1024) return;
            try {
              const parsed = presenceSignal.safeParse(JSON.parse(wire));
              if (parsed.success)
                for (const listener of this.presenceListeners)
                  listener(parsed.data.subjectRoutingReference);
            } catch {
              this.metrics.commands.inc({ command: 'community.presence', result: 'invalid' });
            }
          },
        );
        this.redisReady = true;
      }
      if (!this.kafkaReady) {
        stage = 'kafka-producer';
        await this.producer.connect();
        stage = 'kafka-consumer';
        await this.consumer.connect();
        await this.consumer.subscribe({ topic: COMMUNITY_TOPIC, fromBeginning: true });
        await this.consumer.run({
          autoCommitThreshold: 1,
          eachMessage: async ({ topic, partition, message }) => {
            let input: unknown;
            try {
              if (!message.value || message.value.length > 32768) throw new Error('Invalid size');
              input = JSON.parse(message.value.toString('utf8'));
            } catch {
              input = undefined;
            }
            const parsed = factSchema.safeParse(input);
            if (!parsed.success || message.key?.toString() !== parsed.data.aggregateId) {
              await this.producer.send({
                topic: COMMUNITY_DEAD_LETTER_TOPIC,
                messages: [
                  {
                    key: topic + ':' + String(partition) + ':' + message.offset,
                    value: JSON.stringify({
                      topic,
                      partition,
                      offset: message.offset,
                      code: 'INVALID_COMMUNITY_FACT',
                    }),
                  },
                ],
              });
              this.metrics.commands.inc({ command: 'community.fact', result: 'invalid' });
              return;
            }
            if (
              ['MESSAGE_SAVED', 'AUTHORIZATION_CHANGED', 'READ_STATE_CHANGED'].includes(
                parsed.data.payload.kind,
              )
            )
              await this.publish(parsed.data);
          },
        });
        this.kafkaReady = true;
      }
    } catch (error) {
      this.metrics.commands.inc({ command: 'community.transport', result: 'unavailable' });
      this.logger.warn(
        { stage, errorCode: error instanceof Error ? error.name : 'UNKNOWN' },
        'Community transport unavailable',
      );
      this.fail();
    } finally {
      this.starting = false;
    }
  }
  private receive(wire: string): void {
    if (Buffer.byteLength(wire) > 32768) return;
    try {
      const input: unknown = JSON.parse(wire);
      const parsed = factSchema.safeParse(input);
      if (parsed.success) for (const listener of this.listeners) listener(parsed.data);
    } catch {
      this.metrics.commands.inc({ command: 'community.fact', result: 'invalid' });
    }
  }
  private lostRedis(): void {
    this.redisReady = false;
    this.fail();
  }
  private fail(): void {
    for (const listener of this.failures) listener();
  }
}
