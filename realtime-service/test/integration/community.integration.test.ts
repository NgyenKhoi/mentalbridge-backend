import { randomUUID } from 'node:crypto';
import type { Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { createServer } from 'node:net';
import type { INestApplication } from '@nestjs/common';
import type { StartedKafkaContainer } from '@testcontainers/kafka';
import { CommunityKafkaContainer } from '../fixtures/community-kafka.js';
import { exportPKCS8, exportSPKI, generateKeyPair, SignJWT, type CryptoKey } from 'jose';
import { Kafka, logLevel, type Producer } from 'kafkajs';
import { io, type Socket } from 'socket.io-client';
import request from 'supertest';
import { GenericContainer, type StartedTestContainer, Wait } from 'testcontainers';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { z } from 'zod';
import { createApplication } from '../../src/application.js';
import {
  CommunityFanout,
  COMMUNITY_TOPIC,
  COMMUNITY_DEAD_LETTER_TOPIC,
} from '../../src/community/community-fanout.js';
import {
  messageSchema,
  type CommunityFact,
  type CommunityMessage,
} from '../../src/community/community-schema.js';
import type { ServiceConfiguration } from '../../src/configuration/configuration.js';
import { RedisService } from '../../src/database/redis.service.js';
import { CommunityPresence } from '../../src/community/community-presence.js';
import type { MongoDatabaseService } from '../../src/database/mongo-database.service.js';
import {
  CommunityOwnerFixture,
  communityAlice,
  communityBob,
  communityConversation,
  communityPeer,
} from '../fixtures/community-owner.js';
import { testConfiguration } from '../fixtures/test-configuration.js';
import { issueToken } from '../fixtures/token.js';

const envelope = z
  .object({
    schemaVersion: z.literal(1),
    eventId: z.uuid(),
    correlationId: z.uuid(),
    occurredAt: z.iso.datetime({ offset: true }),
    eventType: z.string(),
    payload: z.record(z.string(), z.unknown()),
  })
  .strict();
type Event = z.infer<typeof envelope>;
const credentialSchema = z
  .object({
    schemaVersion: z.literal(1),
    namespace: z.literal('/community'),
    credential: z.string().min(32),
    expiresAt: z.iso.datetime({ offset: true }),
  })
  .strict();
describe('Community two-replica Kafka, Redis and Socket.IO delivery', { concurrent: false }, () => {
  let broker: StartedKafkaContainer;
  let redisContainer: StartedTestContainer;
  let producer: Producer;
  let kafka: Kafka;
  let owner: CommunityOwnerFixture;
  let bffKey: CryptoKey;
  let identityKey: CryptoKey;
  let configuration: ServiceConfiguration;
  const apps: INestApplication[] = [];
  const urls: string[] = [];
  const clients: { socket: Socket; events: Event[] }[] = [];
  let redisStopped = false;
  function url(index = 0): string {
    const value = urls[index];
    if (!value) throw new Error('Replica URL unavailable');
    return value;
  }
  beforeAll(async () => {
    const reservation = createServer();
    await new Promise<void>((resolve) => {
      reservation.listen(0, '127.0.0.1', resolve);
    });
    const reserved = reservation.address();
    if (!reserved || typeof reserved === 'string') throw new Error('Broker port unavailable');
    const brokerPort = reserved.port;
    await new Promise<void>((resolve, reject) => {
      reservation.close((error) => {
        if (error) reject(error);
        else resolve();
      });
    });
    [broker, redisContainer] = await Promise.all([
      new CommunityKafkaContainer(brokerPort).start(),
      new GenericContainer('redis:7.4-alpine')
        .withExposedPorts(6379)
        .withWaitStrategy(Wait.forLogMessage(/Ready to accept connections/))
        .start(),
    ]);
    kafka = new Kafka({
      clientId: 'community-tests',
      brokers: [broker.getHost() + ':' + String(broker.getMappedPort(9093))],
      logLevel: logLevel.NOTHING,
    });
    const admin = kafka.admin();
    await admin.connect();
    await admin.createTopics({
      topics: [
        { topic: COMMUNITY_TOPIC, numPartitions: 3 },
        { topic: COMMUNITY_DEAD_LETTER_TOPIC, numPartitions: 1 },
      ],
    });
    await admin.disconnect();
    producer = kafka.producer();
    await producer.connect();
    const realtime = await generateKeyPair('RS256', { extractable: true });
    const bff = await generateKeyPair('RS256', { extractable: true });
    const identity = await generateKeyPair('RS256', { extractable: true });
    bffKey = bff.privateKey;
    identityKey = identity.privateKey;
    owner = new CommunityOwnerFixture(realtime.publicKey);
    await owner.start();
    const base = testConfiguration(await exportSPKI(identity.publicKey));
    configuration = {
      ...base,
      LOG_LEVEL: 'warn',
      REDIS_URL:
        'redis://' + redisContainer.getHost() + ':' + String(redisContainer.getMappedPort(6379)),
      COMMUNITY: {
        ...base.COMMUNITY,
        enabled: true,
        baseUrl: owner.url,
        privateKey: await exportPKCS8(realtime.privateKey),
        keyId: 'realtime-test',
        bffPublicKey: await exportSPKI(bff.publicKey),
        bffKeyId: 'bff-test',
        brokers: [broker.getHost() + ':' + String(broker.getMappedPort(9093))],
        groupId: 'community-tests-' + randomUUID(),
        channel: 'community:test:' + randomUUID(),
      },
    };
    for (let index = 0; index < 2; index++) {
      const mongo = {
        ping: () => Promise.resolve(),
        collection: () => {
          throw new Error('Community must not use MongoDB');
        },
        onApplicationShutdown: () => Promise.resolve(),
      } as unknown as MongoDatabaseService;
      const app = await createApplication(configuration, { mongo });
      await app.listen(0, '127.0.0.1');
      apps.push(app);
      urls.push(
        'http://127.0.0.1:' +
          String(((app.getHttpServer() as Server).address() as AddressInfo).port),
      );
    }
    await vi.waitFor(
      () => {
        expect(apps.every((app) => app.get(CommunityFanout).available())).toBe(true);
      },
      { timeout: 20_000 },
    );
  }, 240_000);
  beforeEach(() => {
    owner.reset();
  });
  afterEach(async () => {
    for (const client of clients) client.socket.disconnect();
    clients.splice(0);
    const app = apps[0];
    if (!redisStopped && app)
      await vi.waitFor(
        async () => {
          const count = await app.get(RedisService).execute(async (client) => {
            const keys = await client.keys('community:v1:connections:*');
            return keys.length === 0
              ? 0
              : (await Promise.all(keys.map((key) => client.zCard(key)))).reduce(
                  (a, b) => a + b,
                  0,
                );
          });
          expect(count).toBe(0);
        },
        { timeout: 5000 },
      );
  });
  afterAll(async () => {
    await Promise.allSettled(apps.map((app) => app.close()));
    await producer.disconnect();
    await owner.close();
    await Promise.allSettled([
      broker.stop(),
      redisStopped ? Promise.resolve() : redisContainer.stop(),
    ]);
  });
  async function delegation(
    subject = communityBob,
    expiresIn = 300,
    scope = 'community:socket:issue',
  ): Promise<string> {
    const now = Math.floor(Date.now() / 1000);
    return new SignJWT({ act: { sub: 'community-bff' }, scope, identityExpiresAt: now + expiresIn })
      .setProtectedHeader({ alg: 'RS256', kid: 'bff-test' })
      .setIssuer('community-bff')
      .setAudience('realtime-service')
      .setSubject(subject)
      .setJti(randomUUID())
      .setIssuedAt(now)
      .setExpirationTime(now + 30)
      .sign(bffKey);
  }
  async function ticket(index = 0, subject = communityBob, expiresIn = 300): Promise<string> {
    const response = await request(url(index))
      .post('/internal/v1/community/socket-credentials')
      .set('Authorization', 'Bearer ' + (await delegation(subject, expiresIn)))
      .expect('Cache-Control', 'no-store')
      .expect(200);
    return credentialSchema.parse(response.body as unknown).credential;
  }
  async function connect(
    index = 0,
    subject = communityBob,
    expiresIn = 300,
    existing?: string,
  ): Promise<{ socket: Socket; events: Event[] }> {
    const credential = existing ?? (await ticket(index, subject, expiresIn));
    const socket = io(url(index) + '/community', {
      auth: { schemaVersion: 1, credential },
      transports: ['websocket'],
      reconnection: false,
      forceNew: true,
      autoConnect: false,
      timeout: 5000,
    });
    const events: Event[] = [];
    const client = { socket, events };
    clients.push(client);
    socket.on('community.event', (value: unknown) => {
      events.push(envelope.parse(value));
    });
    await new Promise<void>((resolve, reject) => {
      socket.once('connect', () => {
        resolve();
      });
      socket.once('connect_error', reject);
      socket.connect();
    });
    return client;
  }
  async function subscribe(client: { socket: Socket }): Promise<Event> {
    const value: unknown = await client.socket.timeout(10_000).emitWithAck('community.command', {
      schemaVersion: 1,
      commandId: randomUUID(),
      correlationId: randomUUID(),
      commandType: 'community.conversation.subscribe',
      payload: { conversationId: communityConversation },
    });
    return envelope.parse(value);
  }
  async function heartbeat(client: { socket: Socket }, foreground: boolean): Promise<Event> {
    const value: unknown = await client.socket.timeout(10_000).emitWithAck('community.command', {
      schemaVersion: 1,
      commandId: randomUUID(),
      correlationId: randomUUID(),
      commandType: 'community.presence.heartbeat',
      payload: { foreground },
    });
    return envelope.parse(value);
  }
  function badge(client: { events: Event[] }, profile = communityAlice): unknown {
    let state: unknown;
    for (const event of client.events) {
      if (event.eventType === 'community.conversation.invalidated') state = undefined;
      if (
        event.eventType === 'community.presence.changed' &&
        event.payload.communityProfileId === profile
      )
        state = event.payload.state;
    }
    return state;
  }
  const delivered = (client: { events: Event[] }): CommunityMessage[] =>
    client.events
      .filter((event) => event.eventType === 'community.message.created')
      .map((event) => messageSchema.parse(event.payload.message));
  const updated = (client: { events: Event[] }): CommunityMessage[] =>
    client.events
      .filter((event) => event.eventType === 'community.message.updated')
      .map((event) => messageSchema.parse(event.payload.message));
  const unread = (client: { events: Event[] }) =>
    client.events.filter((event) => event.eventType === 'community.read-state.changed').at(-1)
      ?.payload.unreadCount;
  async function publish(...facts: CommunityFact[]): Promise<void> {
    await producer.send({
      topic: COMMUNITY_TOPIC,
      messages: facts.map((fact) => ({ key: fact.aggregateId, value: JSON.stringify(fact) })),
    });
  }
  it('connects the native USER exchange to the existing namespace across replicas without accepting replay', async () => {
    const response = await request(url())
      .post('/api/v1/community/socket-credentials')
      .set(
        'Authorization',
        'Bearer ' + (await issueToken(identityKey, configuration, communityBob)),
      )
      .send({})
      .expect('Cache-Control', 'no-store')
      .expect(200);
    const credential = credentialSchema.parse(response.body as unknown).credential;
    const native = await connect(1, communityBob, 300, credential);
    expect((await subscribe(native)).eventType).toBe('community.command.ack');
    await expect(connect(0, communityBob, 300, credential)).rejects.toThrow();
    const message = owner.save();
    await publish(owner.fact(message));
    await expect
      .poll(() => delivered(native).map((item) => item.messageId), { timeout: 20_000 })
      .toEqual([message.messageId]);
  });
  it('fans ordered persisted messages to every device despite duplicate and reordered facts', async () => {
    await request(url())
      .get('/health/ready')
      .expect(200)
      .expect((response) => {
        expect((response.body as Record<string, unknown>).community).toBe('connected');
      });
    const bob1 = await connect(0);
    const bob2 = await connect(1);
    const alice = await connect(0, communityAlice);
    for (const client of [bob1, bob2, alice])
      expect((await subscribe(client)).eventType).toBe('community.command.ack');
    const messages = [owner.save(), owner.save(), owner.save()];
    const first = messages[0];
    const last = messages[2];
    if (!first || !last) throw new Error('Fixture missing');
    const duplicate = owner.fact(first);
    await publish(owner.fact(last), duplicate, duplicate, owner.fact(messages[1]));
    await expect
      .poll(() => [delivered(bob1).length, delivered(bob2).length, delivered(alice).length], {
        timeout: 20_000,
      })
      .toEqual([3, 3, 3]);
    for (const client of [bob1, bob2, alice])
      expect(delivered(client).map((message) => message.messageId)).toEqual(
        messages.map((message) => message.messageId),
      );
    expect(unread(bob1)).toBe(3);
    expect(unread(bob2)).toBe(3);
    expect(unread(alice)).toBe(0);
    const redis = apps[0]?.get(RedisService);
    if (!redis) throw new Error('Redis unavailable');
    const data = await redis.execute(async (client) => {
      const keys = await client.keys('community:v1:ticket:*');
      return Promise.all(keys.map((key) => client.get(key)));
    });
    expect(JSON.stringify(data)).not.toContain(first.content);
  });
  it('suppresses hidden traffic and invalidates a removed viewer before further delivery', async () => {
    const bob = await connect(0);
    const peer = await connect(1, communityPeer);
    await subscribe(bob);
    await subscribe(peer);
    const hidden = owner.save();
    owner.hidden.add(communityBob + ':' + hidden.messageId);
    const visible = owner.save(communityPeer);
    await publish(owner.fact(hidden), owner.fact(visible));
    await expect.poll(() => delivered(peer).length, { timeout: 15_000 }).toBe(2);
    await expect.poll(() => delivered(bob).length).toBe(1);
    expect(delivered(bob)[0]?.messageId).toBe(visible.messageId);
    expect(unread(bob)).toBe(1);
    expect(
      bob.events.filter((event) => event.eventType === 'community.resync.required'),
    ).toHaveLength(1);
    owner.denied.add(communityBob);
    owner.epochs.set(communityBob, 1);
    await publish(owner.fact());
    await expect
      .poll(
        () => bob.events.some((event) => event.eventType === 'community.conversation.invalidated'),
        { timeout: 10_000 },
      )
      .toBe(true);
    const next = owner.save();
    await publish(owner.fact(next));
    await expect.poll(() => delivered(peer).length).toBe(3);
    expect(delivered(bob)).toHaveLength(1);
  });
  it('discards a projection queued before a known revocation', async () => {
    const bob = await connect();
    await subscribe(bob);
    const message = owner.save();
    let release: () => void = () => undefined;
    owner.projectionGate = new Promise<void>((resolve) => {
      release = resolve;
    });
    await publish(owner.fact(message));
    await expect
      .poll(() => owner.calls.some((call) => call.message === message.messageId))
      .toBe(true);
    owner.denied.add(communityBob);
    const revoked = owner.fact();
    let observed: () => void = () => undefined;
    const seen = new Promise<void>((resolve) => {
      observed = resolve;
    });
    apps[0]?.get(CommunityFanout).onFact((fact) => {
      if (fact.messageId === revoked.messageId) observed();
    });
    await publish(revoked);
    await seen;
    release();
    delete owner.projectionGate;
    await expect
      .poll(() =>
        bob.events.some((event) => event.eventType === 'community.conversation.invalidated'),
      )
      .toBe(true);
    expect(delivered(bob)).toEqual([]);
  });
  it('replaces unread with the current private read state on every device', async () => {
    const first = await connect(0);
    const second = await connect(1);
    await subscribe(first);
    await subscribe(second);
    const message = owner.save();
    await publish(owner.fact(message));
    await expect.poll(() => unread(second)).toBe(1);
    owner.reads.set(communityBob, 1);
    const fact: CommunityFact = {
      ...owner.fact(),
      payload: {
        kind: 'READ_STATE_CHANGED',
        conversationId: communityConversation,
        recipientRoutingReference: communityBob,
      },
    };
    await publish(fact, fact, fact);
    await expect.poll(() => [unread(first), unread(second)]).toEqual([0, 0]);
    const next = owner.save();
    await publish(owner.fact(next));
    await expect.poll(() => [unread(first), unread(second)]).toEqual([1, 1]);
    for (const client of [first, second]) expect(delivered(client)).toHaveLength(2);
  });
  it('replaces an existing cell with a content-free retraction without appending a message', async () => {
    const bob = await connect(0);
    const alice = await connect(1, communityAlice);
    await subscribe(bob);
    await subscribe(alice);
    const message = owner.save();
    await publish(owner.fact(message));
    await expect.poll(() => [delivered(bob).length, delivered(alice).length]).toEqual([1, 1]);
    expect(unread(bob)).toBe(1);

    const tombstone = owner.retract(message);
    await publish(owner.retraction(tombstone), owner.readStateFact(communityBob));
    await expect.poll(() => [updated(bob).length, updated(alice).length]).toEqual([1, 1]);
    await expect.poll(() => unread(bob)).toBe(0);
    for (const client of [bob, alice]) {
      expect(delivered(client)).toHaveLength(1);
      expect(updated(client)[0]).toEqual(tombstone);
      expect(updated(client)[0]).not.toHaveProperty('content');
    }
  });
  it('requires fresh credentials and resynchronization after offline reconnect without rebroadcasting retained history', async () => {
    const first = await connect();
    await subscribe(first);
    first.socket.disconnect();
    const offline = [owner.save(), owner.save()];
    await publish(...offline.map((message) => owner.fact(message)));
    const reconnected = await connect(1);
    await subscribe(reconnected);
    expect(
      reconnected.events.some((event) => event.eventType === 'community.resync.required'),
    ).toBe(true);
    expect(unread(reconnected)).toBe(2);
    expect(delivered(reconnected)).toEqual([]);
    const current = owner.save();
    await publish(owner.fact(current));
    await expect.poll(() => delivered(reconnected).length).toBe(1);
    expect(delivered(reconnected)[0]?.messageId).toBe(current.messageId);
    expect(owner.messages.slice(0, 2).map((message) => message.messageId)).toEqual(
      offline.map((message) => message.messageId),
    );
  });
  it('isolates one-use tickets from appointment chat and enforces Identity expiry', async () => {
    await request(url())
      .post('/internal/v1/community/socket-credentials')
      .set('Authorization', 'Bearer ' + (await delegation(communityBob, 300, 'wrong:scope')))
      .expect(401);
    const credential = await ticket();
    const active = await connect(0, communityBob, 300, credential);
    await expect(connect(1, communityBob, 300, credential)).rejects.toThrow();
    expect(active.socket.connected).toBe(true);
    const appointmentTicket = await ticket();
    const socket = io(url() + '/realtime', {
      auth: { schemaVersion: 1, accessToken: appointmentTicket },
      transports: ['websocket'],
      reconnection: false,
      forceNew: true,
      autoConnect: false,
    });
    clients.push({ socket, events: [] });
    await expect(
      new Promise<void>((resolve, reject) => {
        socket.once('connect', () => {
          resolve();
        });
        socket.once('connect_error', reject);
        socket.connect();
      }),
    ).rejects.toThrow();
    await connect(1, communityBob, 300, appointmentTicket);
    const expiring = await connect(1, communityPeer, 2);
    await expect.poll(() => expiring.socket.connected, { timeout: 5000 }).toBe(false);
  });
  it('bounds five simultaneous devices across replicas and delivers a 20-message catch-up in order', async () => {
    const devices: { socket: Socket; events: Event[] }[] = [];
    for (let index = 0; index < 5; index++) {
      const client = await connect(index % 2);
      await subscribe(client);
      devices.push(client);
    }
    await expect(connect(1)).rejects.toThrow();
    expect(devices.every((client) => client.socket.connected)).toBe(true);
    const messages = Array.from({ length: 20 }, () => owner.save());
    const last = messages.at(-1);
    if (!last) throw new Error('Fixture missing');
    await publish(owner.fact(last));
    await expect
      .poll(() => devices.map((client) => delivered(client).length), { timeout: 30_000 })
      .toEqual([20, 20, 20, 20, 20]);
    for (const device of devices) {
      expect(delivered(device).map((message) => message.messageId)).toEqual(
        messages.map((message) => message.messageId),
      );
      expect(unread(device)).toBe(20);
    }
  });
  it('dead-letters only safe metadata for poison facts and continues valid delivery', async () => {
    const bob = await connect();
    await subscribe(bob);
    const wires: string[] = [];
    const consumer = kafka.consumer({ groupId: 'poison-test-' + randomUUID() });
    await consumer.connect();
    await consumer.subscribe({ topic: COMMUNITY_DEAD_LETTER_TOPIC, fromBeginning: true });
    await consumer.run({
      eachMessage: ({ message }) => {
        wires.push(message.value?.toString() ?? '');
        return Promise.resolve();
      },
    });
    try {
      const message = owner.save();
      const fact = owner.fact(message);
      await producer.send({
        topic: COMMUNITY_TOPIC,
        messages: [
          {
            key: communityConversation,
            value: JSON.stringify({
              ...fact,
              payload: { ...fact.payload, content: 'DO-NOT-REPEAT-PRIVATE-TEXT' },
            }),
          },
        ],
      });
      await publish(fact);
      await expect.poll(() => delivered(bob).length, { timeout: 15_000 }).toBe(1);
      await expect.poll(() => wires.length).toBe(1);
      expect(wires[0]).toContain('INVALID_COMMUNITY_FACT');
      expect(wires[0]).not.toContain('DO-NOT-REPEAT-PRIVATE-TEXT');
    } finally {
      await consumer.disconnect();
    }
  });
  it('resumes authorized fanout after a real Kafka broker restart', async () => {
    let bob = await connect();
    await subscribe(bob);
    const originalPort = broker.getMappedPort(9093);
    await broker.restart();
    expect(broker.getMappedPort(9093)).toBe(originalPort);
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
        expect(apps.every((app) => app.get(CommunityFanout).available())).toBe(true);
      },
      { timeout: 30_000 },
    );
    if (!bob.socket.connected) {
      bob = await connect(1);
      await subscribe(bob);
    }
    const message = owner.save();
    await publish(owner.fact(message));
    await expect
      .poll(() => delivered(bob).map((value) => value.messageId), { timeout: 30_000 })
      .toEqual([message.messageId]);
  });
  it('keeps ONLINE across two replicas until the last foreground device backgrounds or disconnects', async () => {
    owner.presenceOptIn.add(communityAlice);
    const bob = await connect(0);
    const first = await connect(0, communityAlice);
    const second = await connect(1, communityAlice);
    await subscribe(bob);
    await expect.poll(() => badge(bob)).toBe('OFFLINE');
    expect((await heartbeat(first, true)).eventType).toBe('community.command.ack');
    expect((await heartbeat(second, true)).eventType).toBe('community.command.ack');
    await expect.poll(() => badge(bob)).toBe('ONLINE');
    expect((await heartbeat(first, false)).eventType).toBe('community.command.ack');
    expect(badge(bob)).toBe('ONLINE');
    second.socket.disconnect();
    await expect.poll(() => badge(bob)).toBe('OFFLINE');
    expect(first.socket.connected).toBe(true);
  });

  it('does not infer ONLINE from a live routing socket and repairs expiry/reconnect with a fresh lease', async () => {
    owner.presenceOptIn.add(communityAlice);
    const bob = await connect(1);
    let alice = await connect(0, communityAlice);
    await subscribe(bob);
    await expect.poll(() => badge(bob)).toBe('OFFLINE');
    await heartbeat(alice, true);
    await expect.poll(() => badge(bob)).toBe('ONLINE');
    const app = apps[0];
    if (!app || !alice.socket.id) throw new Error('Fixture unavailable');
    await app.get(RedisService).execute((client) =>
      client.zAdd('community:v1:presence:' + communityAlice, {
        score: Date.now() + 100,
        value: alice.socket.id ?? '',
      }),
    );
    await expect.poll(() => badge(bob), { timeout: 10_000 }).toBe('OFFLINE');
    expect(alice.socket.connected).toBe(true);
    alice.socket.disconnect();
    alice = await connect(1, communityAlice);
    await heartbeat(alice, true);
    await expect.poll(() => badge(bob)).toBe('ONLINE');
  });

  it('suppresses opt-out and invisible subjects and clears badges by identity-free invalidation', async () => {
    const bob = await connect();
    const alice = await connect(1, communityAlice);
    await heartbeat(alice, true);
    await subscribe(bob);
    expect(badge(bob)).toBeUndefined();
    owner.presenceOptIn.add(communityAlice);
    await publish(owner.fact());
    await expect.poll(() => badge(bob)).toBe('ONLINE');
    const before = bob.events.length;
    owner.presenceOptIn.delete(communityAlice);
    await publish(owner.fact());
    await expect.poll(() => badge(bob)).toBeUndefined();
    const invalidations = bob.events
      .slice(before)
      .filter((event) => event.eventType === 'community.conversation.invalidated');
    expect(invalidations.length).toBeGreaterThan(0);
    expect(invalidations[0]?.payload).toEqual({ conversationId: communityConversation });
    expect(
      bob.events.slice(before).filter((event) => event.eventType === 'community.presence.changed'),
    ).toEqual([]);
    owner.presenceOptIn.add(communityAlice);
    owner.presenceInvisible.add(communityAlice);
    await publish(owner.fact());
    await expect
      .poll(() => owner.calls.filter((call) => call.scope === 'community:presence:project').length)
      .toBeGreaterThan(6);
    expect(badge(bob)).toBeUndefined();
  });

  it('removes a revoked pair even when authorization invalidation was lost without affecting other peers', async () => {
    owner.presenceOptIn.add(communityAlice);
    const bob = await connect(0);
    const peer = await connect(1, communityPeer);
    const alice = await connect(1, communityAlice);
    await heartbeat(alice, true);
    await subscribe(bob);
    await subscribe(peer);
    await expect.poll(() => badge(bob)).toBe('ONLINE');
    await expect.poll(() => badge(peer)).toBe('ONLINE');
    owner.presenceHidden.add(communityBob + ':' + communityAlice);
    await expect.poll(() => badge(bob), { timeout: 10_000 }).toBeUndefined();
    expect(badge(peer)).toBe('ONLINE');
  });

  it('lets background supersede an in-flight foreground heartbeat without restoring ONLINE', async () => {
    owner.presenceOptIn.add(communityAlice);
    const bob = await connect();
    const alice = await connect(1, communityAlice);
    await subscribe(bob);
    let release: () => void = () => undefined;
    owner.requestGate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const previous = owner.calls.length;
    const pending = heartbeat(alice, true);
    try {
      await expect.poll(() => owner.calls.length).toBeGreaterThan(previous);
      expect((await heartbeat(alice, false)).eventType).toBe('community.command.ack');
    } finally {
      delete owner.requestGate;
      release();
    }
    expect((await pending).eventType).toBe('community.command.ack');
    const app = apps[0];
    if (!app) throw new Error('Fixture unavailable');
    expect((await app.get(CommunityPresence).states([communityAlice]))[0]?.state).toBe('OFFLINE');
    expect(badge(bob)).toBe('OFFLINE');
    const repeated = await heartbeat(alice, true);
    expect(repeated.eventType).toBe('community.command.error');
    expect(repeated.payload.code).toBe('RATE_LIMITED');
  });

  it('disconnects on owner and Redis failures while preserving owner history for REST repair', async () => {
    const bob = await connect();
    await subscribe(bob);
    owner.unavailable = true;
    const message = owner.save();
    await publish(owner.fact(message));
    await expect.poll(() => bob.socket.connected, { timeout: 10_000 }).toBe(false);
    expect(delivered(bob)).toEqual([]);
    owner.unavailable = false;
    const restored = await connect(1);
    await subscribe(restored);
    expect(unread(restored)).toBe(1);
    await redisContainer.stop();
    redisStopped = true;
    await expect.poll(() => restored.socket.connected, { timeout: 10_000 }).toBe(false);
    expect(owner.messages[0]?.messageId).toBe(message.messageId);
  });
});
