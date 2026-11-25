import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import SwaggerParser from '@apidevtools/swagger-parser';
import Ajv2020Import from 'ajv/dist/2020.js';
import addFormatsImport from 'ajv-formats';
import { describe, expect, it } from 'vitest';
import { z } from 'zod';
import { factSchema } from '../../src/community/community-schema.js';
import { communityConversation, communityBob } from '../fixtures/community-owner.js';

const Ajv2020 = Ajv2020Import.default;
const addFormats = addFormatsImport.default;
const compile = async (path: string) => {
  const ajv = new Ajv2020({ strict: true, allErrors: true });
  addFormats(ajv);
  const schema: unknown = JSON.parse(
    await readFile(new URL('../../../contracts/' + path, import.meta.url), 'utf8'),
  );
  if (typeof schema !== 'object' || schema === null) throw new Error('Missing schema');
  return ajv.compile(schema);
};
describe('Community MB-689 runtime contracts', () => {
  it('defines an isolated closed native USER exchange without changing BFF authentication', async () => {
    const api = await SwaggerParser.validate(
      fileURLToPath(
        new URL('../../../contracts/openapi/realtime-service-v1.yaml', import.meta.url),
      ),
    );
    const path = '/api/v1/community/socket-credentials';
    expect(api).toHaveProperty(['paths', path, 'x-mentalbridge-status'], 'implemented');
    expect(api).toHaveProperty(['paths', path, 'post', 'security'], [{ IdentityBearerAuth: [] }]);
    expect(api).toHaveProperty(
      ['paths', path, 'post', 'requestBody', 'content', 'application/json', 'schema'],
      { type: 'object', additionalProperties: false, maxProperties: 0 },
    );
    expect(api).toHaveProperty(
      ['paths', '/internal/v1/community/socket-credentials', 'post', 'security'],
      [{ RealtimeDelegation: [] }],
    );
  });
  it('validates the additive owner and credential APIs', async () => {
    for (const path of ['community-chat-v1.json', 'community-realtime-v1.json']) {
      const api = await SwaggerParser.validate(
        fileURLToPath(new URL('../../../contracts/openapi/' + path, import.meta.url)),
      );
      expect(z.object({ openapi: z.string() }).parse(api).openapi).toBe('3.1.0');
    }
  });
  it('registers delegated operations in both canonical service APIs', async () => {
    for (const [contract, path, operationId] of [
      [
        'realtime-service-v1.yaml',
        '/internal/v1/community/socket-credentials',
        'createCommunitySocketCredential',
      ],
      [
        'community-service-v1.yaml',
        '/internal/v1/community/chat/session-authorizations',
        'authorizeCommunitySession',
      ],
      [
        'community-service-v1.yaml',
        '/internal/v1/community/chat/presence-projections',
        'projectCommunityPresence',
      ],
    ] as const) {
      const api = await SwaggerParser.validate(
        fileURLToPath(new URL('../../../contracts/openapi/' + contract, import.meta.url)),
      );
      expect(api).toHaveProperty(['paths', path, 'x-mentalbridge-status'], 'implemented');
      expect(api).toHaveProperty(['paths', path, 'post', 'operationId'], operationId);
      expect(api).toHaveProperty(['paths', path, 'post', 'security'], [{ RealtimeDelegation: [] }]);
      expect(api).toHaveProperty(
        ['components', 'securitySchemes', 'RealtimeDelegation'],
        expect.objectContaining({ type: 'http', scheme: 'bearer', bearerFormat: 'JWT' }),
      );
    }
  });
  it('closes command, handshake, private state and acknowledgement variants', async () => {
    const handshake = await compile('websocket/community/handshake-v1.schema.json');
    expect(handshake({ schemaVersion: 1, credential: 'x'.repeat(43) })).toBe(true);
    expect(handshake({ schemaVersion: 1, accessToken: 'x'.repeat(43) })).toBe(false);
    const command = await compile('websocket/community/command-v1.schema.json');
    const value = {
      schemaVersion: 1,
      commandId: randomUUID(),
      correlationId: randomUUID(),
      commandType: 'community.conversation.subscribe',
      payload: { conversationId: communityConversation },
    };
    expect(command(value)).toBe(true);
    const heartbeat = {
      ...value,
      commandType: 'community.presence.heartbeat',
      payload: { foreground: true },
    };
    expect(command(heartbeat)).toBe(true);
    expect(command({ ...heartbeat, payload: { foreground: true, ttl: 60000 } })).toBe(false);
    expect(command({ ...value, payload: { ...value.payload, actor: communityBob } })).toBe(false);
    const event = await compile('websocket/community/event-v1.schema.json');
    const header = {
      schemaVersion: 1,
      eventId: randomUUID(),
      correlationId: randomUUID(),
      occurredAt: new Date().toISOString(),
    };
    const state = {
      ...header,
      eventType: 'community.read-state.changed',
      payload: { conversationId: communityConversation, lastReadMessageId: null, unreadCount: 0 },
    };
    expect(event(state)).toBe(true);
    expect(event({ ...state, payload: { ...state.payload, sequence: '10' } })).toBe(false);
    const presence = {
      ...header,
      eventType: 'community.presence.changed',
      payload: { communityProfileId: communityBob, state: 'ONLINE' },
    };
    expect(event(presence)).toBe(true);
    for (const field of ['subjectRoutingReference', 'lastSeen', 'expiresAt', 'invisible'])
      expect(event({ ...presence, payload: { ...presence.payload, [field]: communityBob } })).toBe(
        false,
      );
    const retracted = {
      ...header,
      eventType: 'community.message.updated',
      payload: {
        message: {
          messageId: randomUUID(),
          conversationId: communityConversation,
          clientMessageId: randomUUID(),
          sender: {
            state: 'AVAILABLE',
            communityProfileId: communityBob,
            displayName: 'Thành viên cộng đồng',
          },
          state: 'RETRACTED',
          createdAt: new Date().toISOString(),
          retractedAt: new Date().toISOString(),
        },
      },
    };
    expect(event(retracted)).toBe(true);
    expect(
      event({
        ...retracted,
        payload: { message: { ...retracted.payload.message, content: 'private' } },
      }),
    ).toBe(false);
    expect(
      event({
        ...header,
        eventType: 'community.command.ack',
        payload: { commandId: randomUUID(), status: 'ACCEPTED' },
      }),
    ).toBe(true);
  });
  it('accepts private self read-state facts without content or global counters', async () => {
    const fact = await compile('events/community/community-chat-fact-v1.schema.json');
    const value = {
      messageId: randomUUID(),
      messageType: 'community.chat.fact',
      schemaVersion: '1.0',
      producer: 'community-service',
      occurredAt: new Date().toISOString(),
      correlationId: randomUUID(),
      aggregateId: communityConversation,
      aggregateVersion: '2',
      payload: {
        kind: 'READ_STATE_CHANGED',
        conversationId: communityConversation,
        recipientRoutingReference: communityBob,
      },
    };
    expect(fact(value)).toBe(true);
    expect(factSchema.safeParse(value).success).toBe(true);
    for (const field of ['content', 'sequence', 'lastReadMessageId', 'unreadCount', 'actorId']) {
      const forged = { ...value, payload: { ...value.payload, [field]: 'forged' } };
      expect(fact(forged)).toBe(false);
      expect(factSchema.safeParse(forged).success).toBe(false);
    }
  });
});
