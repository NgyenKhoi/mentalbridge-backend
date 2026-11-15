import { createHash } from 'node:crypto';
import type { KafkaMessage, Producer } from 'kafkajs';
import { z } from 'zod';
import type { RealtimeMetrics } from '../observability/metrics.js';
import {
  NOTIFICATION_TOPIC,
  NOTIFICATION_RETRY_TOPIC,
  NOTIFICATION_DEAD_LETTER_TOPIC,
  notificationCreatedSchema,
  toNotificationPointer,
  type NotificationPointer,
} from './notification-schema.js';

export interface NotificationRecord {
  readonly topic: string;
  readonly partition: number;
  readonly message: Pick<KafkaMessage, 'key' | 'value' | 'headers' | 'offset'>;
}

type ErrorCode =
  | 'INVALID_JSON'
  | 'INVALID_EVENT'
  | 'IDENTITY_MISMATCH'
  | 'INVALID_RETRY_METADATA'
  | 'TRANSIENT_RETRIES_EXHAUSTED';
const retryDelays = [1000, 5000, 30_000] as const;

export class NotificationRecordHandler {
  constructor(
    private readonly producer: Pick<Producer, 'send'>,
    private readonly publish: (pointer: NotificationPointer) => Promise<void>,
    private readonly metrics: RealtimeMetrics,
    private readonly clock: () => number = Date.now,
  ) {}

  async handle(record: NotificationRecord, now = this.clock()): Promise<number | undefined> {
    const metadata = this.retryMetadata(record, now);
    if (!metadata) {
      await this.deadLetter(record, 'INVALID_RETRY_METADATA', 0);
      return;
    }
    let input: unknown;
    try {
      if (!record.message.value || record.message.value.length > 16_384)
        throw new Error('Invalid JSON');
      input = JSON.parse(record.message.value.toString('utf8'));
    } catch {
      await this.deadLetter(record, 'INVALID_JSON', metadata.attempt);
      return;
    }
    const parsed = notificationCreatedSchema.safeParse(input);
    if (!parsed.success) {
      await this.deadLetter(record, 'INVALID_EVENT', metadata.attempt);
      return;
    }
    const event = parsed.data;
    if (
      event.aggregateId !== event.payload.notificationId ||
      record.message.key?.toString('utf8') !== event.aggregateId
    ) {
      await this.deadLetter(record, 'IDENTITY_MISMATCH', metadata.attempt);
      return;
    }
    if (Date.parse(event.payload.expiresAt) <= now) {
      this.metrics.commands.inc({ command: 'notification.consumer', result: 'expired' });
      return;
    }
    if (metadata.notBefore > now) return metadata.notBefore;
    try {
      await this.publish(toNotificationPointer(event));
      this.metrics.commands.inc({ command: 'notification.consumer', result: 'published' });
    } catch {
      const failedAt = this.clock();
      if (Date.parse(event.payload.expiresAt) <= failedAt) {
        this.metrics.commands.inc({ command: 'notification.consumer', result: 'expired' });
        return;
      }
      const delay = retryDelays[metadata.attempt];
      if (delay === undefined) {
        await this.deadLetter(record, 'TRANSIENT_RETRIES_EXHAUSTED', metadata.attempt);
        return;
      }
      await this.producer.send({
        topic: NOTIFICATION_RETRY_TOPIC,
        acks: -1,
        messages: [
          {
            key: event.aggregateId,
            value: record.message.value,
            headers: {
              'mb-retry-attempt': String(metadata.attempt + 1),
              'mb-not-before': new Date(failedAt + delay).toISOString(),
            },
          },
        ],
      });
      this.metrics.commands.inc({ command: 'notification.consumer', result: 'retry' });
    }
  }

  private retryMetadata(
    record: NotificationRecord,
    now: number,
  ): { attempt: number; notBefore: number } | undefined {
    const attempt = record.message.headers?.['mb-retry-attempt'];
    const notBefore = record.message.headers?.['mb-not-before'];
    if (record.topic === NOTIFICATION_TOPIC)
      return attempt === undefined && notBefore === undefined
        ? { attempt: 0, notBefore: 0 }
        : undefined;
    if (
      record.topic !== NOTIFICATION_RETRY_TOPIC ||
      attempt === undefined ||
      notBefore === undefined ||
      Array.isArray(attempt) ||
      Array.isArray(notBefore)
    )
      return;
    const attemptText = attempt.toString();
    const timeText = notBefore.toString();
    if (!/^[1-3]$/.test(attemptText) || !z.iso.datetime().safeParse(timeText).success) return;
    const time = Date.parse(timeText);
    if (!Number.isFinite(time) || time > now + 31_000) return;
    return { attempt: Number(attemptText), notBefore: time };
  }

  private async deadLetter(
    record: NotificationRecord,
    errorCode: ErrorCode,
    attempt: number,
  ): Promise<void> {
    await this.producer.send({
      topic: NOTIFICATION_DEAD_LETTER_TOPIC,
      acks: -1,
      messages: [
        {
          key: `${record.topic}:${String(record.partition)}:${record.message.offset}`,
          value: JSON.stringify({
            schemaVersion: '1.0',
            failedAt: new Date(this.clock()).toISOString(),
            sourceTopic: record.topic,
            sourcePartition: record.partition,
            sourceOffset: record.message.offset,
            sourceMessageDigest: createHash('sha256')
              .update(record.message.value ?? Buffer.alloc(0))
              .digest('hex'),
            attempt,
            errorCode,
          }),
        },
      ],
    });
    this.metrics.commands.inc({ command: 'notification.consumer', result: errorCode });
  }
}
