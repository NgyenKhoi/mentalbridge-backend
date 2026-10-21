import { randomUUID, createHash } from 'node:crypto';
import { readFile, readdir } from 'node:fs/promises';
import { createServer, type Server } from 'node:http';
import { KafkaContainer } from '@testcontainers/kafka';
import { GenericContainer, type StartedTestContainer } from 'testcontainers';
import { Kafka, logLevel, type Producer } from 'kafkajs';
import { exportPKCS8, exportSPKI, generateKeyPair, jwtVerify } from 'jose';
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { loadConfiguration, type ServiceConfiguration } from '../../configuration/configuration.js';
import { DatabaseService } from '../../database/database.service.js';
import { CommunityNotificationJobs } from '../../community-notifications/community-notification.jobs.js';
import { CommunityNotificationRepository } from '../../community-notifications/community-notification.repository.js';
import { CommunityNotificationWorker } from '../../community-notifications/community-notification.worker.js';
import { CommunityNotificationClient } from '../../community-notifications/community-notification.client.js';
import {
  CommunityNoticeConsumer,
  communityChatProjection,
  CommunityChatFactSchema,
  COMMUNITY_CHAT_TOPIC,
  COMMUNITY_COMMENT_REACTION_TOPIC,
} from '../../community-notifications/community-notice.consumer.js';
import { NotificationRepository } from '../../notifications/notification.repository.js';
import type { CommunityNotificationProjection } from '../../community-notifications/community-interaction.types.js';

