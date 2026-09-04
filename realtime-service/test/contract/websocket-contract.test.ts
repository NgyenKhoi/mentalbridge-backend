import { readFile } from 'node:fs/promises';

import Ajv2020Import from 'ajv/dist/2020.js';
import addFormatsImport from 'ajv-formats';
import { describe, expect, it } from 'vitest';

const schemaDirectory = new URL('../../../contracts/websocket/realtime/', import.meta.url);
const Ajv2020 = Ajv2020Import.default;
const addFormats = addFormatsImport.default;

const validator = async (name: string) => {
  const schema = JSON.parse(await readFile(new URL(name, schemaDirectory), 'utf8')) as object;
  const ajv = new Ajv2020({ strict: true, allErrors: true });
  addFormats(ajv);
  return ajv.compile(schema);
};

const commandId = '11111111-1111-4111-8111-111111111111';
const conversationId = '22222222-2222-4222-8222-222222222222';
const correlationId = '33333333-3333-4333-8333-333333333333';

describe('WebSocket v1 contracts', () => {
  it('validates handshake and message command examples', async () => {
    const validateHandshake = await validator('handshake-v1.schema.json');
    const validateCommand = await validator('command-envelope-v1.schema.json');
    expect(validateHandshake({ schemaVersion: 1, accessToken: 'synthetic-test-token' })).toBe(true);
    expect(
      validateCommand({
        schemaVersion: 1,
        commandId,
        commandType: 'message.send',
        correlationId,
        sentAt: '2026-09-04T12:00:00Z',
        payload: {
          conversationId,
          clientMessageId: commandId,
          type: 'TEXT',
          content: 'Synthetic contract message',
        },
      }),
    ).toBe(true);
  });

  it('validates acknowledgement and safe error examples', async () => {
    const validateAcknowledgement = await validator('acknowledgement-v1.schema.json');
    const validateError = await validator('error-v1.schema.json');
    expect(
      validateAcknowledgement({
        schemaVersion: 1,
        commandId,
        correlationId,
        status: 'accepted',
        acknowledgedAt: '2026-09-04T12:00:01Z',
        messageId: conversationId,
        liveDelivery: 'delivered',
      }),
    ).toBe(true);
    expect(
      validateError({
        schemaVersion: 1,
        commandId,
        correlationId,
        code: 'INVALID_ENVELOPE',
        message: 'Command envelope is invalid',
        retryable: false,
      }),
    ).toBe(true);
  });

  it('validates strict connection and message server events', async () => {
    const validateEvent = await validator('server-event-v1.schema.json');
    expect(
      validateEvent({
        schemaVersion: 1,
        eventId: commandId,
        eventType: 'connection.ready',
        correlationId,
        occurredAt: '2026-09-04T12:00:00Z',
        payload: { accountId: commandId, role: 'USER', presence: 'connected' },
      }),
    ).toBe(true);
    expect(
      validateEvent({
        schemaVersion: 1,
        eventId: commandId,
        eventType: 'message.created',
        correlationId,
        occurredAt: '2026-09-04T12:00:01Z',
        payload: {
          messageId: commandId,
          conversationId,
          senderId: commandId,
          clientMessageId: correlationId,
          type: 'TEXT',
          content: 'Synthetic contract message',
          sentAt: '2026-09-04T12:00:01Z',
          schemaVersion: 1,
        },
      }),
    ).toBe(true);
  });
});
