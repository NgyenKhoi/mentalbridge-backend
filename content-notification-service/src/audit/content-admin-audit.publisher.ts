import {
  Inject,
  Injectable,
  Logger,
  Optional,
  type OnApplicationShutdown,
  type OnModuleInit,
} from '@nestjs/common';
import { Kafka, type Producer } from 'kafkajs';

import { CONFIGURATION_TOKEN, DATABASE_SERVICE_TOKEN } from '../application.tokens.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import type { DatabaseService } from '../database/database.service.js';

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
    @Optional()
    @Inject(DATABASE_SERVICE_TOKEN)
    private readonly db?: DatabaseService,
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
      await this.publishDue();
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
    if (this.db) {
      try {
        await this.db.query(
          `INSERT INTO content_admin_audit_outbox
            (id, deduplication_key, event_type, correlation_id, payload, occurred_at)
           VALUES ($1, $2, $3, $4, $5, $6)
           ON CONFLICT (deduplication_key) DO NOTHING`,
          [
            event.eventId,
            `audit:content:${event.action}:${event.eventId}`,
            event.eventType,
            event.correlationId,
            JSON.stringify(event),
            event.occurredAt,
          ],
        );
      } catch (dbError) {
        this.logger.warn({
          event: 'audit_outbox_insert_failed',
          eventId: event.eventId,
          error: (dbError as Error).message,
        });
      }
    }

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
      if (this.db) {
        await this.db.query(
          `UPDATE content_admin_audit_outbox
           SET published_at = now(), next_attempt_at = NULL
           WHERE id = $1 AND published_at IS NULL`,
          [event.eventId],
        );
      }
    } catch (error) {
      this.logger.error({
        event: 'audit_publish_failed',
        eventId: event.eventId,
        error: (error as Error).message,
      });
      if (this.db) {
        await this.db.query(
          `UPDATE content_admin_audit_outbox
           SET attempt_count = attempt_count + 1, next_attempt_at = now() + interval '5 seconds'
           WHERE id = $1 AND published_at IS NULL`,
          [event.eventId],
        );
      }
    }
  }

  private async publishDue(): Promise<void> {
    if (!this.db || !this.producer) return;
    try {
      const result = await this.db.query<{ id: string; payload: ContentAdminAuditEvent }>(
        `SELECT id, payload
         FROM content_admin_audit_outbox
         WHERE published_at IS NULL AND COALESCE(next_attempt_at, occurred_at) <= now()
         ORDER BY COALESCE(next_attempt_at, occurred_at), occurred_at, id
         LIMIT 50`,
      );
      for (const row of result.rows) {
        const event = row.payload;
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
          await this.db.query(
            `UPDATE content_admin_audit_outbox
             SET published_at = now(), next_attempt_at = NULL
             WHERE id = $1 AND published_at IS NULL`,
            [row.id],
          );
        } catch (sendError) {
          await this.db.query(
            `UPDATE content_admin_audit_outbox
             SET attempt_count = attempt_count + 1, next_attempt_at = now() + interval '10 seconds'
             WHERE id = $1 AND published_at IS NULL`,
            [row.id],
          );
        }
      }
    } catch {
      // Outbox table may not exist in non-migrated test environments
    }
  }
}
