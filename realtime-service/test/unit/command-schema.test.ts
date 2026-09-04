import { randomUUID } from 'node:crypto';

import { describe, expect, it } from 'vitest';

import { commandSchema } from '../../src/websocket/command.schema.js';

const base = {
  schemaVersion: 1,
  commandId: randomUUID(),
  correlationId: randomUUID(),
  sentAt: new Date().toISOString(),
};

describe('Realtime command envelope', () => {
  it('accepts a strict message command', () => {
    expect(
      commandSchema.safeParse({
        ...base,
        commandType: 'message.send',
        payload: {
          conversationId: randomUUID(),
          clientMessageId: randomUUID(),
          type: 'TEXT',
          content: 'Xin chào',
        },
      }).success,
    ).toBe(true);
  });

  it('rejects client-supplied actor context', () => {
    expect(
      commandSchema.safeParse({
        ...base,
        commandType: 'message.send',
        payload: {
          conversationId: randomUUID(),
          clientMessageId: randomUUID(),
          type: 'TEXT',
          content: 'Xin chào',
          senderId: randomUUID(),
        },
      }).success,
    ).toBe(false);
  });

  it('rejects unsupported versions and unknown commands', () => {
    expect(
      commandSchema.safeParse({
        ...base,
        schemaVersion: 2,
        commandType: 'history.read',
        payload: {},
      }).success,
    ).toBe(false);
  });
});
