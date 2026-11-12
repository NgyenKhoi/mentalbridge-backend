import { createRequire } from 'node:module';
import type { AddressInfo } from 'node:net';
import { randomUUID } from 'node:crypto';

import type { INestApplication } from '@nestjs/common';
import { exportSPKI, generateKeyPair } from 'jose';
import { MongoClient, ObjectId } from 'mongodb';
import { io, type Socket } from 'socket.io-client';
import request from 'supertest';
import { GenericContainer, type StartedTestContainer, Wait } from 'testcontainers';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import { createApplication } from '../../src/application.js';
import type { ConversationEligibility } from '../../src/conversations/conversation-eligibility.js';
import type {
  ConversationEvidence,
  ConversationEvidenceInput,
} from '../../src/conversations/conversation-evidence.js';
import type { ServiceConfiguration } from '../../src/configuration/configuration.js';
import { RedisService } from '../../src/database/redis.service.js';
import { MessageEncryptionService } from '../../src/messages/message-encryption.service.js';
import { AppointmentModerationService } from '../../src/moderation/appointment-moderation.service.js';
import { PresenceService } from '../../src/presence/presence.service.js';
import type { AuthenticatedPrincipal } from '../../src/security/principal.js';
import { testConfiguration } from '../fixtures/test-configuration.js';
import { issueToken } from '../fixtures/token.js';

const require = createRequire(import.meta.url);
const migration = require('../../migrations/001_realtime_message_foundation.cjs') as {
  up(database: ReturnType<MongoClient['db']>): Promise<void>;
};
const moderationMigration = require('../../migrations/002_appointment_message_moderation.cjs') as {
  up(database: ReturnType<MongoClient['db']>): Promise<void>;
};

const accountId = '11111111-1111-4111-8111-111111111111';
const conversationId = '22222222-2222-4222-8222-222222222222';
const specialistId = '44444444-4444-4444-8444-444444444444';
const eligibility: ConversationEligibility = {
  check: (_bearer, _account, requestedConversationId) =>
    Promise.resolve({
      conversationId: requestedConversationId,
      appointmentId: requestedConversationId,
      userAccountId: accountId,
      specialistAccountId: specialistId,
      phase: 'ACTIVE',
      reasonCode: 'APPOINTMENT_ACTIVE',
      subscribeAllowed: true,
      sendAllowed: true,
      historyAllowed: true,
      checkInAllowed: true,
      participantCheckedIn: false,
      sessionOutcome: null,
      creditState: 'HELD',
      scheduledStartAt: new Date(Date.now() - 60_000).toISOString(),
      scheduledEndAt: new Date(Date.now() + 3_540_000).toISOString(),
      serverTime: new Date().toISOString(),
    }),
};
const evidenceRecords: ConversationEvidenceInput[] = [];
const evidence: ConversationEvidence = {
  record: (_bearer, _appointment, input) => {
    evidenceRecords.push(input);
    return Promise.resolve();
  },
};

