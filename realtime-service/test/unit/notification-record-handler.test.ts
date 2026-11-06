import { randomUUID } from 'node:crypto';
import type { Producer } from 'kafkajs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  NotificationRecordHandler,
  type NotificationRecord,
} from '../../src/notifications/notification-record-handler.js';
import {
  NOTIFICATION_TOPIC,
  NOTIFICATION_RETRY_TOPIC,
  NOTIFICATION_DEAD_LETTER_TOPIC,
  type NotificationCreated,
  type NotificationPointer,
} from '../../src/notifications/notification-schema.js';
import { createMetrics } from '../../src/observability/metrics.js';
import { testConfiguration } from '../fixtures/test-configuration.js';

const now = Date.now();
function fact(): NotificationCreated {
  const notificationId = randomUUID();
  return {
    messageId: randomUUID(),
    messageType: 'content.notification.created',
    schemaVersion: '1.0',
    producer: 'content-notification-service',
    correlationId: randomUUID(),
    causationId: randomUUID(),
    occurredAt: new Date(now - 1000).toISOString(),
    aggregateId: notificationId,
    aggregateVersion: 0,
    payload: {
      notificationId,
      recipientAccountId: randomUUID(),
      recipientRole: 'SPECIALIST',
      expiresAt: new Date(now + 60_000).toISOString(),
    },
  };
}

