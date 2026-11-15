import { createHash, randomUUID } from 'node:crypto';
import { readFile, readdir } from 'node:fs/promises';
import { createServer as createHttpServer, type Server } from 'node:http';
import { createServer, type AddressInfo } from 'node:net';
import type { INestApplication } from '@nestjs/common';
import type { StartedKafkaContainer } from '@testcontainers/kafka';
import { exportSPKI, generateKeyPair } from 'jose';
import { Kafka, logLevel, type Producer } from 'kafkajs';
import { io, type Socket } from 'socket.io-client';
import request from 'supertest';
import { GenericContainer, type StartedTestContainer, Wait } from 'testcontainers';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApplication } from '../../src/application.js';
import type { ServiceConfiguration } from '../../src/configuration/configuration.js';
import type { MongoDatabaseService } from '../../src/database/mongo-database.service.js';
import { RedisService } from '../../src/database/redis.service.js';
import { ContentNotificationClient } from '../../src/notifications/content-notification.client.js';
import {
  NOTIFICATION_TOPIC,
  NOTIFICATION_RETRY_TOPIC,
  NOTIFICATION_DEAD_LETTER_TOPIC,
  notificationCreatedSchema,
  notificationServerEventSchema,
  toNotificationPointer,
  type NotificationCreated,
  type NotificationServerEvent,
} from '../../src/notifications/notification-schema.js';
import { SpecialistNotificationFanout } from '../../src/notifications/specialist-notification-fanout.js';
import { CommunityKafkaContainer } from '../fixtures/community-kafka.js';
import { testConfiguration } from '../fixtures/test-configuration.js';
import { issueToken } from '../fixtures/token.js';

type ContentConfiguration = Record<string, unknown>;
interface ContentDatabase {
  query(text: string, parameters?: unknown[]): Promise<{ rows: Record<string, unknown>[] }>;
  onModuleDestroy(): Promise<void>;
}
interface Materializer {
  materialize(event: Record<string, unknown>): Promise<void>;
}
interface Relay {
  publishDue(): Promise<void>;
  onApplicationShutdown(): Promise<void>;
}
interface Client {
  socket: Socket;
  events: NotificationServerEvent[];
}
const contentRoot = new URL('../../../content-notification-service/', import.meta.url);
const loadContent = (path: string): Promise<unknown> =>
  import(new URL(`dist/${path}`, contentRoot).href);
const specialist = '44444444-4444-4444-8444-444444444444';
const foreignSpecialist = '55555555-5555-4555-8555-555555555555';

