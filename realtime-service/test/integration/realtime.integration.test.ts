import { createRequire } from 'node:module';
import type { AddressInfo } from 'node:net';
import { randomUUID } from 'node:crypto';

import type { INestApplication } from '@nestjs/common';
import { exportSPKI, generateKeyPair } from 'jose';
import { MongoClient } from 'mongodb';
import { io, type Socket } from 'socket.io-client';
import request from 'supertest';
import { GenericContainer, type StartedTestContainer, Wait } from 'testcontainers';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

import { createApplication } from '../../src/application.js';
import type { ConversationEligibility } from '../../src/conversations/conversation-eligibility.js';
import type { ServiceConfiguration } from '../../src/configuration/configuration.js';
import { PresenceService } from '../../src/presence/presence.service.js';
import { testConfiguration } from '../fixtures/test-configuration.js';
import { issueToken } from '../fixtures/token.js';

const require = createRequire(import.meta.url);
const migration = require('../../migrations/001_realtime_message_foundation.cjs') as {
  up(database: ReturnType<MongoClient['db']>): Promise<void>;
};

const accountId = '11111111-1111-4111-8111-111111111111';
const conversationId = '22222222-2222-4222-8222-222222222222';
const eligibility: ConversationEligibility = { assertEligible: () => Promise.resolve() };