describe('Specialist notification broker handoff', () => {
  const send = vi.fn<Producer['send']>();
  const publish = vi.fn<(pointer: NotificationPointer) => Promise<void>>();
  let handler: NotificationRecordHandler;
  let event: NotificationCreated;
  let record: NotificationRecord;
  let clock: number;
  beforeEach(() => {
    send.mockReset().mockResolvedValue([]);
    publish.mockReset().mockResolvedValue();
    clock = now;
    handler = new NotificationRecordHandler(
      { send },
      publish,
      createMetrics(testConfiguration('synthetic')),
      () => clock,
    );
    event = fact();
    record = {
      topic: NOTIFICATION_TOPIC,
      partition: 0,
      message: {
        offset: '15',
        key: Buffer.from(event.aggregateId),
        value: Buffer.from(JSON.stringify(event)),
      },
    };
  });

  it('resolves only after Redis acknowledges the minimized pointer', async () => {
    let release: () => void = () => undefined;
    publish.mockReturnValue(
      new Promise<void>((resolve) => {
        release = resolve;
      }),
    );
    let resolved = false;
    const pending = handler.handle(record, now).then(() => {
      resolved = true;
    });
    await Promise.resolve();
    expect(resolved).toBe(false);
    expect(publish).toHaveBeenCalledWith({
      schemaVersion: 1,
      eventId: event.messageId,
      notificationId: event.aggregateId,
      recipientAccountId: event.payload.recipientAccountId,
      correlationId: event.correlationId,
      occurredAt: event.occurredAt,
      expiresAt: event.payload.expiresAt,
    });
    release();
    await pending;
    expect(send).not.toHaveBeenCalled();
  });

  it.each(['key', 'aggregate', 'role', 'raw'] as const)(
    'parks a %s mismatch without publishing',
    async (kind) => {
      const payload = {
        ...event.payload,
        ...(kind === 'role' ? { recipientRole: 'USER' } : {}),
        ...(kind === 'raw' ? { body: 'secret' } : {}),
      };
      const invalid = {
        ...event,
        payload,
        ...(kind === 'aggregate' ? { aggregateId: randomUUID() } : {}),
      };
      await handler.handle(
        {
          ...record,
          message: {
            ...record.message,
            key: Buffer.from(kind === 'key' ? randomUUID() : event.aggregateId),
            value: Buffer.from(JSON.stringify(invalid)),
          },
        },
        now,
      );
      expect(publish).not.toHaveBeenCalled();
      expect(send).toHaveBeenCalledWith(
        expect.objectContaining({ topic: NOTIFICATION_DEAD_LETTER_TOPIC, acks: -1 }),
      );
      expect(JSON.stringify(send.mock.calls)).not.toContain('secret');
    },
  );

  it('suppresses an already expired creation without Redis or retry', async () => {
    await handler.handle(
      {
        ...record,
        message: {
          ...record.message,
          value: Buffer.from(
            JSON.stringify({
              ...event,
              payload: { ...event.payload, expiresAt: new Date(now).toISOString() },
            }),
          ),
        },
      },
      now,
    );
    expect(publish).not.toHaveBeenCalled();
    expect(send).not.toHaveBeenCalled();
  });

  it('leaves the original offset unresolved when the retry handoff fails', async () => {
    publish.mockRejectedValue(new Error('Redis unavailable'));
    send.mockRejectedValue(new Error('Broker unavailable'));
    await expect(handler.handle(record, now)).rejects.toThrow('Broker unavailable');
  });

  it('starts retry backoff after a slow Redis failure rather than the original read time', async () => {
    publish.mockImplementationOnce(() => {
      clock = now + 5000;
      return Promise.reject(new Error('Slow Redis failure'));
    });
    await handler.handle(record, now);
    expect(send).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({
        topic: NOTIFICATION_RETRY_TOPIC,
        messages: [
          expect.objectContaining({
            headers: {
              'mb-retry-attempt': '1',
              'mb-not-before': new Date(now + 6000).toISOString(),
            },
          }),
        ],
      }),
    );
  });

  it.each([0, 3])(
    'suppresses expiry during Redis publication instead of retrying attempt %i',
    async (attempt) => {
      publish.mockImplementationOnce(() => {
        clock = now + 60_001;
        return Promise.reject(new Error('Slow Redis failure'));
      });
      await handler.handle(
        attempt === 0
          ? record
          : {
              ...record,
              topic: NOTIFICATION_RETRY_TOPIC,
              message: {
                ...record.message,
                headers: { 'mb-retry-attempt': '3', 'mb-not-before': new Date(now).toISOString() },
              },
            },
        now,
      );
      expect(send).not.toHaveBeenCalled();
    },
  );

  it('leaves poison unresolved until the digest-only DLQ is acknowledged', async () => {
    send.mockRejectedValue(new Error('Broker unavailable'));
    await expect(
      handler.handle(
        { ...record, message: { ...record.message, value: Buffer.from('{bad') } },
        now,
      ),
    ).rejects.toThrow('Broker unavailable');
  });

  it('defers a future retry without blocking publication from the main topic', async () => {
    const retry = {
      ...record,
      topic: NOTIFICATION_RETRY_TOPIC,
      message: {
        ...record.message,
        headers: { 'mb-retry-attempt': '1', 'mb-not-before': new Date(now + 1000).toISOString() },
      },
    };
    expect(await handler.handle(retry, now)).toBe(now + 1000);
    expect(publish).not.toHaveBeenCalled();
    await handler.handle(record, now);
    expect(publish).toHaveBeenCalledOnce();
    await handler.handle(retry, now + 1000);
    expect(publish).toHaveBeenCalledTimes(2);
  });

  it.each([
    { 'mb-retry-attempt': '01', 'mb-not-before': new Date(now).toISOString() },
    { 'mb-retry-attempt': '4', 'mb-not-before': new Date(now).toISOString() },
    { 'mb-retry-attempt': '1', 'mb-not-before': '2026-02-31T00:00:00Z' },
    { 'mb-retry-attempt': '1', 'mb-not-before': '2026-01-01T00:00:00+07:00' },
    { 'mb-retry-attempt': '1', 'mb-not-before': new Date(now + 60_000).toISOString() },
    { 'mb-retry-attempt': ['1', '2'], 'mb-not-before': new Date(now).toISOString() },
  ])('parks invalid retry metadata %#', async (headers) => {
    await handler.handle(
      { ...record, topic: NOTIFICATION_RETRY_TOPIC, message: { ...record.message, headers } },
      now,
    );
    expect(publish).not.toHaveBeenCalled();
    expect(JSON.stringify(send.mock.calls)).toContain('INVALID_RETRY_METADATA');
  });

  it('parks attempt three after a transient failure with only a digest and safe diagnostics', async () => {
    publish.mockRejectedValue(new Error('Redis unavailable'));
    await handler.handle(
      {
        ...record,
        topic: NOTIFICATION_RETRY_TOPIC,
        message: {
          ...record.message,
          headers: { 'mb-retry-attempt': '3', 'mb-not-before': new Date(now - 1).toISOString() },
        },
      },
      now,
    );
    expect(JSON.stringify(send.mock.calls)).toContain('TRANSIENT_RETRIES_EXHAUSTED');
    expect(JSON.stringify(send.mock.calls)).not.toContain(event.payload.recipientAccountId);
  });
});
