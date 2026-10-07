import {
  Inject,
  Injectable,
  Logger,
  type OnApplicationShutdown,
  type OnModuleInit,
} from '@nestjs/common';
import { Kafka, type Producer } from 'kafkajs';

import { CONFIGURATION_TOKEN } from '../application.tokens.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';

export interface ContentAdminAuditEvent {
  eventId: string;
  eventType: string;
  occurredAt: string;
  producer: string;
  schemaVersion: '1.0';
  sourceService: 'CONTENT';
  domain: 'RESOURCE_MANAGEMENT';
  actorId: string;
  actorType: 'ADMIN';
  action: string;
  result: 'SUCCEEDED';
  reasonCode: string | null;
  correlationId: string;
  targetAccountId: string | null;
  targetIdentifier: string | null;
}

export const CONTENT_ADMIN_AUDIT_PUBLISHER_TOKEN = Symbol('CONTENT_ADMIN_AUDIT_PUBLISHER_TOKEN');

export interface ContentAdminAuditPublisher {
  publish(event: ContentAdminAuditEvent): Promise<void>;
}

@Injectable()
export class KafkaContentAdminAuditPublisher
  implements ContentAdminAuditPublisher, OnModuleInit, OnApplicationShutdown
{
  private readonly logger = new Logger(KafkaContentAdminAuditPublisher.name);
  private producer?: Producer;
  private readonly topic: string;

  constructor(
    @Inject(CONFIGURATION_TOKEN)
    private readonly configuration: ServiceConfiguration,
  ) {
    this.topic = process.env.CONTENT_ADMIN_AUDIT_TOPIC ?? 'mentalbridge.admin.audit-event.v1';
  }

  async onModuleInit(): Promise<void> {
    const bootstrapServers = this.configuration.KAFKA_BOOTSTRAP_SERVERS;
    if (!bootstrapServers) return;

    try {
      const kafka = new Kafka({
        clientId: 'content-notification-service-audit',
        brokers: bootstrapServers
          .split(',')
          .map((b) => b.trim())
          .filter(Boolean),
      });
      this.producer = kafka.producer({ idempotent: true });
      await this.producer.connect();
    } catch (error) {
      this.logger.warn({
        event: 'audit_producer_connect_failed',
        error: (error as Error).message,
      });
    }
  }

  async onApplicationShutdown(): Promise<void> {
    if (this.producer) {
      await this.producer.disconnect();
    }
  }

  async publish(event: ContentAdminAuditEvent): Promise<void> {
    if (!this.producer) {
      this.logger.debug({ event: 'audit_producer_unavailable', eventId: event.eventId });
      return;
    }
    try {
      await this.producer.send({
        topic: this.topic,
        messages: [
          {
            key: event.targetIdentifier ?? event.eventId,
            value: JSON.stringify(event),
          },
        ],
      });
    } catch (error) {
      this.logger.error({
        event: 'audit_publish_failed',
        eventId: event.eventId,
        error: (error as Error).message,
      });
    }
  }
}