describe('Specialist notifications with the real Content owner', { concurrent: false }, () => {
  let postgres: StartedTestContainer;
  let redis: StartedTestContainer;
  let broker: StartedKafkaContainer;
  let database: ContentDatabase;
  let content: INestApplication;
  let materializer: Materializer;
  let relay: Relay;
  let contentUrl: string;
  let configuration: ServiceConfiguration;
  let signingKey: Parameters<typeof issueToken>[0];
  let bearer: string;
  let kafka: Kafka;
  let producer: Producer;
  let ownerProxy: Server;
  let ownerMode: 'forward' | 'unavailable' | 'malformed' | 'timeout' = 'forward';
  let ownerGate: Promise<void> | undefined;
  let appointmentVersion = 0;
  const apps: INestApplication[] = [];
  const clients: Client[] = [];
  const urls: string[] = [];

  beforeAll(async () => {
    const [port, redisPort] = await Promise.all([reservePort(), reservePort()]);
    [postgres, redis, broker] = await Promise.all([
      new GenericContainer('postgres:16-alpine')
        .withEnvironment({
          POSTGRES_USER: 'mb800',
          POSTGRES_PASSWORD: 'test-only',
          POSTGRES_DB: 'content',
        })
        .withExposedPorts(5432)
        .withWaitStrategy(Wait.forLogMessage(/database system is ready to accept connections/, 2))
        .start(),
      new GenericContainer('redis:7.4-alpine')
        .withExposedPorts({ container: 6379, host: redisPort })
        .withWaitStrategy(Wait.forLogMessage(/Ready to accept connections/))
        .start(),
      new CommunityKafkaContainer(port).start(),
    ]);
    const brokers = [`${broker.getHost()}:${String(broker.getMappedPort(9093))}`];
    kafka = new Kafka({ clientId: 'mb800-real-owner', brokers, logLevel: logLevel.NOTHING });
    const admin = kafka.admin();
    await admin.connect();
    try {
      await admin.createTopics({
        topics: [NOTIFICATION_TOPIC, NOTIFICATION_RETRY_TOPIC, NOTIFICATION_DEAD_LETTER_TOPIC].map(
          (topic) => ({ topic, numPartitions: 3 }),
        ),
      });
    } finally {
      await admin.disconnect();
    }
    producer = kafka.producer({ allowAutoTopicCreation: false });
    await producer.connect();
    const keys = await generateKeyPair('RS256', { extractable: true });
    signingKey = keys.privateKey;
    const publicKey = await exportSPKI(keys.publicKey);
    const configurationModule = (await loadContent('configuration/configuration.js')) as {
      loadConfiguration(environment: NodeJS.ProcessEnv): ContentConfiguration;
    };
    const contentConfiguration = configurationModule.loadConfiguration({
      NODE_ENV: 'test',
      DATABASE_URL: `postgresql://mb800:test-only@${postgres.getHost()}:${String(postgres.getMappedPort(5432))}/content`,
      LOG_LEVEL: 'silent',
      IDENTITY_JWT_ISSUER: 'https://identity.test.mentalbridge',
      IDENTITY_JWT_AUDIENCE: 'mentalbridge-api',
      IDENTITY_JWT_PUBLIC_KEY: publicKey,
      IDENTITY_JWT_KEY_ID: 'test-key',
      IDENTITY_JWT_CLOCK_TOLERANCE_SECONDS: '0',
    });
    const databaseModule = (await loadContent('database/database.service.js')) as {
      DatabaseService: new (configuration: ContentConfiguration) => ContentDatabase;
    };
    database = new databaseModule.DatabaseService(contentConfiguration);
    const migrationRoot = new URL('migrations/', contentRoot);
    const migrations = (await readdir(migrationRoot))
      .filter((name) => /^\d+.*\.sql$/.test(name))
      .sort((left, right) => Number.parseInt(left) - Number.parseInt(right));
    for (const migration of migrations) {
      const sql = await readFile(new URL(migration, migrationRoot), 'utf8');
      await database.query(sql.split('-- Down Migration')[0] ?? sql);
    }
    const appModule = (await loadContent('application.js')) as {
      createApplication(configuration: ContentConfiguration): Promise<INestApplication>;
    };
    content = await appModule.createApplication(contentConfiguration);
    await content.listen(0, '127.0.0.1');
    contentUrl = serverUrl(content);
    ownerProxy = createHttpServer((incoming, response) => {
      const mode = ownerMode;
      const gate = ownerGate;
      void (async () => {
        if (mode === 'timeout') return;
        await gate;
        if (response.destroyed) return;
        if (mode !== 'forward') {
          response.writeHead(mode === 'unavailable' ? 503 : 200, {
            'content-type': 'application/json',
          });
          response.end(mode === 'malformed' ? '{invalid' : '{}');
          return;
        }
        const upstream = await fetch(new URL(incoming.url ?? '/', contentUrl), {
          headers: {
            authorization: incoming.headers.authorization ?? '',
            'x-correlation-id': String(incoming.headers['x-correlation-id'] ?? ''),
          },
        });
        response.writeHead(upstream.status, { 'content-type': 'application/json' });
        response.end(await upstream.text());
      })().catch(() => {
        if (!response.destroyed) {
          response.writeHead(503);
          response.end();
        }
      });
    });
    await new Promise<void>((resolve) => ownerProxy.listen(0, '127.0.0.1', resolve));
    const materializerModule = (await loadContent(
      'appointment-reminders/specialist-appointment-notification.repository.js',
    )) as {
      SpecialistAppointmentNotificationRepository: new (database: ContentDatabase) => Materializer;
    };
    materializer = new materializerModule.SpecialistAppointmentNotificationRepository(database);
    const repositoryModule = (await loadContent(
      'specialist-notifications/specialist-notification-live.repository.js',
    )) as {
      SpecialistNotificationLiveRepository: new (database: ContentDatabase) => object;
    };
    const relayModule = (await loadContent(
      'specialist-notifications/specialist-notification-live.relay.js',
    )) as {
      SpecialistNotificationLiveRelay: new (
        configuration: ContentConfiguration,
        repository: object,
      ) => Relay;
    };
    relay = new relayModule.SpecialistNotificationLiveRelay(
      {
        ...contentConfiguration,
        CONTENT_SPECIALIST_LIVE_RELAY_ENABLED: true,
        KAFKA_BOOTSTRAP_SERVERS: brokers.join(','),
      },
      new repositoryModule.SpecialistNotificationLiveRepository(database),
    );
    configuration = testConfiguration(publicKey, {
      REDIS_URL: `redis://${redis.getHost()}:${String(redis.getMappedPort(6379))}`,
      SPECIALIST_LIVE: {
        enabled: true,
        baseUrl: `http://127.0.0.1:${String((ownerProxy.address() as AddressInfo).port)}`,
        timeoutMs: 500,
        brokers,
      },
    });
    bearer = await issueToken(signingKey, configuration, specialist, { role: 'SPECIALIST' });
    for (let index = 0; index < 2; index++) {
      const mongo = {
        ping: () => Promise.resolve(),
        collection: () => {
          throw new Error('Notification delivery must not use MongoDB');
        },
        onApplicationShutdown: () => Promise.resolve(),
      } as unknown as MongoDatabaseService;
      const app = await createApplication(configuration, { mongo });
      await app.listen(0, '127.0.0.1');
      apps.push(app);
      urls.push(serverUrl(app));
    }
    await vi.waitFor(
      () => {
        expect(apps.every((app) => app.get(SpecialistNotificationFanout).available())).toBe(true);
      },
      { timeout: 25_000 },
    );
  }, 240_000);

  afterEach(() => {
    for (const client of clients) client.socket.disconnect();
    clients.splice(0);
    ownerMode = 'forward';
    ownerGate = undefined;
    vi.restoreAllMocks();
  });
  beforeEach(async () => {
    await database.query('TRUNCATE notification CASCADE');
    await database.query('DELETE FROM notification_preference');
  });
  afterAll(async () => {
    for (const app of apps) await app.close();
    await Promise.allSettled([
      Promise.resolve().then(() => relay.onApplicationShutdown()),
      Promise.resolve().then(() => producer.disconnect()),
      Promise.resolve().then(async () => {
        ownerProxy.closeAllConnections();
        await new Promise<void>((resolve, reject) =>
          ownerProxy.close((error) => {
            if (error) reject(error);
            else resolve();
          }),
        );
      }),
      Promise.resolve().then(() => content.close()),
      Promise.resolve().then(() => database.onModuleDestroy()),
    ]);
    await Promise.allSettled([
      Promise.resolve().then(() => postgres.stop()),
      Promise.resolve().then(() => redis.stop()),
      Promise.resolve().then(() => broker.stop()),
    ]);
  });

  async function connect(index = 0, token = bearer): Promise<Client> {
    const baseUrl = urls[index];
    if (!baseUrl) throw new Error('Realtime replica URL is unavailable');
    const credential = await request(baseUrl)
      .post('/internal/v1/socket-credentials')
      .set('authorization', `Bearer ${token}`)
      .expect(201);
    const ticket = (credential.body as { accessToken: string }).accessToken;
    const socket = io(`${baseUrl}/realtime`, {
      auth: { schemaVersion: 1, accessToken: ticket, correlationId: randomUUID() },
      transports: ['websocket'],
      reconnection: false,
      forceNew: true,
      autoConnect: false,
    });
    const client: Client = { socket, events: [] };
    clients.push(client);
    socket.on('notification.created', (event: unknown) =>
      client.events.push(notificationServerEventSchema.parse(event)),
    );
    await new Promise<void>((resolve, reject) => {
      socket.on('realtime.event', (event: { eventType: string }) => {
        if (event.eventType === 'connection.ready') resolve();
      });
      socket.once('connect_error', reject);
      socket.connect();
    });
    return client;
  }

  function event() {
    const appointmentId = randomUUID();
    return {
      messageId: randomUUID(),
      messageType: 'consultation.appointment.status-changed',
      schemaVersion: '2.0',
      producer: 'consultation-service',
      correlationId: randomUUID(),
      occurredAt: new Date().toISOString(),
      aggregateId: appointmentId,
      aggregateVersion: ++appointmentVersion,
      payload: {
        appointmentId,
        ownerAccountId: randomUUID(),
        specialistAccountId: specialist,
        status: 'REQUESTED',
        scheduledStartAt: new Date(Date.now() + 3600_000).toISOString(),
        modality: 'IN_APP_CHAT',
        replacesAppointmentId: null,
      },
    };
  }

  async function committed(source = event()): Promise<NotificationCreated> {
    await materializer.materialize(source);
    const result = await database.query(
      'SELECT * FROM specialist_notification_live_outbox WHERE causation_id=$1',
      [source.messageId],
    );
    const row = result.rows[0] as
      | {
          id: string;
          notification_id: string;
          recipient_account_id: string;
          aggregate_version: string | number;
          correlation_id: string;
          causation_id: string;
          occurred_at: Date;
          expires_at: Date;
        }
      | undefined;
    if (!row) throw new Error('Committed creation outbox pointer is absent');
    return notificationCreatedSchema.parse({
      messageId: row.id,
      messageType: 'content.notification.created',
      schemaVersion: '1.0',
      producer: 'content-notification-service',
      correlationId: row.correlation_id,
      causationId: row.causation_id,
      occurredAt: row.occurred_at.toISOString(),
      aggregateId: row.notification_id,
      aggregateVersion: Number(row.aggregate_version),
      payload: {
        notificationId: row.notification_id,
        recipientAccountId: row.recipient_account_id,
        recipientRole: 'SPECIALIST',
        expiresAt: row.expires_at.toISOString(),
      },
    });
  }

  async function publish(
    created: NotificationCreated,
    topic = NOTIFICATION_TOPIC,
    headers?: Record<string, string>,
  ): Promise<void> {
    await producer.send({
      topic,
      acks: -1,
      messages: [
        {
          key: created.aggregateId,
          value: JSON.stringify(created),
          ...(headers ? { headers } : {}),
        },
      ],
    });
  }

  it('delivers one committed creation to every eligible owner device across replicas', async () => {
    const ownerA = await connect(0);
    const ownerB = await connect(1);
    const foreign = await connect(
      0,
      await issueToken(signingKey, configuration, foreignSpecialist, { role: 'SPECIALIST' }),
    );
    const user = await connect(
      1,
      await issueToken(signingKey, configuration, specialist, { role: 'USER' }),
    );
    const source = event();
    const created = await committed(source);
    await materializer.materialize({ ...source, messageId: randomUUID() });
    expect(ownerA.events).toEqual([]);
    expect(ownerB.events).toEqual([]);
    await relay.publishDue();
    await vi.waitFor(
      () => {
        expect(ownerA.events).toHaveLength(1);
        expect(ownerB.events).toHaveLength(1);
      },
      { timeout: 15_000 },
    );
    expect(ownerA.events[0]).toEqual(ownerB.events[0]);
    expect(ownerA.events[0]).toEqual({
      schemaVersion: 1,
      eventId: created.messageId,
      eventType: 'notification.created',
      correlationId: created.correlationId,
      occurredAt: created.occurredAt,
      payload: { notificationId: created.aggregateId },
    });
    await apps[0]?.get(SpecialistNotificationFanout).publish(toNotificationPointer(created));
    await new Promise((resolve) => setTimeout(resolve, 250));
    expect(ownerA.events).toHaveLength(1);
    expect(ownerB.events).toHaveLength(1);
    expect(foreign.events).toEqual([]);
    expect(user.events).toEqual([]);
    const inbox = await request(contentUrl)
      .get('/api/v1/notifications')
      .set('authorization', `Bearer ${bearer}`)
      .expect(200);
    expect(inbox.body).toMatchObject({ unreadCount: 1, items: [{ id: created.aggregateId }] });
    const persisted = await database.query(
      'SELECT state FROM specialist_notification_live_outbox WHERE id=$1',
      [created.messageId],
    );
    expect(persisted.rows[0]?.state).toBe('PUBLISHED');
  });

  it('repairs an offline interval through current REST list and absolute unread count', async () => {
    const before = await connect();
    before.socket.disconnect();
    const created = await committed();
    await relay.publishDue();
    await connect(1);
    const recovered = await request(contentUrl)
      .get('/api/v1/notifications')
      .set('authorization', `Bearer ${bearer}`)
      .expect(200);
    const body = recovered.body as { unreadCount: number; items: { id: string }[] };
    expect(body.items.some((item) => item.id === created.aggregateId)).toBe(true);
    expect(body.unreadCount).toBe(1);
    await request(contentUrl)
      .patch(`/api/v1/notifications/${created.aggregateId}/read`)
      .set('authorization', `Bearer ${bearer}`)
      .expect(200);
    const read = await request(contentUrl)
      .get('/api/v1/notifications')
      .set('authorization', `Bearer ${bearer}`)
      .expect(200);
    expect(read.body).toMatchObject({ unreadCount: 0 });
  });

  it('revalidates preference changes after Kafka and never revives a deleted owner row', async () => {
    const created = await committed();
    await relay.publishDue();
    await database.query(
      'UPDATE notification_preference SET group_appointment_message_enabled=false WHERE user_id=$1',
      [specialist],
    );
    const client = await connect();
    const pointer = toNotificationPointer(created);
    await apps[0]?.get(SpecialistNotificationFanout).publish(pointer);
    await new Promise((resolve) => setTimeout(resolve, 350));
    expect(client.events).toEqual([]);
    const denied = await request(contentUrl)
      .get(`/internal/v1/notifications/${created.aggregateId}/live-eligibility`)
      .set('authorization', `Bearer ${bearer}`)
      .expect(404);
    expect(denied.body).toMatchObject({ code: 'NOTIFICATION_NOT_FOUND' });
    expect(denied.headers['cache-control']).toBe('private, no-store');
    await database.query(
      'UPDATE notification_preference SET group_appointment_message_enabled=true WHERE user_id=$1',
      [specialist],
    );
    await apps[0]?.get(SpecialistNotificationFanout).publish(pointer);
    await vi.waitFor(() => {
      expect(client.events).toHaveLength(1);
    });
    await request(contentUrl)
      .delete(`/api/v1/notifications/${created.aggregateId}`)
      .set('authorization', `Bearer ${bearer}`)
      .expect(204);
    client.socket.disconnect();
    const reconnected = await connect(1);
    await apps[0]?.get(SpecialistNotificationFanout).publish(pointer);
    await new Promise((resolve) => setTimeout(resolve, 350));
    expect(reconnected.events).toEqual([]);
    await request(contentUrl)
      .get(`/internal/v1/notifications/${created.aggregateId}/live-eligibility`)
      .set('authorization', `Bearer ${bearer}`)
      .expect(404);
    const inbox = await request(contentUrl)
      .get('/api/v1/notifications')
      .set('authorization', `Bearer ${bearer}`)
      .expect(200);
    expect(
      (inbox.body as { items: { id: string }[] }).items.some(
        (item) => item.id === created.aggregateId,
      ),
    ).toBe(false);
  });

  it('honors a delayed retry while the main topic delivers newer and reordered creations', async () => {
    const first = await connect(0);
    const second = await connect(1);
    const delayed = await committed();
    const newer = await committed();
    const notBefore = Date.now() + 3000;
    await publish(delayed, NOTIFICATION_RETRY_TOPIC, {
      'mb-retry-attempt': '1',
      'mb-not-before': new Date(notBefore).toISOString(),
    });
    await publish(newer);
    await vi.waitFor(
      () => {
        expect(first.events.map((value) => value.eventId)).toContain(newer.messageId);
        expect(second.events.map((value) => value.eventId)).toContain(newer.messageId);
      },
      { timeout: 2000 },
    );
    expect(Date.now()).toBeLessThan(notBefore);
    expect(first.events.map((value) => value.eventId)).not.toContain(delayed.messageId);
    await vi.waitFor(
      () => {
        expect(first.events.map((value) => value.eventId)).toContain(delayed.messageId);
        expect(second.events.map((value) => value.eventId)).toContain(delayed.messageId);
      },
      { timeout: 10_000 },
    );
    await publish(delayed);
    await publish(newer);
    await new Promise((resolve) => setTimeout(resolve, 250));
    expect(first.events).toHaveLength(2);
    expect(second.events).toHaveLength(2);
  });

  it('ACKs digest-only poison and retry-exhaustion handoff while continuing valid delivery', async () => {
    const client = await connect();
    const created = await committed();
    const wire = JSON.stringify({
      ...created,
      payload: { ...created.payload, body: 'PRIVATE-CONTENT-MUST-NOT-LEAK' },
    });
    const digest = createHash('sha256').update(wire).digest('hex');
    const exhaustedDigest = createHash('sha256').update(JSON.stringify(created)).digest('hex');
    const diagnostics: Record<string, unknown>[] = [];
    const observer = kafka.consumer({ groupId: `mb800-dlq-${randomUUID()}` });
    const joined = new Promise<void>((resolve) =>
      observer.on(observer.events.GROUP_JOIN, () => {
        resolve();
      }),
    );
    await observer.connect();
    await observer.subscribe({ topic: NOTIFICATION_DEAD_LETTER_TOPIC, fromBeginning: true });
    await observer.run({
      eachMessage: ({ message }) => {
        diagnostics.push(JSON.parse(message.value?.toString() ?? '{}') as Record<string, unknown>);
        return Promise.resolve();
      },
    });
    await joined;
    try {
      await producer.send({
        topic: NOTIFICATION_TOPIC,
        acks: -1,
        messages: [{ key: created.aggregateId, value: wire }],
      });
      await vi.waitFor(() => {
        expect(
          diagnostics.some(
            (value) => value.sourceMessageDigest === digest && value.errorCode === 'INVALID_EVENT',
          ),
        ).toBe(true);
      });
      const failedPublish = vi
        .spyOn(SpecialistNotificationFanout.prototype, 'publish')
        .mockRejectedValue(new Error('Synthetic Redis failure'));
      try {
        await publish(created, NOTIFICATION_RETRY_TOPIC, {
          'mb-retry-attempt': '3',
          'mb-not-before': new Date().toISOString(),
        });
        await vi.waitFor(() => {
          expect(
            diagnostics.some(
              (value) =>
                value.sourceMessageDigest === exhaustedDigest &&
                value.errorCode === 'TRANSIENT_RETRIES_EXHAUSTED' &&
                value.attempt === 3,
            ),
          ).toBe(true);
        });
      } finally {
        failedPublish.mockRestore();
      }
      expect(client.events).toEqual([]);
      expect(JSON.stringify(diagnostics)).not.toContain('PRIVATE-CONTENT-MUST-NOT-LEAK');
      expect(JSON.stringify(diagnostics)).not.toContain(created.payload.recipientAccountId);
      await publish(created);
      await vi.waitFor(() => {
        expect(client.events).toHaveLength(1);
      });
    } finally {
      await observer.disconnect();
    }
  });

  it.each(['unavailable', 'malformed', 'timeout'] as const)(
    'fails closed on %s owner reads and permits a fresh signal after recovery',
    async (mode) => {
      const client = await connect();
      const created = await committed();
      const owner = apps[0]?.get(ContentNotificationClient);
      if (!owner) throw new Error('Owner client unavailable');
      const check = vi.spyOn(owner, 'check');
      ownerMode = mode;
      await publish(created);
      await vi.waitFor(
        () => {
          expect(check).toHaveBeenCalledTimes(3);
        },
        { timeout: 5000 },
      );
      await new Promise((resolve) => setTimeout(resolve, mode === 'timeout' ? 600 : 100));
      expect(client.events).toEqual([]);
      ownerMode = 'forward';
      await new Promise((resolve) => setTimeout(resolve, 2100));
      await publish(created);
      await vi.waitFor(() => {
        expect(client.events).toHaveLength(1);
      });
      expect(check).toHaveBeenCalledTimes(4);
    },
  );

  it.each(['disconnect', 'original-expiry'] as const)(
    'does not emit after %s during an owner request',
    async (state) => {
      const token =
        state === 'original-expiry'
          ? await issueToken(signingKey, configuration, specialist, {
              role: 'SPECIALIST',
              expiresInSeconds: 2,
            })
          : bearer;
      const client = await connect(0, token);
      const created = await committed();
      let release: () => void = () => undefined;
      ownerGate = new Promise<void>((resolve) => {
        release = resolve;
      });
      const owner = apps[0]?.get(ContentNotificationClient);
      if (!owner) throw new Error('Owner client unavailable');
      const check = vi.spyOn(owner, 'check');
      try {
        await publish(created);
        await vi.waitFor(() => {
          expect(check).toHaveBeenCalled();
        });
        if (state === 'disconnect') client.socket.disconnect();
        else
          await vi.waitFor(
            () => {
              expect(client.socket.connected).toBe(false);
            },
            { timeout: 4000 },
          );
      } finally {
        ownerGate = undefined;
        release();
      }
      await new Promise((resolve) => setTimeout(resolve, 200));
      expect(client.events).toEqual([]);
      const fresh = await connect(1);
      await publish(created);
      await vi.waitFor(() => {
        expect(fresh.events).toHaveLength(1);
      });
    },
  );

  it('suppresses expired and CANCELLED owner rows after the broker handoff', async () => {
    const client = await connect();
    const expired = await committed();
    const cancelled = await committed();
    await database.query(
      "UPDATE notification SET created_at=now()-interval '2 days', expires_at=now()-interval '1 day' WHERE id=$1",
      [expired.aggregateId],
    );
    await database.query("UPDATE notification SET delivery_state='CANCELLED' WHERE id=$1", [
      cancelled.aggregateId,
    ]);
    await publish(expired);
    await publish(cancelled);
    await new Promise((resolve) => setTimeout(resolve, 500));
    expect(client.events).toEqual([]);
    for (const value of [expired, cancelled])
      await request(contentUrl)
        .get(`/internal/v1/notifications/${value.aggregateId}/live-eligibility`)
        .set('authorization', `Bearer ${bearer}`)
        .expect(404);
  });

  it('resumes delivery after a real Kafka broker restart', async () => {
    const client = await connect();
    const port = broker.getMappedPort(9093);
    await broker.restart();
    expect(broker.getMappedPort(9093)).toBe(port);
    await vi.waitFor(
      async () => {
        const admin = kafka.admin();
        try {
          await admin.connect();
          expect((await admin.describeCluster()).brokers.length).toBeGreaterThan(0);
        } finally {
          await admin.disconnect();
        }
      },
      { timeout: 30_000 },
    );
    await vi.waitFor(
      () => {
        expect(apps.every((app) => app.get(SpecialistNotificationFanout).available())).toBe(true);
      },
      { timeout: 30_000 },
    );
    const created = await committed();
    await relay.publishDue();
    await vi.waitFor(
      () => {
        expect(client.events.map((value) => value.eventId)).toContain(created.messageId);
      },
      { timeout: 30_000 },
    );
  });

  it('repairs Redis restart gaps from REST and restores delivery on both replicas', async () => {
    const first = await connect(0);
    const second = await connect(1);
    const missed = await committed();
    const originalPort = redis.getMappedPort(6379);
    await redis.restart();
    expect(redis.getMappedPort(6379)).toBe(originalPort);
    await vi.waitFor(
      async () => {
        expect(
          await apps[0]?.get(RedisService).ping(),
          'Shared Redis connection recovers at its original endpoint',
        ).toBe(true);
        for (const [index, app] of apps.entries()) {
          const fanout = app.get(SpecialistNotificationFanout);
          const subscriber = Reflect.get(fanout, 'subscriber') as {
            isOpen: boolean;
            isReady: boolean;
          };
          const transport = {
            redisReady: Reflect.get(fanout, 'redisReady') as boolean,
            kafkaReady: Reflect.get(fanout, 'kafkaReady') as boolean,
            starting: Reflect.get(fanout, 'starting') as boolean,
            startStage: Reflect.get(fanout, 'startStage') as string,
            lastKafkaErrorCode: Reflect.get(fanout, 'lastKafkaErrorCode') as string,
            lastKafkaErrorChain: Reflect.get(fanout, 'lastKafkaErrorChain') as string[],
            lastKafkaTimeoutApi: Reflect.get(fanout, 'lastKafkaTimeoutApi') as string,
            subscriberOpen: subscriber.isOpen,
            subscriberReady: subscriber.isReady,
          };
          expect(
            fanout.available(),
            `Replica ${String(index)} Kafka consumer and Redis subscriber recover: ${JSON.stringify(transport)}`,
          ).toBe(true);
        }
      },
      { timeout: 20_000 },
    );
    const inbox = await request(contentUrl)
      .get('/api/v1/notifications')
      .set('authorization', `Bearer ${bearer}`)
      .expect(200);
    expect(inbox.body).toMatchObject({ unreadCount: 1, items: [{ id: missed.aggregateId }] });
    const created = await committed();
    await relay.publishDue();
    await vi.waitFor(
      () => {
        expect(first.events.map((value) => value.eventId)).toContain(created.messageId);
        expect(second.events.map((value) => value.eventId)).toContain(created.messageId);
      },
      { timeout: 15_000 },
    );
  });
});

function serverUrl(app: INestApplication): string {
  return `http://127.0.0.1:${String(((app.getHttpServer() as Server).address() as AddressInfo).port)}`;
}

async function reservePort(): Promise<number> {
  const reservation = createServer();
  await new Promise<void>((resolve) => {
    reservation.listen(0, '127.0.0.1', resolve);
  });
  const port = (reservation.address() as AddressInfo).port;
  await new Promise<void>((resolve, reject) =>
    reservation.close((error) => {
      if (error) reject(error);
      else resolve();
    }),
  );
  return port;
}