describe('Community notice durable delivery on PostgreSQL and Kafka', () => {
  let postgres: StartedTestContainer;
  let broker: StartedTestContainer;
  let db: DatabaseService;
  let producer: Producer;
  let configuration: ServiceConfiguration;
  let server: Server;
  let jobs: CommunityNotificationJobs;
  let repository: CommunityNotificationRepository;
  let ownerClient: CommunityNotificationClient;
  let worker: CommunityNotificationWorker;
  let inbox: NotificationRepository;
  let instant: Date | undefined;
  let allow = true;
  let outage = false;
  let authorizations = 0;
  const owner = randomUUID();
  const clock = { now: () => instant ?? new Date(Date.now() + 1000) };
  const digest = (value: CommunityNotificationProjection) =>
    createHash('sha256').update(JSON.stringify(value)).digest('hex');
  function projection(): CommunityNotificationProjection {
    return {
      eventId: randomUUID(),
      ownerId: owner,
      kind: 'COMMUNITY_REACTION',
      title: 'Thông báo cộng đồng',
      body: 'Chia sẻ của bạn nhận được sự đồng cảm.',
      occurredAt: new Date().toISOString(),
      postId: randomUUID(),
    };
  }
  function chat() {
    const conversation = randomUUID();
    return {
      messageId: randomUUID(),
      messageType: 'community.chat.fact',
      schemaVersion: '1.0',
      producer: 'community-service',
      occurredAt: new Date().toISOString(),
      correlationId: randomUUID(),
      aggregateId: conversation,
      aggregateVersion: '1',
      payload: {
        kind: 'MESSAGE_RECEIVED',
        conversationId: conversation,
        targetId: randomUUID(),
        actorCommunityProfileId: randomUUID(),
        recipientRoutingReference: owner,
      },
    };
  }
  beforeAll(async () => {
    postgres = await new GenericContainer('postgres:16-alpine')
      .withEnvironment({
        POSTGRES_USER: 'synthetic',
        POSTGRES_PASSWORD: 'synthetic',
        POSTGRES_DB: 'notices',
      })
      .withExposedPorts(5432)
      .start();
    broker = await new KafkaContainer('confluentinc/cp-kafka:7.8.0').withKraft().start();
    const keys = await generateKeyPair('RS256', { extractable: true });
    server = createServer((request, response) => {
      void (async () => {
        await jwtVerify(request.headers.authorization?.slice(7) ?? '', keys.publicKey, {
          issuer: 'content-notification-service',
          subject: 'content-notification-service',
          audience: 'community-service',
        });
        for await (const _chunk of request) {
          /* drain synthetic target query */
        }
        authorizations++;
        response.writeHead(outage ? 503 : allow ? 200 : 404, {
          'Content-Type': 'application/json',
        });
        response.end(JSON.stringify({ allowed: allow, decisionVersion: '0' }));
      })().catch(() => {
        response.writeHead(500);
        response.end();
      });
    });
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') throw new Error('Fixture unavailable');
    configuration = loadConfiguration({
      NODE_ENV: 'test',
      DATABASE_URL: `postgresql://synthetic:synthetic@${postgres.getHost()}:${String(postgres.getMappedPort(5432))}/notices`,
      IDENTITY_JWT_ISSUER: 'synthetic-identity',
      IDENTITY_JWT_AUDIENCE: 'synthetic-api',
      IDENTITY_JWT_PUBLIC_KEY: await exportSPKI(keys.publicKey),
      COMMUNITY_SERVICE_URL: 'http://127.0.0.1:' + String(address.port),
      CONTENT_COMMUNITY_NOTICE_CONSUMER_ENABLED: 'true',
      CONTENT_COMMUNITY_NOTIFICATION_PRIVATE_KEY: await exportPKCS8(keys.privateKey),
      CONTENT_COMMUNITY_NOTIFICATION_KEY_ID: 'content-test',
      KAFKA_BOOTSTRAP_SERVERS: broker.getHost() + ':' + String(broker.getMappedPort(9093)),
    });
    db = new DatabaseService(configuration);
    const migrations = (await readdir(new URL('../../../migrations/', import.meta.url)))
      .filter((name) => /^\d+_.+\.sql$/.test(name))
      .sort((a, b) => parseInt(a) - parseInt(b));
    for (const name of migrations)
      await db.query(
        await readFile(new URL('../../../migrations/' + name, import.meta.url), 'utf8'),
      );
    jobs = new CommunityNotificationJobs(db);
    repository = new CommunityNotificationRepository(db);
    inbox = new NotificationRepository(db);
    ownerClient = new CommunityNotificationClient(configuration);
    await ownerClient.onModuleInit();
    worker = new CommunityNotificationWorker(configuration, jobs, repository, ownerClient, clock);
    const kafka = new Kafka({
      clientId: 'mb691-integration',
      brokers: [configuration.KAFKA_BOOTSTRAP_SERVERS ?? ''],
      logLevel: logLevel.NOTHING,
    });
    const admin = kafka.admin();
    await admin.connect();
    await admin.createTopics({
      topics: [COMMUNITY_CHAT_TOPIC, COMMUNITY_COMMENT_REACTION_TOPIC].map((topic) => ({
        topic,
        numPartitions: 1,
        replicationFactor: 1,
      })),
    });
    await admin.disconnect();
    producer = kafka.producer({ allowAutoTopicCreation: false });
    await producer.connect();
  }, 180_000);
  beforeEach(async () => {
    await db.query(
      'TRUNCATE community_notification_job,notification,notification_preference CASCADE',
    );
    allow = true;
    outage = false;
    authorizations = 0;
    instant = undefined;
  });
  afterAll(async () => {
    await producer?.disconnect();
    await db?.onModuleDestroy();
    server?.closeAllConnections();
    if (server) await new Promise<void>((resolve) => server.close(() => resolve()));
    await broker?.stop();
    await postgres?.stop();
  });
  it('atomically groups unread reactions across workers and keeps read/deleted receipt outcomes stable', async () => {
    const first = { ...projection(), groupingKey: 'POST:' + randomUUID() };
    const second = { ...first, eventId: randomUUID() };
    await Promise.all([jobs.enqueue(first, digest(first)), jobs.enqueue(second, digest(second))]);
    const replica = new CommunityNotificationWorker(
      configuration,
      new CommunityNotificationJobs(db),
      new CommunityNotificationRepository(db),
      ownerClient,
      clock,
    );
    await Promise.all([worker.runDue(), replica.runDue()]);
    let page = await inbox.list(owner, 20, null);
    expect(page.items).toHaveLength(1);
    expect(page.unreadCount).toBe(1);
    const grouped = await db.query<{ count: string }>(
      'SELECT count(DISTINCT notification_id)::text AS count FROM community_notification_job',
    );
    expect(grouped.rows[0]?.count).toBe('1');
    const notice = page.items[0];
    if (!notice) throw new Error('Missing notice');
    await inbox.markRead(owner, notice.id);
    const third = { ...first, eventId: randomUUID() };
    await jobs.enqueue(third, digest(third));
    await worker.runDue();
    page = await inbox.list(owner, 20, null);
    expect(page.items).toHaveLength(2);
    expect(page.unreadCount).toBe(1);
    await inbox.delete(owner, notice.id);
    await jobs.enqueue(first, digest(first));
    await worker.runDue();
    expect((await inbox.list(owner, 20, null)).items).toHaveLength(1);
  });
  it('defers new inbox notices through quiet hours and reauthorizes the exact target at deferred delivery', async () => {
    instant = new Date();
    instant.setUTCDate(instant.getUTCDate() + 1);
    instant.setUTCHours(22, 30, 0, 0);
    await db.query(
      `INSERT INTO notification_preference(user_id,quiet_hours_enabled,quiet_hours_start,quiet_hours_end,time_zone)
      VALUES($1,true,'22:00','07:00','UTC')`,
      [owner],
    );
    const event = communityChatProjection(CommunityChatFactSchema.parse(chat()));
    if (!event) throw new Error('Missing projection');
    await jobs.enqueue(event, digest(event));
    await worker.runDue();
    expect((await inbox.list(owner, 20, null)).items).toEqual([]);
    expect(authorizations).toBe(1);
    allow = false;
    instant.setUTCDate(instant.getUTCDate() + 1);
    instant.setUTCHours(7, 1, 0, 0);
    await worker.runDue();
    expect(authorizations).toBe(2);
    expect((await inbox.list(owner, 20, null)).items).toEqual([]);
    expect(
      (await db.query<{ state: string }>('SELECT state FROM community_notification_job')).rows[0]
        ?.state,
    ).toBe('CANCELLED');
  });
  it('does not revive intake opt-out after opt-in and retries owner uncertainty without a cached grant', async () => {
    await db.query(
      'INSERT INTO notification_preference(user_id,notifications_enabled) VALUES($1,false)',
      [owner],
    );
    const ignored = projection();
    await jobs.enqueue(ignored, digest(ignored));
    await db.query(
      'UPDATE notification_preference SET notifications_enabled=true WHERE user_id=$1',
      [owner],
    );
    await jobs.enqueue(ignored, digest(ignored));
    await worker.runDue();
    expect((await inbox.list(owner, 20, null)).items).toEqual([]);
    const event = communityChatProjection(CommunityChatFactSchema.parse(chat()));
    if (!event) throw new Error('Missing projection');
    await jobs.enqueue(event, digest(event));
    outage = true;
    await worker.runDue();
    expect((await inbox.list(owner, 20, null)).items).toEqual([]);
    outage = false;
    instant = new Date(Date.now() + 5000);
    await worker.runDue();
    expect((await inbox.list(owner, 20, null)).items).toHaveLength(1);
    await expect(jobs.enqueue({ ...event, body: 'Changed' }, 'f'.repeat(64))).rejects.toThrow(
      'identity conflict',
    );
  });
  it('recovers an expired worker claim and fences stale completion before creating the inbox item', async () => {
    const event = projection();
    await jobs.enqueue(event, digest(event));
    const abandoned = await jobs.claim(clock.now());
    if (!abandoned) throw new Error('Missing claim');
    instant = new Date(Date.now() + 32_000);
    const current = await jobs.claim(clock.now());
    if (!current) throw new Error('Missing replacement claim');
    await repository.materialize(event, digest(event), abandoned, clock.now());
    expect((await inbox.list(owner, 20, null)).items).toEqual([]);
    await repository.materialize(event, digest(event), current, clock.now());
    expect((await inbox.list(owner, 20, null)).items).toHaveLength(1);
  });
  it('catches up after consumer downtime and dedupes a replay with a new envelope on real Kafka', async () => {
    const event = chat();
    const publish = async (value: typeof event) =>
      producer.send({
        topic: COMMUNITY_CHAT_TOPIC,
        messages: [{ key: event.aggregateId, value: JSON.stringify(value) }],
      });
    await publish(event);
    const intake = vi
      .spyOn(jobs, 'enqueue')
      .mockRejectedValueOnce(new Error('synthetic intake database outage'));
    const deadLetters = {
      publish: async () => {
        throw new Error('Unexpected dead letter');
      },
    };
    let consumer = new CommunityNoticeConsumer(configuration, jobs, deadLetters);
    try {
      await consumer.onModuleInit();
      await expect
        .poll(
          async () =>
            (
              await db.query<{ count: string }>(
                'SELECT count(*)::text AS count FROM community_notification_job',
              )
            ).rows[0]?.count,
          { timeout: 30_000 },
        )
        .toBe('1');
      await consumer.onApplicationShutdown();
      await publish({ ...event, messageId: randomUUID() });
      consumer = new CommunityNoticeConsumer(configuration, jobs, deadLetters);
      await consumer.onModuleInit();
      const admin = new Kafka({
        clientId: 'mb691-offsets',
        brokers: [configuration.KAFKA_BOOTSTRAP_SERVERS ?? ''],
        logLevel: logLevel.NOTHING,
      }).admin();
      await admin.connect();
      try {
        await expect
          .poll(
            async () => {
              const offsets = await admin.fetchOffsets({
                groupId: 'content-notification-community-notices-v1',
                topics: [COMMUNITY_CHAT_TOPIC],
              });
              return offsets[0]?.partitions[0]?.offset;
            },
            { timeout: 30_000 },
          )
          .toBe('2');
      } finally {
        await admin.disconnect();
      }
      await worker.runDue();
      const page = await inbox.list(owner, 20, null);
      expect(page.items).toHaveLength(1);
      expect(page.items[0]?.action).toEqual({
        type: 'OPEN_COMMUNITY_CONVERSATION',
        targetId: event.aggregateId,
        href: '/community/chat/conversations/' + event.aggregateId,
      });
      for (const kind of ['DIRECT_REQUESTED', 'GROUP_INVITED']) {
        const next = chat();
        next.payload.kind = kind;
        await producer.send({
          topic: COMMUNITY_CHAT_TOPIC,
          messages: [{ key: next.aggregateId, value: JSON.stringify(next) }],
        });
      }
      const reaction = {
        eventId: randomUUID(),
        schemaVersion: '1.0',
        actorCommunityProfileId: randomUUID(),
        targetOwnerRoutingReference: owner,
        targetType: 'COMMENT',
        targetId: randomUUID(),
        interactionKind: 'SUPPORT',
        occurredAt: new Date().toISOString(),
        deepLink: { kind: 'COMMUNITY_POST', postId: randomUUID() },
      };
      await producer.send({
        topic: COMMUNITY_COMMENT_REACTION_TOPIC,
        messages: [{ key: reaction.targetId, value: JSON.stringify(reaction) }],
      });
      await expect
        .poll(
          async () =>
            (
              await db.query<{ count: string }>(
                'SELECT count(*)::text AS count FROM community_notification_job',
              )
            ).rows[0]?.count,
          { timeout: 30_000 },
        )
        .toBe('4');
      await worker.runDue();
      const all = await inbox.list(owner, 20, null);
      expect(all.items.map((item) => item.kind).sort()).toEqual(
        [
          'COMMUNITY_MESSAGE',
          'COMMUNITY_REQUEST',
          'COMMUNITY_INVITATION',
          'COMMUNITY_REACTION',
        ].sort(),
      );
      expect(all.items.find((item) => item.kind === 'COMMUNITY_REACTION')?.action?.targetId).toBe(
        reaction.deepLink.postId,
      );
      expect(
        (
          await db.query<{ count: string }>(
            'SELECT count(*)::text AS count FROM community_notification_job',
          )
        ).rows[0]?.count,
      ).toBe('4');
    } finally {
      await consumer.onApplicationShutdown();
      intake.mockRestore();
    }
  }, 90_000);
});
