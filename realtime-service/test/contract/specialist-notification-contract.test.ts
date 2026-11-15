import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

import SwaggerParser from '@apidevtools/swagger-parser';
import Ajv2020Import from 'ajv/dist/2020.js';
import addFormatsImport from 'ajv-formats';
import type { ProducerRecord } from 'kafkajs';
import { describe, expect, it, vi } from 'vitest';
import type { RealtimeMetrics } from '../../src/observability/metrics.js';
import { createMetrics } from '../../src/observability/metrics.js';
import { HealthController } from '../../src/health/health.controller.js';
import type { MongoDatabaseService } from '../../src/database/mongo-database.service.js';
import type { RedisService } from '../../src/database/redis.service.js';
import type { SpecialistNotificationFanout } from '../../src/notifications/specialist-notification-fanout.js';
import { testConfiguration } from '../fixtures/test-configuration.js';
import {
  NotificationRecordHandler,
  type NotificationRecord,
} from '../../src/notifications/notification-record-handler.js';
import {
  NOTIFICATION_DEAD_LETTER_TOPIC,
  NOTIFICATION_RETRY_TOPIC,
  NOTIFICATION_TOPIC,
  notificationCreatedSchema,
  notificationPointerSchema,
  notificationServerEventSchema,
  toNotificationPointer,
  toNotificationServerEvent,
} from '../../src/notifications/notification-schema.js';

const contractDirectory = new URL('../../../contracts/', import.meta.url);
const Ajv2020 = Ajv2020Import.default;
const addFormats = addFormatsImport.default;

const read = async (path: string) =>
  JSON.parse(await readFile(new URL(path, contractDirectory), 'utf8')) as Record<string, unknown>;

const validator = async (path: string) => {
  const ajv = new Ajv2020({ strict: true, allErrors: true });
  addFormats(ajv);
  return ajv.compile(await read(path));
};