describe('Realtime MongoDB, Redis and Socket.IO integration', { concurrent: false }, () => {
  let mongoContainer: StartedTestContainer;
  let redisContainer: StartedTestContainer;
  let mongoClient: MongoClient;
  let app: INestApplication;
  let configuration: ServiceConfiguration;
  let token: string;
  let identityPrivateKey: Parameters<typeof issueToken>[0];
  let baseUrl: string;
  let redisStopped = false;

  beforeEach(() => evidenceRecords.splice(0));

  beforeAll(async () => {
    [mongoContainer, redisContainer] = await Promise.all([
      new GenericContainer('mongo:8.0')
        .withEnvironment({ GLIBC_TUNABLES: 'glibc.pthread.rseq=1' })
        .withCommand([
          '--replSet',
          'rs0',
          '--bind_ip_all',
          '--setParameter',
          'enableTestCommands=1',
        ])
        .withExposedPorts(27017)
        .withWaitStrategy(Wait.forLogMessage(/Waiting for connections/))
        .start(),
      new GenericContainer('redis:7.4-alpine')
        .withExposedPorts(6379)
        .withWaitStrategy(Wait.forLogMessage(/Ready to accept connections/))
        .start(),
    ]);

    await mongoContainer.exec([
      'mongosh',
      '--quiet',
      '--eval',
      'rs.initiate({_id:"rs0",members:[{_id:0,host:"localhost:27017"}]})',
    ]);
    const mongoUri = `mongodb://${mongoContainer.getHost()}:${String(mongoContainer.getMappedPort(27017))}/?directConnection=true&replicaSet=rs0`;
    const redisUrl = `redis://${redisContainer.getHost()}:${String(redisContainer.getMappedPort(6379))}`;
    const databaseName = `mentalbridge_realtime_${randomUUID().replaceAll('-', '')}`;
    const keys = await generateKeyPair('RS256', { extractable: true });
    identityPrivateKey = keys.privateKey;
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
    for (let attempt = 0; attempt < 50; attempt += 1) {
      const hello = await mongoClient.db('admin').command({ hello: 1 });
      if (hello.isWritablePrimary === true) break;
      if (attempt === 49) throw new Error('MongoDB test replica set did not elect a primary');
      await new Promise((resolve) => setTimeout(resolve, 100));
    }
    await migration.up(mongoClient.db(databaseName));
    await moderationMigration.up(mongoClient.db(databaseName));

    app = await createApplication(configuration, { eligibility, evidence });
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
    await Promise.allSettled([
      Promise.resolve().then(() => app.close()),
      Promise.resolve()
        .then(() => mongoClient.db(configuration.MONGODB_DATABASE).dropDatabase())
        .then(() => mongoClient.close()),
      Promise.resolve().then(() => mongoContainer.stop()),
      Promise.resolve().then(async () => {
        if (!redisStopped) await redisContainer.stop();
      }),
    ]);
  });

  it('applies strict validators, conversation topology and required indexes', async () => {
    const database = mongoClient.db(configuration.MONGODB_DATABASE);
    await expect(
      database.collection('messages').insertOne({ content: 'plaintext' }),
    ).rejects.toThrow();
    const createdAt = new Date('2026-09-06T00:00:00.000Z');
    const updatedAt = new Date('2026-09-06T00:01:00.000Z');
    const validConversation = {
      _id: new ObjectId(),
      conversationId: randomUUID(),
      appointmentId: randomUUID(),
      participants: [
        { accountId, role: 'USER', joinedAt: createdAt },
        {
          accountId: specialistId,
          role: 'SPECIALIST',
          joinedAt: createdAt,
        },
      ],
      status: 'ACTIVE',
      lastMessageAt: null,
      closedAt: null,
      schemaVersion: 1,
      createdAt,
      updatedAt,
    };
    await expect(
      database.collection('conversations').insertOne(validConversation),
    ).resolves.toBeDefined();
    await expect(
      database.collection('conversations').insertOne({
        ...validConversation,
        _id: new ObjectId(),
        conversationId: randomUUID(),
        appointmentId: randomUUID(),
        participants: [
          { accountId, role: 'USER', joinedAt: createdAt },
          { accountId, role: 'SPECIALIST', joinedAt: createdAt },
        ],
      }),
    ).rejects.toThrow();
    await expect(
      database.collection('conversations').insertOne({
        ...validConversation,
        _id: new ObjectId(),
        conversationId: randomUUID(),
        appointmentId: randomUUID(),
        participants: validConversation.participants.map((participant) => ({
          ...participant,
          role: 'USER',
        })),
      }),
    ).rejects.toThrow();
    await expect(
      database.collection('conversations').insertOne({
        ...validConversation,
        _id: new ObjectId(),
        conversationId: randomUUID(),
        appointmentId: randomUUID(),
        closedAt: updatedAt,
      }),
    ).rejects.toThrow();
    await expect(
      database.collection('conversations').insertOne({
        ...validConversation,
        _id: new ObjectId(),
        conversationId: randomUUID(),
        appointmentId: randomUUID(),
        status: 'CLOSED',
        closedAt: null,
      }),
    ).rejects.toThrow();
    await expect(
      database.collection('conversations').insertOne({
        ...validConversation,
        _id: new ObjectId(),
        conversationId: randomUUID(),
        appointmentId: randomUUID(),
        createdAt: updatedAt,
        updatedAt: createdAt,
      }),
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

  it('applies idempotent appointment moderation collections, validators and indexes', async () => {
    const database = mongoClient.db(configuration.MONGODB_DATABASE);
    await expect(moderationMigration.up(database)).resolves.toBeUndefined();

    const collectionNames = (await database.listCollections({}, { nameOnly: true }).toArray()).map(
      ({ name }) => name,
    );
    expect(collectionNames).toEqual(
      expect.arrayContaining([
        'appointment_message_reports',
        'appointment_moderation_commands',
        'appointment_moderation_audit',
      ]),
    );

    const reportId = randomUUID();
    const reporterId = randomUUID();
    const reportedMessageId = randomUUID();
    const now = new Date('2026-11-12T00:00:00.000Z');
    const report = {
      _id: new ObjectId(),
      reportId,
      conversationId: randomUUID(),
      messageId: reportedMessageId,
      reporterId,
      reason: 'HARASSMENT',
      context: null,
      state: 'OPEN',
      resolution: null,
      resolvedBy: null,
      resolvedAt: null,
      createdAt: now,
      updatedAt: now,
      schemaVersion: 1,
    };
    await expect(
      database.collection('appointment_message_reports').insertOne(report),
    ).resolves.toBeDefined();
    await expect(
      database.collection('appointment_message_reports').insertOne({
        ...report,
        _id: new ObjectId(),
        reportId: randomUUID(),
      }),
    ).rejects.toMatchObject({ code: 11000 });
    await expect(
      database.collection('appointment_moderation_commands').insertOne({
        _id: new ObjectId(),
        actorId: reporterId,
        idempotencyKey: 'short',
        action: 'REPORT_MESSAGE',
        fingerprint: 'a'.repeat(64),
        targetId: reportedMessageId,
        resultId: reportId,
        createdAt: now,
        schemaVersion: 1,
      }),
    ).rejects.toThrow();
    await expect(
      database.collection('appointment_moderation_audit').insertOne({
        _id: new ObjectId(),
        auditId: randomUUID(),
        actorId: reporterId,
        actorRole: 'USER',
        action: 'REPORT_MESSAGE',
        targetType: 'MESSAGE',
        targetId: reportedMessageId,
        reason: 'HARASSMENT',
        outcome: 'SUCCEEDED',
        correlationId: randomUUID(),
        occurredAt: now,
        schemaVersion: 1,
      }),
    ).resolves.toBeDefined();

    const reportIndexes = await database.collection('appointment_message_reports').indexes();
    expect(reportIndexes.map(({ name }) => name)).toEqual(
      expect.arrayContaining([
        'appointment_reports_id_unique_idx',
        'appointment_reports_reporter_message_unique_idx',
        'appointment_reports_review_idx',
      ]),
    );
  });

  it('atomically rejects concurrent conflicting uses of one moderation key', async () => {
    const service = app.get(AppointmentModerationService);
    const targetConversationId = randomUUID();
    const targetMessageId = randomUUID();
    const key = `mb-694-collision-${randomUUID()}`;
    await storeModerationMessage(targetConversationId, targetMessageId);

    const outcomes = await Promise.allSettled([
      service.report(
        'bearer',
        userPrincipal(accountId),
        targetConversationId,
        targetMessageId,
        key,
        'SPAM',
        undefined,
        randomUUID(),
      ),
      service.report(
        'bearer',
        userPrincipal(accountId),
        targetConversationId,
        targetMessageId,
        key,
        'HARASSMENT',
        undefined,
        randomUUID(),
      ),
    ]);

    expect(outcomes.filter(({ status }) => status === 'fulfilled')).toHaveLength(1);
    const rejected = outcomes.find(({ status }) => status === 'rejected');
    expect(rejected).toMatchObject({
      status: 'rejected',
      reason: { status: 409, code: 'IDEMPOTENCY_CONFLICT' },
    });
    const database = mongoClient.db(configuration.MONGODB_DATABASE);
    expect(
      await database
        .collection('appointment_moderation_commands')
        .countDocuments({ actorId: accountId, idempotencyKey: key }),
    ).toBe(1);
    expect(
      await database
        .collection('appointment_message_reports')
        .countDocuments({ messageId: targetMessageId }),
    ).toBe(1);
    expect(
      await database
        .collection('appointment_moderation_audit')
        .countDocuments({ targetId: targetMessageId, action: 'REPORT_MESSAGE' }),
    ).toBe(1);
  });

  it('returns the one persisted tombstone timestamp to concurrent delete callers', async () => {
    const service = app.get(AppointmentModerationService);
    const targetConversationId = randomUUID();
    const targetMessageId = randomUUID();
    await storeModerationMessage(targetConversationId, targetMessageId);

    const [first, second] = await Promise.all([
      service.removeOwn(
        'bearer',
        userPrincipal(accountId),
        targetConversationId,
        targetMessageId,
        `mb-694-delete-${randomUUID()}`,
        randomUUID(),
      ),
      service.removeOwn(
        'bearer',
        userPrincipal(accountId),
        targetConversationId,
        targetMessageId,
        `mb-694-delete-${randomUUID()}`,
        randomUUID(),
      ),
    ]);

    const stored = await mongoClient
      .db(configuration.MONGODB_DATABASE)
      .collection<{ deletedAt: Date | null }>('messages')
      .findOne({ messageId: targetMessageId });
    expect(stored?.deletedAt).toBeInstanceOf(Date);
    expect(first.deletedAt).toBe(stored?.deletedAt.toISOString());
    expect(second.deletedAt).toBe(stored?.deletedAt.toISOString());
  });

  it('rolls back a moderation state change when mandatory audit persistence fails', async () => {
    const service = app.get(AppointmentModerationService);
    const targetConversationId = randomUUID();
    const targetMessageId = randomUUID();
    const key = `mb-694-audit-failure-${randomUUID()}`;
    const database = mongoClient.db(configuration.MONGODB_DATABASE);
    await storeModerationMessage(targetConversationId, targetMessageId);
    await mongoClient.db('admin').command({
      configureFailPoint: 'failCommand',
      mode: { times: 1 },
      data: {
        failCommands: ['insert'],
        namespace: `${configuration.MONGODB_DATABASE}.appointment_moderation_audit`,
        errorCode: 121,
      },
    });
    try {
      await expect(
        service.removeOwn(
          'bearer',
          userPrincipal(accountId),
          targetConversationId,
          targetMessageId,
          key,
          randomUUID(),
        ),
      ).rejects.toBeDefined();
    } finally {
      await mongoClient.db('admin').command({ configureFailPoint: 'failCommand', mode: 'off' });
    }
    expect(
      await database.collection('messages').findOne({ messageId: targetMessageId }),
    ).toMatchObject({ deletedAt: null });
    expect(
      await database
        .collection('appointment_moderation_commands')
        .countDocuments({ actorId: accountId, idempotencyKey: key }),
    ).toBe(0);
    expect(
      await database
        .collection('appointment_moderation_audit')
        .countDocuments({ targetId: targetMessageId }),
    ).toBe(0);
  });

  it('keeps report resolution and message removal consistent under conflicting admin races', async () => {
    const service = app.get(AppointmentModerationService);
    const database = mongoClient.db(configuration.MONGODB_DATABASE);
    const targetConversationId = randomUUID();
    const targetMessageId = randomUUID();
    const targetReportId = randomUUID();
    const now = new Date();
    await storeModerationMessage(targetConversationId, targetMessageId);
    await database.collection('appointment_message_reports').insertOne({
      _id: new ObjectId(),
      reportId: targetReportId,
      conversationId: targetConversationId,
      messageId: targetMessageId,
      reporterId: accountId,
      reason: 'SPAM',
      context: null,
      state: 'OPEN',
      resolution: null,
      resolvedBy: null,
      resolvedAt: null,
      createdAt: now,
      updatedAt: now,
      schemaVersion: 1,
    });
    const firstAdmin = userPrincipal('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'ADMIN');
    const secondAdmin = userPrincipal('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'ADMIN');

    const outcomes = await Promise.allSettled([
      service.resolve(
        firstAdmin,
        targetReportId,
        `mb-694-resolve-${randomUUID()}`,
        'NO_ACTION',
        'No policy violation',
        randomUUID(),
      ),
      service.resolve(
        secondAdmin,
        targetReportId,
        `mb-694-resolve-${randomUUID()}`,
        'MESSAGE_REMOVED',
        'Confirmed policy violation',
        randomUUID(),
      ),
    ]);

    expect(outcomes.filter(({ status }) => status === 'fulfilled')).toHaveLength(1);
    expect(outcomes.filter(({ status }) => status === 'rejected')).toHaveLength(1);
    const persistedReport = await database
      .collection('appointment_message_reports')
      .findOne({ reportId: targetReportId });
    const persistedMessage = await database
      .collection('messages')
      .findOne({ messageId: targetMessageId });
    expect(persistedReport?.state).toBe('RESOLVED');
    if (persistedReport?.resolution === 'MESSAGE_REMOVED')
      expect(persistedMessage?.deletedAt).toBeInstanceOf(Date);
    else {
      expect(persistedReport?.resolution).toBe('NO_ACTION');
      expect(persistedMessage?.deletedAt).toBeNull();
    }
  });

  it('returns the current resolved report state on an idempotent report replay', async () => {
    const service = app.get(AppointmentModerationService);
    const database = mongoClient.db(configuration.MONGODB_DATABASE);
    const targetConversationId = randomUUID();
    const targetMessageId = randomUUID();
    const key = `mb-694-report-replay-${randomUUID()}`;
    await storeModerationMessage(targetConversationId, targetMessageId);
    const first = await service.report(
      'bearer',
      userPrincipal(accountId),
      targetConversationId,
      targetMessageId,
      key,
      'SPAM',
      undefined,
      randomUUID(),
    );
    await database.collection('appointment_message_reports').updateOne(
      { reportId: first.reportId },
      {
        $set: {
          state: 'RESOLVED',
          resolution: 'NO_ACTION',
          resolvedBy: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
          resolvedAt: new Date(),
          updatedAt: new Date(),
        },
      },
    );

    await expect(
      service.report(
        'bearer',
        userPrincipal(accountId),
        targetConversationId,
        targetMessageId,
        key,
        'SPAM',
        undefined,
        randomUUID(),
      ),
    ).resolves.toMatchObject({ reportId: first.reportId, state: 'RESOLVED', duplicate: true });
  });

  it('rejects a missing Socket.IO credential', async () => {
    expect(await connectionError({ schemaVersion: 1 })).toMatchObject({
      message: 'Authentication failed',
    });
  });

  it('rejects an invalid Socket.IO credential', async () => {
    expect(await connectionError({ schemaVersion: 1, accessToken: 'invalid' })).toMatchObject({
      message: 'Authentication failed',
    });
  });

  it('rejects an already expired Identity bearer during credential exchange', async () => {
    const expiredToken = await issueToken(identityPrivateKey, configuration, accountId, {
      expiresInSeconds: -1,
      tokenId: 'expired-handshake',
    });
    await request(baseUrl)
      .post('/internal/v1/socket-credentials')
      .set('authorization', `Bearer ${expiredToken}`)
      .expect(401);
  });

  it('consumes a socket credential exactly once', async () => {
    const credential = await socketCredential(token);
    const socket = await connectWithCredential(credential);
    socket.close();

    expect(
      await connectionError({
        schemaVersion: 1,
        accessToken: credential,
        correlationId: randomUUID(),
      }),
    ).toMatchObject({ message: 'Authentication failed' });
  });

  it('disconnects a connected session when its JWT expires', async () => {
    const expiringToken = await issueToken(identityPrivateKey, configuration, accountId, {
      expiresInSeconds: 2,
      tokenId: 'connected-expiry',
    });
    const socket = await connectClient(expiringToken);
    const authenticationError = new Promise<Record<string, unknown>>((resolve) => {
      socket.once('realtime.error', resolve);
    });
    const disconnected = new Promise<void>((resolve) => {
      socket.once('disconnect', () => {
        resolve();
      });
    });
    expect(await authenticationError).toMatchObject({
      code: 'AUTHENTICATION_EXPIRED',
      retryable: false,
    });
    await disconnected;
    expect(socket.connected).toBe(false);
  });

  it('refreshes the actual Redis presence TTL on heartbeat', async () => {
    const heartbeatAccountId = randomUUID();
    const heartbeatToken = await issueToken(identityPrivateKey, configuration, heartbeatAccountId, {
      tokenId: `heartbeat-${heartbeatAccountId}`,
    });
    const socket = await connectClient(heartbeatToken);
    try {
      const presence = app.get(PresenceService);
      expect(await presence.status(heartbeatAccountId)).toBe('online');
      const redis = app.get(RedisService);
      const socketId = socket.id;
      if (!socketId) throw new Error('Connected socket ID is unavailable');
      await new Promise((resolve) => setTimeout(resolve, 1100));
      const beforeHeartbeat = await redis.execute((client) =>
        client.pTTL(`realtime:v1:socket:${socketId}`),
      );
      expect(await command(socket, 'presence.heartbeat', {})).toMatchObject({ status: 'accepted' });
      const afterHeartbeat = await redis.execute((client) =>
        client.pTTL(`realtime:v1:socket:${socketId}`),
      );
      expect(afterHeartbeat).toBeGreaterThan(beforeHeartbeat + 500);
      await eventually(async () => (await presence.status(heartbeatAccountId)) === 'offline');
    } finally {
      socket.close();
    }
  });

  it('records explicit check-in then server-derived presence without chat content', async () => {
    const socket = await connectClient();
    try {
      expect(await command(socket, 'conversation.check-in', { conversationId })).toMatchObject({
        status: 'accepted',
      });
      await new Promise((resolve) => setTimeout(resolve, 25));
      expect(await command(socket, 'presence.heartbeat', {})).toMatchObject({ status: 'accepted' });

      expect(evidenceRecords[0]).toMatchObject({ type: 'CHECK_IN' });
      expect(evidenceRecords[1]?.type).toBe('PRESENCE_INTERVAL');
      expect(typeof evidenceRecords[1]?.intervalStartedAt).toBe('string');
      expect(typeof evidenceRecords[1]?.occurredAt).toBe('string');
      expect(evidenceRecords.every((record) => !Object.hasOwn(record, 'content'))).toBe(true);
    } finally {
      socket.close();
    }
  });

  it('removes Redis routing when a real socket disconnects', async () => {
    const socket = await connectClient();
    const socketId = socket.id;
    if (!socketId) throw new Error('Connected socket ID is unavailable');
    socket.close();
    await eventually(async () => {
      const exists = await app
        .get(RedisService)
        .execute((client) => client.exists(`realtime:v1:socket:${socketId}`));
      return exists === 0 && (await app.get(PresenceService).status(accountId)) === 'offline';
    });
  });

  it('enforces the connection bound with multiple real sockets', async () => {
    const boundedAccountId = '33333333-3333-4333-8333-333333333333';
    const boundedToken = await issueToken(identityPrivateKey, configuration, boundedAccountId, {
      tokenId: 'bounded-connections',
    });
    const sockets: Socket[] = [];
    try {
      for (let index = 0; index < configuration.MAX_CONNECTIONS_PER_ACCOUNT; index += 1) {
        sockets.push(await connectClient(boundedToken));
      }
      const rejectedCredential = await socketCredential(boundedToken);
      const rejected = io(`${baseUrl}/realtime`, {
        transports: ['websocket'],
        auth: { schemaVersion: 1, accessToken: rejectedCredential },
        reconnection: false,
      });
      sockets.push(rejected);
      const disconnected = new Promise<void>((resolve) => {
        rejected.once('disconnect', () => {
          resolve();
        });
      });
      const error = await new Promise<Record<string, unknown>>((resolve, reject) => {
        rejected.once('realtime.error', resolve);
        rejected.once('connect_error', reject);
      });
      expect(error).toMatchObject({ code: 'RATE_LIMITED', retryable: true });
      await disconnected;
      expect(rejected.connected).toBe(false);
    } finally {
      for (const socket of sockets) socket.close();
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

      const missingVersion = await rawCommand(socket, {
        commandId: randomUUID(),
        commandType: 'presence.heartbeat',
        correlationId: randomUUID(),
        sentAt: new Date().toISOString(),
        payload: {},
      });
      expect(missingVersion.code).toBe('UNSUPPORTED_SCHEMA_VERSION');

      const missingCommandId = await rawCommand(socket, {
        schemaVersion: 1,
        commandType: 'presence.heartbeat',
        correlationId: randomUUID(),
        sentAt: new Date().toISOString(),
        payload: {},
      });
      expect(missingCommandId.code).toBe('INVALID_ENVELOPE');

      const missingCorrelationId = await rawCommand(socket, {
        schemaVersion: 1,
        commandId: randomUUID(),
        commandType: 'presence.heartbeat',
        sentAt: new Date().toISOString(),
        payload: {},
      });
      expect(missingCorrelationId.code).toBe('INVALID_ENVELOPE');

      const unknownCommand = await rawCommand(socket, {
        schemaVersion: 1,
        commandId: randomUUID(),
        commandType: 'message.unknown',
        correlationId: randomUUID(),
        sentAt: new Date().toISOString(),
        payload: {},
      });
      expect(unknownCommand.code).toBe('INVALID_ENVELOPE');

      const malformed = await rawCommand(socket, {
        schemaVersion: 1,
        commandId: randomUUID(),
        commandType: 'message.send',
        correlationId: randomUUID(),
        sentAt: new Date().toISOString(),
        payload: { senderId: accountId },
      });
      expect(malformed.code).toBe('INVALID_ENVELOPE');

      const forgedActor = await rawCommand(socket, {
        schemaVersion: 1,
        commandId: randomUUID(),
        commandType: 'message.send',
        correlationId: randomUUID(),
        sentAt: new Date().toISOString(),
        payload: {
          conversationId,
          clientMessageId: randomUUID(),
          type: 'TEXT',
          content: 'forged actor attempt',
          senderId: '44444444-4444-4444-8444-444444444444',
        },
      });
      expect(forgedActor.code).toBe('INVALID_ENVELOPE');

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
    const messageEvents: Record<string, unknown>[] = [];
    socket.on('realtime.event', (event: { eventType?: unknown }) => {
      if (event.eventType === 'message.created') messageEvents.push(event);
    });
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
      expect(first.liveDelivery).toBe('not_applicable');
      expect(duplicate.liveDelivery).toBe('not_applicable');
      await eventually(() => Promise.resolve(messageEvents.length === 1));

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

      socket.close();
      const reconnected = await connectClient();

      try {
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
      } finally {
        reconnected.close();
      }

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

  it('does not persist a message when the bound conversation is already closed', async () => {
    const socket = await connectClient();
    const closedConversationId = randomUUID();
    try {
      await command(socket, 'conversation.subscribe', {
        conversationId: closedConversationId,
      });
      await mongoClient
        .db(configuration.MONGODB_DATABASE)
        .collection('conversations')
        .updateOne(
          { conversationId: closedConversationId },
          {
            $set: {
              status: 'CLOSED',
              closedAt: new Date(),
              updatedAt: new Date(),
            },
          },
        );

      const clientMessageId = randomUUID();
      const acknowledgement = await command(socket, 'message.send', {
        conversationId: closedConversationId,
        clientMessageId,
        type: 'TEXT',
        content: 'must not persist after close',
      });

      expect(acknowledgement.code).toBe('ACCESS_DENIED');
      expect(
        await mongoClient.db(configuration.MONGODB_DATABASE).collection('messages').countDocuments({
          conversationId: closedConversationId,
          clientMessageId,
        }),
      ).toBe(0);
    } finally {
      socket.close();
    }
  });

  it('does not claim live delivery when the sender has not joined a room', async () => {
    const socket = await connectClient();
    try {
      const acknowledgement = await command(socket, 'message.send', {
        conversationId: '55555555-5555-4555-8555-555555555555',
        clientMessageId: randomUUID(),
        type: 'TEXT',
        content: 'unsubscribed sender',
      });
      expect(acknowledgement).toMatchObject({
        status: 'accepted',
        liveDelivery: 'not_applicable',
      });
    } finally {
      socket.close();
    }
  });

  it('keeps cursor ordering stable when messages have equal timestamps', async () => {
    const stableConversationId = '66666666-6666-4666-8666-666666666666';
    const sentAt = new Date('2026-09-06T01:00:00.000Z');
    const encryption = app.get(MessageEncryptionService);
    const firstMessageId = randomUUID();
    const secondMessageId = randomUUID();
    const storedMessage = (id: ObjectId, messageId: string, content: string) => ({
      _id: id,
      messageId,
      conversationId: stableConversationId,
      senderId: accountId,
      clientMessageId: randomUUID(),
      type: 'TEXT',
      ...encryption.encrypt(content),
      commandFingerprint: encryption.fingerprint(stableConversationId, 'TEXT', content),
      sentAt,
      editedAt: null,
      deletedAt: null,
      moderationHold: false,
      schemaVersion: 1,
    });
    await mongoClient
      .db(configuration.MONGODB_DATABASE)
      .collection('messages')
      .insertMany([
        storedMessage(new ObjectId('000000000000000000000001'), firstMessageId, 'first tie'),
        storedMessage(new ObjectId('000000000000000000000002'), secondMessageId, 'second tie'),
      ]);
    const firstPage = await request(baseUrl)
      .get(`/api/v1/conversations/${stableConversationId}/messages?limit=1`)
      .set('authorization', `Bearer ${token}`)
      .expect(200);
    const firstBody = firstPage.body as {
      items: { messageId: string }[];
      nextCursor: string;
    };
    expect(firstBody.items[0]?.messageId).toBe(secondMessageId);
    const secondPage = await request(baseUrl)
      .get(`/api/v1/conversations/${stableConversationId}/messages`)
      .query({ limit: 1, cursor: firstBody.nextCursor })
      .set('authorization', `Bearer ${token}`)
      .expect(200);
    const secondBody = secondPage.body as { items: { messageId: string }[] };
    expect(secondBody.items[0]?.messageId).toBe(firstMessageId);
  });

  it('does not acknowledge success when MongoDB persistence fails', async () => {
    const clientMessageId = randomUUID();
    await mongoClient.db('admin').command({
      configureFailPoint: 'failCommand',
      mode: { times: 1 },
      data: { failCommands: ['insert'], errorCode: 121 },
    });
    const socket = await connectClient();
    try {
      const acknowledgement = await command(socket, 'message.send', {
        conversationId,
        clientMessageId,
        type: 'TEXT',
        content: 'must not be acknowledged',
      });
      expect(acknowledgement.code).toBe('INTERNAL_ERROR');
      expect(acknowledgement).not.toHaveProperty('status');
      expect(acknowledgement).not.toHaveProperty('messageId');
      expect(
        await mongoClient
          .db(configuration.MONGODB_DATABASE)
          .collection('messages')
          .countDocuments({ senderId: accountId, clientMessageId }),
      ).toBe(0);
    } finally {
      socket.close();
      await mongoClient.db('admin').command({ configureFailPoint: 'failCommand', mode: 'off' });
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
      expect(acknowledgement).toMatchObject({
        status: 'accepted',
        liveDelivery: 'not_applicable',
      });
    } finally {
      socket.close();
    }
  });

  async function storeModerationMessage(
    targetConversationId: string,
    targetMessageId: string,
  ): Promise<void> {
    const encryption = app.get(MessageEncryptionService);
    const content = `moderation target ${targetMessageId}`;
    await mongoClient
      .db(configuration.MONGODB_DATABASE)
      .collection('messages')
      .insertOne({
        _id: new ObjectId(),
        messageId: targetMessageId,
        conversationId: targetConversationId,
        senderId: accountId,
        clientMessageId: randomUUID(),
        type: 'TEXT',
        ...encryption.encrypt(content),
        commandFingerprint: encryption.fingerprint(targetConversationId, 'TEXT', content),
        sentAt: new Date(),
        editedAt: null,
        deletedAt: null,
        moderationHold: false,
        schemaVersion: 1,
      });
  }

  function userPrincipal(
    requestedAccountId: string,
    role: AuthenticatedPrincipal['role'] = 'USER',
  ): AuthenticatedPrincipal {
    return {
      accountId: requestedAccountId,
      role,
      tokenId: randomUUID(),
      expiresAtEpochSeconds: 4_000_000_000,
    };
  }

  async function connectClient(accessToken = token): Promise<Socket> {
    const credential = await socketCredential(accessToken);
    return connectWithCredential(credential);
  }

  async function connectWithCredential(credential: string): Promise<Socket> {
    const socket = io(`${baseUrl}/realtime`, {
      transports: ['websocket'],
      auth: { schemaVersion: 1, accessToken: credential, correlationId: randomUUID() },
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

  async function socketCredential(accessToken: string): Promise<string> {
    const response = await request(baseUrl)
      .post('/internal/v1/socket-credentials')
      .set('authorization', `Bearer ${accessToken}`)
      .expect(201);
    return (response.body as { accessToken: string }).accessToken;
  }

  async function connectionError(auth: Record<string, unknown>): Promise<Error> {
    const socket = io(`${baseUrl}/realtime`, {
      transports: ['websocket'],
      auth,
      reconnection: false,
    });
    try {
      return await new Promise<Error>((resolve) => socket.once('connect_error', resolve));
    } finally {
      socket.close();
    }
  }

  async function eventually(assertion: () => Promise<boolean>): Promise<void> {
    const deadline = Date.now() + 3000;
    while (!(await assertion())) {
      if (Date.now() >= deadline) throw new Error('Expected state was not reached');
      await new Promise((resolve) => setTimeout(resolve, 25));
    }
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