describe('Realtime MongoDB, Redis and Socket.IO integration', { concurrent: false }, () => {
  let mongoContainer: StartedTestContainer;
  let redisContainer: StartedTestContainer;
  let mongoClient: MongoClient;
  let app: INestApplication;
  let configuration: ServiceConfiguration;
  let token: string;
  let baseUrl: string;
  let redisStopped = false;

  beforeAll(async () => {
    [mongoContainer, redisContainer] = await Promise.all([
      new GenericContainer('mongo:8.0')
        .withExposedPorts(27017)
        .withWaitStrategy(Wait.forLogMessage(/Waiting for connections/))
        .start(),
      new GenericContainer('redis:7.4-alpine')
        .withExposedPorts(6379)
        .withWaitStrategy(Wait.forLogMessage(/Ready to accept connections/))
        .start(),
    ]);

    const mongoUri = `mongodb://${mongoContainer.getHost()}:${String(mongoContainer.getMappedPort(27017))}`;
    const redisUrl = `redis://${redisContainer.getHost()}:${String(redisContainer.getMappedPort(6379))}`;
    const databaseName = `mentalbridge_realtime_${randomUUID().replaceAll('-', '')}`;
    const keys = await generateKeyPair('RS256', { extractable: true });
    configuration = testConfiguration(await exportSPKI(keys.publicKey), {
      MONGODB_URI: mongoUri,
      MONGODB_DATABASE: databaseName,
      REDIS_URL: redisUrl,
      PRESENCE_TTL_SECONDS: 2,
      PRESENCE_HEARTBEAT_SECONDS: 1,
    });
    token = await issueToken(keys.privateKey, configuration, accountId);
    mongoClient = new MongoClient(mongoUri);
    await mongoClient.connect();
    await migration.up(mongoClient.db(databaseName));

    app = await createApplication(configuration, { eligibility });
    await app.listen(0);
    const httpServer = app.getHttpServer() as unknown as {
      address(): AddressInfo | string | null;
    };
    const address = httpServer.address();
    if (!address || typeof address === 'string')
      throw new Error('Realtime test server address is unavailable');
    baseUrl = `http://127.0.0.1:${String(address.port)}`;
  });

  afterAll(async () => {
    await app.close();
    await mongoClient.db(configuration.MONGODB_DATABASE).dropDatabase();
    await mongoClient.close();
    await mongoContainer.stop();
    if (!redisStopped) await redisContainer.stop();
  });

  it('applies strict validators and required indexes', async () => {
    const database = mongoClient.db(configuration.MONGODB_DATABASE);
    await expect(
      database.collection('messages').insertOne({ content: 'plaintext' }),
    ).rejects.toThrow();
    const indexes = await database.collection('messages').indexes();
    expect(indexes.map((index) => index.name)).toEqual(
      expect.arrayContaining([
        'messages_id_unique_idx',
        'messages_conversation_cursor_idx',
        'messages_sender_client_unique_idx',
      ]),
    );
  });

  it('rejects an unauthenticated Socket.IO handshake', async () => {
    const socket = io(`${baseUrl}/realtime`, {
      transports: ['websocket'],
      auth: { schemaVersion: 1, accessToken: 'invalid' },
      reconnection: false,
    });
    try {
      const error = await new Promise<Error>((resolve) => socket.once('connect_error', resolve));
      expect(error.message).toBe('Authentication failed');
    } finally {
      socket.close();
    }
  });

  it('authenticates, maintains TTL presence and expires abandoned routing', async () => {
    const socket = await connectClient();
    try {
      const presence = app.get(PresenceService);
      expect(await presence.status(accountId)).toBe('online');
      await command(socket, 'presence.heartbeat', {});
      expect(await presence.status(accountId)).toBe('online');
      const boundedAccountId = '33333333-3333-4333-8333-333333333333';
      const boundedRegistrations = await Promise.all(
        Array.from({ length: 6 }, (_, index) =>
          presence.register(boundedAccountId, `bounded-socket-${String(index)}`),
        ),
      );
      expect(boundedRegistrations.filter((result) => result === 'connected')).toHaveLength(5);
      expect(boundedRegistrations.filter((result) => result === 'limit_exceeded')).toHaveLength(1);
      await new Promise((resolve) => setTimeout(resolve, 2200));
      expect(await presence.status(accountId)).toBe('offline');
    } finally {
      socket.close();
    }
  });

  it('rejects incompatible, malformed and rate-limited commands safely', async () => {
    const socket = await connectClient();
    try {
      const incompatible = await rawCommand(socket, {
        schemaVersion: 2,
        commandId: randomUUID(),
        commandType: 'presence.heartbeat',
        correlationId: randomUUID(),
        sentAt: new Date().toISOString(),
        payload: {},
      });
      expect(incompatible.code).toBe('UNSUPPORTED_SCHEMA_VERSION');

      const malformed = await rawCommand(socket, {
        schemaVersion: 1,
        commandId: randomUUID(),
        commandType: 'message.send',
        correlationId: randomUUID(),
        sentAt: new Date().toISOString(),
        payload: { senderId: accountId },
      });
      expect(malformed.code).toBe('INVALID_ENVELOPE');

      const acknowledgements = [];
      for (let index = 0; index < configuration.COMMAND_RATE_LIMIT; index += 1) {
        acknowledgements.push(await command(socket, 'presence.heartbeat', {}));
      }
      expect(acknowledgements.at(-1)?.code).toBe('RATE_LIMITED');
    } finally {
      socket.close();
    }
  });

  it('disconnects a client that exceeds the transport payload limit', async () => {
    const socket = await connectClient();
    const disconnected = new Promise<void>((resolve) => {
      socket.once('disconnect', () => {
        resolve();
      });
    });
    socket.emit('realtime.command', {
      schemaVersion: 1,
      commandId: randomUUID(),
      commandType: 'message.send',
      correlationId: randomUUID(),
      sentAt: new Date().toISOString(),
      payload: {
        conversationId,
        clientMessageId: randomUUID(),
        type: 'TEXT',
        content: 'x'.repeat(configuration.MAX_PAYLOAD_BYTES),
      },
    });
    await disconnected;
    expect(socket.connected).toBe(false);
  });

  it('persists before acknowledgement, deduplicates and recovers ordered history', async () => {
    const socket = await connectClient();
    try {
      await command(socket, 'conversation.subscribe', { conversationId });
      const clientMessageId = randomUUID();
      const repeatedInput = {
        conversationId,
        clientMessageId,
        type: 'TEXT',
        content: 'Tin nhắn bền vững',
      };
      const results = await Promise.all([
        command(socket, 'message.send', repeatedInput),
        command(socket, 'message.send', repeatedInput),
      ]);
      const first = results.find((result) => result.status === 'accepted');
      const duplicate = results.find((result) => result.status === 'duplicate');
      expect(first).toBeDefined();
      expect(duplicate).toBeDefined();
      if (!first || !duplicate) throw new Error('Concurrent idempotency outcome is incomplete');
      expect(duplicate.status).toBe('duplicate');
      expect(duplicate.messageId).toBe(first.messageId);

      const conflict = await command(socket, 'message.send', {
        conversationId,
        clientMessageId,
        type: 'TEXT',
        content: 'Nội dung khác',
      });
      expect(conflict.code).toBe('IDEMPOTENCY_CONFLICT');

      await command(socket, 'message.send', {
        conversationId,
        clientMessageId: randomUUID(),
        type: 'TEXT',
        content: 'Tin nhắn thứ hai',
      });

      const firstPage = await request(baseUrl)
        .get(`/api/v1/conversations/${conversationId}/messages?limit=1`)
        .set('authorization', `Bearer ${token}`)
        .expect(200);
      const firstBody = firstPage.body as {
        items: { messageId: string }[];
        hasMore: boolean;
        nextCursor: string;
      };
      expect(firstBody.items).toHaveLength(1);
      expect(firstBody.hasMore).toBe(true);
      expect(firstBody.nextCursor).toEqual(expect.any(String));
      const secondPage = await request(baseUrl)
        .get(`/api/v1/conversations/${conversationId}/messages`)
        .query({ limit: 1, cursor: firstBody.nextCursor })
        .set('authorization', `Bearer ${token}`)
        .expect(200);
      const secondBody = secondPage.body as { items: { messageId: string }[] };
      expect(secondBody.items).toHaveLength(1);
      expect(secondBody.items[0]?.messageId).not.toBe(firstBody.items[0]?.messageId);

      const stored = await mongoClient
        .db(configuration.MONGODB_DATABASE)
        .collection('messages')
        .findOne({ messageId: first.messageId });
      expect(stored).toBeTruthy();
      if (!stored) throw new Error('Persisted message is missing');
      expect(stored).not.toHaveProperty('content');
      expect(stored.bodyCiphertext).not.toContain('Tin nhắn');
    } finally {
      socket.close();
    }
  });

  it('keeps durable messaging available when Redis presence degrades', async () => {
    const socket = await connectClient();
    await redisContainer.stop();
    redisStopped = true;
    try {
      expect(await app.get(PresenceService).status(accountId)).toBe('unknown');
      const acknowledgement = await command(socket, 'message.send', {
        conversationId,
        clientMessageId: randomUUID(),
        type: 'TEXT',
        content: 'MongoDB vẫn là nguồn bền vững',
      });
      expect(acknowledgement.status).toBe('accepted');
    } finally {
      socket.close();
    }
  });

  async function connectClient(): Promise<Socket> {
    const socket = io(`${baseUrl}/realtime`, {
      transports: ['websocket'],
      auth: { schemaVersion: 1, accessToken: token, correlationId: randomUUID() },
      reconnection: false,
      autoConnect: false,
    });
    const connected = new Promise<void>((resolve, reject) => {
      socket.once('realtime.event', (event: { eventType?: unknown }) => {
        if (event.eventType === 'connection.ready') resolve();
      });
      socket.once('connect_error', reject);
    });
    socket.connect();
    await connected;
    return socket;
  }

  function command(
    socket: Socket,
    commandType: string,
    payload: object,
  ): Promise<Record<string, unknown>> {
    return new Promise((resolve) => {
      socket.emit(
        'realtime.command',
        {
          schemaVersion: 1,
          commandId: randomUUID(),
          commandType,
          correlationId: randomUUID(),
          sentAt: new Date().toISOString(),
          payload,
        },
        resolve,
      );
    });
  }

  function rawCommand(socket: Socket, input: object): Promise<Record<string, unknown>> {
    return new Promise((resolve) => {
      socket.emit('realtime.command', input, resolve);
    });
  }
});
