import {
  Inject,
  Injectable,
  Logger,
  type OnApplicationShutdown,
  type OnModuleInit,
} from '@nestjs/common';
import { Kafka, logLevel, type Producer } from 'kafkajs';

import { CONFIGURATION_TOKEN } from '../application.tokens.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import { SpecialistNotificationLiveRepository } from './specialist-notification-live.repository.js';
import {
  LIVE_NOTIFICATION_TOPIC,
  toNotificationCreatedEvent,
  type SpecialistNotificationLiveClaim,
} from './specialist-notification-live.types.js';

@Injectable()
export class SpecialistNotificationLiveRelay implements OnModuleInit, OnApplicationShutdown {
  private readonly logger = new Logger(SpecialistNotificationLiveRelay.name);
  private producer?: Producer;
  private timer?: NodeJS.Timeout;
  private activeRun?: Promise<void>;
  private stopping = false;
  readonly metrics = { acknowledged: 0, suppressed: 0, failures: 0, fenced: 0 };

  constructor(
    @Inject(CONFIGURATION_TOKEN) private readonly configuration: ServiceConfiguration,
    @Inject(SpecialistNotificationLiveRepository)
    private readonly repository: SpecialistNotificationLiveRepository,
  ) {}

  onModuleInit(): void {
    if (!this.configuration.CONTENT_SPECIALIST_LIVE_RELAY_ENABLED) return;
    this.timer = setInterval(() => {
      void this.publishDue().catch(() => {
        this.logger.warn({ event: 'specialist_live_outbox_unavailable' });
      });
    }, 1_000);
    this.timer.unref();
  }

  async publishDue(limit = 20): Promise<void> {
    if (!this.configuration.CONTENT_SPECIALIST_LIVE_RELAY_ENABLED || this.stopping) return;
    if (this.activeRun) return this.activeRun;
    const run = this.relay(Math.min(Math.max(limit, 0), 20));
    this.activeRun = run;
    try {
      await run;
    } finally {
      this.activeRun = undefined;
    }
  }

  private async relay(limit: number): Promise<void> {
    for (let index = 0; index < limit && !this.stopping; index++) {
      const row = await this.repository.claim();
      if (!row) break;
      try {
        if (!(await this.repository.eligibleClaim(row))) {
          await this.suppress(row);
          continue;
        }
        const producer = await this.ensureProducer();
        if (!(await this.repository.eligibleClaim(row))) {
          await this.suppress(row);
          continue;
        }
        await producer.send({
          topic: LIVE_NOTIFICATION_TOPIC,
          acks: -1,
          timeout: 2_000,
          messages: [
            { key: row.notification_id, value: JSON.stringify(toNotificationCreatedEvent(row)) },
          ],
        });
        if (await this.repository.published(row)) this.metrics.acknowledged++;
        else this.metrics.fenced++;
        this.logger.log({
          event: 'specialist_live_kafka_acknowledged',
          eventId: row.id,
          correlationId: row.correlation_id,
          outboxLagMs: Math.max(0, Date.now() - new Date(row.occurred_at).getTime()),
        });
      } catch {
        this.metrics.failures++;
        await this.repository.retry(row);
        this.logger.warn({
          event: 'specialist_live_publish_deferred',
          eventId: row.id,
          correlationId: row.correlation_id,
          attempt: row.attempt_count,
          terminal: row.attempt_count >= 5,
        });
        const producer = this.producer;
        this.producer = undefined;
        await producer?.disconnect().catch(() => undefined);
      }
    }
  }

  private async suppress(row: SpecialistNotificationLiveClaim): Promise<void> {
    if (await this.repository.suppress(row)) this.metrics.suppressed++;
    else this.metrics.fenced++;
    this.logger.log({ event: 'specialist_live_suppressed', eventId: row.id });
  }

  private async ensureProducer(): Promise<Producer> {
    if (this.producer) return this.producer;
    const brokers =
      this.configuration.KAFKA_BOOTSTRAP_SERVERS?.split(',')
        .map((broker) => broker.trim())
        .filter(Boolean) ?? [];
    const kafka = new Kafka({
      clientId: 'content-specialist-notification-live-v1',
      brokers,
      logLevel: logLevel.NOTHING,
      connectionTimeout: 2_000,
      requestTimeout: 2_000,
      retry: { retries: 0 },
    });
    const producer = kafka.producer({
      idempotent: true,
      maxInFlightRequests: 1,
      allowAutoTopicCreation: false,
      retry: { retries: 1, initialRetryTime: 100, maxRetryTime: 500 },
    });
    try {
      await producer.connect();
      this.producer = producer;
      return producer;
    } catch (error) {
      await producer.disconnect().catch(() => undefined);
      throw error;
    }
  }

  async onApplicationShutdown(): Promise<void> {
    this.stopping = true;
    if (this.timer) clearInterval(this.timer);
    await this.activeRun?.catch(() => undefined);
    await this.producer?.disconnect();
    this.producer = undefined;
  }
}