describe('specialist notification v1 contracts', () => {
  it.each([true, false])(
    'reports enabled specialist transport readiness=%s under its OpenAPI schema',
    async (available) => {
      const api = await SwaggerParser.validate(
        fileURLToPath(new URL('openapi/realtime-service-v1.yaml', contractDirectory)),
      );
      if (!('components' in api)) throw new Error('Missing OpenAPI components');
      const readiness = api.components.schemas?.Readiness;
      expect(readiness).toBeDefined();
      const ajv = new Ajv2020({ strict: true, allErrors: true });
      const validate = ajv.compile(readiness as object);
      const controller = new HealthController(
        { ping: () => Promise.resolve() } as unknown as MongoDatabaseService,
        { ping: () => Promise.resolve(true) } as unknown as RedisService,
        createMetrics(testConfiguration('synthetic')),
        undefined,
        { available: () => available } as unknown as SpecialistNotificationFanout,
      );
      const result = await controller.ready();
      expect(result).toEqual({
        status: available ? 'ok' : 'degraded',
        service: 'realtime-service',
        mongodb: 'connected',
        redis: 'connected',
        specialistNotifications: available ? 'connected' : 'degraded',
      });
      expect(validate(result)).toBe(true);
      expect(validate({ ...result, specialistNotifications: 'unavailable' })).toBe(false);
    },
  );

  it('registers a bearer-protected strict owner eligibility pointer and every stable outcome', async () => {
    const api = await SwaggerParser.validate(
      fileURLToPath(new URL('openapi/content-notification-service.yaml', contractDirectory)),
    );
    const path = '/internal/v1/notifications/{notificationId}/live-eligibility';
    expect(api).toHaveProperty(['info', 'version'], '1.12.0');
    expect(api).toHaveProperty(['paths', path, 'x-mentalbridge-status'], 'implemented');
    expect(api).toHaveProperty(['paths', path, 'get', 'security'], [{ BearerAuth: [] }]);
    const operation = api.paths?.[path]?.get;
    expect(operation).toBeDefined();
    expect(operation).not.toHaveProperty('requestBody');
    expect(operation?.parameters).toEqual([
      {
        name: 'notificationId',
        in: 'path',
        required: true,
        schema: { type: 'string', format: 'uuid' },
      },
    ]);
    expect(Object.keys(operation?.responses ?? {}).sort()).toEqual([
      '200',
      '401',
      '403',
      '404',
      '422',
      '503',
    ]);
    expect(operation).toHaveProperty(
      ['responses', '200', 'headers', 'Cache-Control', 'schema', 'const'],
      'private, no-store',
    );
    if (!('components' in api)) throw new Error('Missing OpenAPI components');
    const schema = api.components.schemas?.NotificationLiveEligibility;
    expect(schema).toEqual({
      type: 'object',
      additionalProperties: false,
      required: ['notificationId'],
      properties: { notificationId: { type: 'string', format: 'uuid' } },
    });
    const ajv = new Ajv2020({ strict: true, allErrors: true });
    addFormats(ajv);
    const validate = ajv.compile(schema as object);
    expect(validate({ notificationId: '44444444-4444-4444-8444-444444444444' })).toBe(true);
    expect(validate({ notificationId: 'invalid' })).toBe(false);
    expect(
      validate({ notificationId: '44444444-4444-4444-8444-444444444444', eligible: true }),
    ).toBe(false);
  });

  it('maps the committed broker fixture to exactly the separately named socket fixture', async () => {
    const event = await read('fixtures/content/notification-created-v1.json');
    const socket = await read('fixtures/realtime/notification-created-v1.json');
    const payload = event.payload as Record<string, unknown>;
    const validateCreation = await validator('events/content/notification-created-v1.schema.json');
    const validateSocket = await validator(
      'websocket/realtime/notification-server-event-v1.schema.json',
    );
    const validateChat = await validator('websocket/realtime/server-event-v1.schema.json');
    expect(validateCreation(event)).toBe(true);
    expect(event.aggregateId).toBe(payload.notificationId);
    expect(socket).toEqual({
      schemaVersion: 1,
      eventId: event.messageId,
      eventType: 'notification.created',
      correlationId: event.correlationId,
      occurredAt: event.occurredAt,
      payload: { notificationId: payload.notificationId },
    });
    expect(validateSocket(socket)).toBe(true);
    expect(validateChat(socket)).toBe(false);
    const created = notificationCreatedSchema.parse(event);
    const pointer = notificationPointerSchema.parse(toNotificationPointer(created));
    const mapped = notificationServerEventSchema.parse(toNotificationServerEvent(pointer));
    expect(mapped).toEqual(socket);
    expect(validateSocket(mapped)).toBe(true);
  });

  it('rejects extended or malformed creation identities, roles and expiry', async () => {
    const event = await read('fixtures/content/notification-created-v1.json');
    const payload = event.payload as Record<string, unknown>;
    const validate = await validator('events/content/notification-created-v1.schema.json');
    for (const invalid of [
      { ...event, rawRecord: 'private' },
      { ...event, schemaVersion: 1 },
      { ...event, aggregateVersion: -1 },
      { ...event, causationId: 'invalid' },
      { ...event, payload: { ...payload, recipientRole: 'USER' } },
      { ...event, payload: { ...payload, recipientAccountId: 'invalid' } },
      { ...event, payload: { ...payload, expiresAt: 'invalid' } },
      { ...event, payload: { ...payload, body: 'private' } },
    ]) {
      expect(validate(invalid)).toBe(false);
    }
  });

  it('rejects every extra socket field, incompatible version and malformed pointer', async () => {
    const event = await read('fixtures/realtime/notification-created-v1.json');
    const payload = event.payload as Record<string, unknown>;
    const validate = await validator('websocket/realtime/notification-server-event-v1.schema.json');
    for (const invalid of [
      { ...event, schemaVersion: '1.0' },
      { ...event, eventId: 'invalid' },
      { ...event, correlationId: 'invalid' },
      { ...event, occurredAt: 'invalid' },
      { ...event, payload: { notificationId: 'invalid' } },
      { ...event, recipientAccountId: '55555555-5555-4555-8555-555555555555' },
      ...[
        'recipientRole',
        'appointmentId',
        'title',
        'body',
        'action',
        'unreadCount',
        'ack',
        'cursor',
      ].map((field) => ({ ...event, payload: { ...payload, [field]: 'forbidden' } })),
    ]) {
      expect(validate(invalid)).toBe(false);
    }
  });

  it('validates digest-only diagnostics and rejects raw records or unsafe metadata', async () => {
    const event = await read('fixtures/realtime/specialist-notification-dead-letter-v1.json');
    const validate = await validator(
      'events/realtime/specialist-notification-dead-letter-v1.schema.json',
    );
    expect(validate(event)).toBe(true);
    for (const invalid of [
      { ...event, rawRecord: 'private' },
      { ...event, sourceTopic: 'foreign-topic' },
      { ...event, sourcePartition: -1 },
      { ...event, sourceOffset: 42 },
      { ...event, sourceOffset: '-1' },
      { ...event, sourceMessageDigest: 'raw-record' },
      { ...event, attempt: 4 },
      { ...event, errorCode: 'unsafe-error-text' },
    ]) {
      expect(validate(invalid)).toBe(false);
    }
  });

  it('keeps the creation body/key unchanged while generating exact bounded retry headers', async () => {
    const event = await read('fixtures/content/notification-created-v1.json');
    const now = Date.parse('2026-11-07T09:00:00.000Z');
    const sent: ProducerRecord[] = [];
    const handler = new NotificationRecordHandler(
      {
        send: vi.fn((record: ProducerRecord) => {
          sent.push(record);
          return Promise.resolve([]);
        }),
      },
      vi.fn(() => Promise.reject(new Error('Redis unavailable'))),
      { commands: { inc: vi.fn() } } as unknown as RealtimeMetrics,
      () => now,
    );
    const body = Buffer.from(JSON.stringify(event));
    for (const [attempt, delay] of [
      [0, 1000],
      [1, 5000],
      [2, 30_000],
    ] as const) {
      const record: NotificationRecord = {
        topic: attempt === 0 ? NOTIFICATION_TOPIC : NOTIFICATION_RETRY_TOPIC,
        partition: 0,
        message: {
          key: Buffer.from(event.aggregateId as string),
          value: body,
          offset: '42',
          ...(attempt === 0
            ? {}
            : {
                headers: {
                  'mb-retry-attempt': Buffer.from(String(attempt)),
                  'mb-not-before': Buffer.from(new Date(now).toISOString()),
                },
              }),
        },
      };
      await handler.handle(record, now);
      expect(sent.at(-1)).toEqual({
        topic: NOTIFICATION_RETRY_TOPIC,
        acks: -1,
        messages: [
          {
            key: event.aggregateId,
            value: body,
            headers: {
              'mb-retry-attempt': String(attempt + 1),
              'mb-not-before': new Date(now + delay).toISOString(),
            },
          },
        ],
      });
    }
    const validate = await validator('events/content/notification-created-v1.schema.json');
    expect(sent).toHaveLength(3);
    for (const retry of sent) {
      expect(validate(JSON.parse(String(retry.messages[0]?.value)) as unknown)).toBe(true);
    }
  });

  it('rejects invalid retry metadata into the strict digest-only dead-letter contract', async () => {
    const event = await read('fixtures/content/notification-created-v1.json');
    const now = Date.parse('2026-11-07T09:00:00.000Z');
    const sent: ProducerRecord[] = [];
    const publish = vi.fn(() => Promise.resolve());
    const handler = new NotificationRecordHandler(
      {
        send: vi.fn((record: ProducerRecord) => {
          sent.push(record);
          return Promise.resolve([]);
        }),
      },
      publish,
      { commands: { inc: vi.fn() } } as unknown as RealtimeMetrics,
      () => now,
    );
    const validTime = '2026-11-07T09:00:00.000Z';
    for (const headers of [
      {},
      { 'mb-retry-attempt': '0', 'mb-not-before': validTime },
      { 'mb-retry-attempt': '4', 'mb-not-before': validTime },
      { 'mb-retry-attempt': '01', 'mb-not-before': validTime },
      { 'mb-retry-attempt': ['1', '2'], 'mb-not-before': validTime },
      { 'mb-retry-attempt': '1', 'mb-not-before': 'invalid' },
      { 'mb-retry-attempt': '1', 'mb-not-before': '2026-11-07T09:00:00+00:00' },
    ]) {
      await handler.handle(
        {
          topic: NOTIFICATION_RETRY_TOPIC,
          partition: 0,
          message: {
            key: Buffer.from(event.aggregateId as string),
            value: Buffer.from(JSON.stringify(event)),
            offset: '42',
            headers,
          },
        },
        now,
      );
    }
    const validate = await validator(
      'events/realtime/specialist-notification-dead-letter-v1.schema.json',
    );
    expect(publish).not.toHaveBeenCalled();
    expect(sent).toHaveLength(7);
    for (const dlq of sent) {
      expect(dlq.topic).toBe(NOTIFICATION_DEAD_LETTER_TOPIC);
      expect(dlq.acks).toBe(-1);
      const diagnostic = JSON.parse(String(dlq.messages[0]?.value)) as Record<string, unknown>;
      expect(validate(diagnostic)).toBe(true);
      expect(diagnostic.errorCode).toBe('INVALID_RETRY_METADATA');
      expect(JSON.stringify(diagnostic)).not.toContain(String(event.messageId));
      expect(JSON.stringify(diagnostic)).not.toContain(String(event.aggregateId));
    }
  });
});
