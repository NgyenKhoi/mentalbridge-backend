import type { INestApplication } from '@nestjs/common';
import { KafkaContainer } from '@testcontainers/kafka';
import { randomUUID } from 'node:crypto';
import { readFile, readdir } from 'node:fs/promises';
import { exportSPKI, generateKeyPair, SignJWT, type KeyLike } from 'jose';
import { Kafka, logLevel, type Consumer, type Producer } from 'kafkajs';
import request from 'supertest';
import { GenericContainer, type StartedTestContainer } from 'testcontainers';
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import { createApplication } from '../../application.js';
import { AppointmentReminderRepository } from '../../appointment-reminders/appointment-reminder.repository.js';
import type { AppointmentStatusChangedEventV2 } from '../../appointment-reminders/appointment-reminder.types.js';
import {
  AppointmentStatusConsumer,
  AppointmentStatusProjector,
} from '../../appointment-reminders/appointment-status.consumer.js';
import {
  SpecialistAppointmentNotificationDedupeConflictError,
  SpecialistAppointmentNotificationRepository,
} from '../../appointment-reminders/specialist-appointment-notification.repository.js';
import { loadConfiguration, type ServiceConfiguration } from '../../configuration/configuration.js';
import { DatabaseService } from '../../database/database.service.js';
import { NotificationPreferenceRepository } from '../../notification-preferences/notification-preference.repository.js';
import { SpecialistNotificationLiveRepository } from '../../specialist-notifications/specialist-notification-live.repository.js';
import { SpecialistNotificationLiveRelay } from '../../specialist-notifications/specialist-notification-live.relay.js';
import {
  LIVE_NOTIFICATION_TOPIC,
  toNotificationCreatedEvent,
} from '../../specialist-notifications/specialist-notification-live.types.js';

describe('specialist live outbox on owned PostgreSQL, Kafka and real Content HTTP', () => {
  let postgres: StartedTestContainer;
  let broker: StartedTestContainer;
  let db: DatabaseService;
  let configuration: ServiceConfiguration;
  let repository: SpecialistNotificationLiveRepository;
  let materializer: SpecialistAppointmentNotificationRepository;
  let relay: SpecialistNotificationLiveRelay;
  let producer: Producer;
  let consumer: Consumer;
  let app: INestApplication;
  let privateKey: KeyLike;
  const received: { key: string; event: ReturnType<typeof toNotificationCreatedEvent> }[] = [];
  const owner = randomUUID();

  function event(): AppointmentStatusChangedEventV2 {
    const appointmentId = randomUUID();
    return {
      messageId: randomUUID(),
      messageType: 'consultation.appointment.status-changed',
      schemaVersion: '2.0',
      producer: 'consultation-service',
      correlationId: randomUUID(),
      occurredAt: new Date().toISOString(),
      aggregateId: appointmentId,
      aggregateVersion: 1,
      payload: {
        appointmentId,
        ownerAccountId: randomUUID(),
        specialistAccountId: owner,
        status: 'REQUESTED',
        scheduledStartAt: new Date(Date.now() + 86_400_000).toISOString(),
        modality: 'IN_APP_CHAT',
        replacesAppointmentId: null,
      },
    };
  }
  async function rows() {
    return (
      await db.query('SELECT * FROM specialist_notification_live_outbox ORDER BY created_at,id')
    ).rows;
  }
  async function create() {
    const source = event();
    await materializer.materialize(source);
    const [row] = await rows();
    if (!row) throw new Error('Missing live outbox row');
    return { source, row };
  }
  async function token(subject = owner, roles = ['SPECIALIST']) {
    return new SignJWT({ roles })
      .setProtectedHeader({ alg: 'RS256' })
      .setSubject(subject)
      .setIssuer('synthetic-identity')
      .setAudience('synthetic-api')
      .setIssuedAt()
      .setExpirationTime('5m')
      .sign(privateKey);
  }
  async function forceDue(id: string) {
    await db.query(
      `UPDATE specialist_notification_live_outbox SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=$1`,
      [id],
    );
  }
  async function expireClaim(id: string) {
    await db.query(
      `UPDATE specialist_notification_live_outbox SET claim_until=clock_timestamp()-interval '1 second' WHERE id=$1`,
      [id],
    );
  }

  beforeAll(async () => {
    postgres = await new GenericContainer('postgres:16-alpine')
      .withEnvironment({
        POSTGRES_USER: 'synthetic',
        POSTGRES_PASSWORD: 'synthetic',
        POSTGRES_DB: 'mb800',
      })
      .withExposedPorts(5432)
      .start();
    broker = await new KafkaContainer('confluentinc/cp-kafka:7.8.0').withKraft().start();
    const keys = await generateKeyPair('RS256');
    privateKey = keys.privateKey;
    configuration = loadConfiguration({
      NODE_ENV: 'test',
      LOG_LEVEL: 'silent',
      DATABASE_URL:
        'postgres://synthetic:synthetic@' +
        postgres.getHost() +
        ':' +
        String(postgres.getMappedPort(5432)) +
        '/mb800',
      IDENTITY_JWT_ISSUER: 'synthetic-identity',
      IDENTITY_JWT_AUDIENCE: 'synthetic-api',
      IDENTITY_JWT_PUBLIC_KEY: await exportSPKI(keys.publicKey),
      IDENTITY_JWT_CLOCK_TOLERANCE_SECONDS: '0',
    });
    db = new DatabaseService(configuration);
    await vi.waitFor(
      async () => {
        await db.check();
      },
      { timeout: 30_000 },
    );
    const migrations = (await readdir(new URL('../../../migrations/', import.meta.url)))
      .filter((name) => /^\d+_.+\.sql$/.test(name))
      .sort((a, b) => parseInt(a) - parseInt(b));
    for (const name of migrations)
      await db.query(
        await readFile(new URL('../../../migrations/' + name, import.meta.url), 'utf8'),
      );
    materializer = new SpecialistAppointmentNotificationRepository(db);
    repository = new SpecialistNotificationLiveRepository(db);
    const brokers = [broker.getHost() + ':' + String(broker.getMappedPort(9093))];
    const kafka = new Kafka({
      clientId: 'mb800-content-integration',
      brokers,
      logLevel: logLevel.NOTHING,
    });
    const admin = kafka.admin();
    await admin.connect();
    await admin.createTopics({
      topics: [{ topic: LIVE_NOTIFICATION_TOPIC, numPartitions: 1, replicationFactor: 1 }],
    });
    await admin.disconnect();
    configuration = {
      ...configuration,
      KAFKA_BOOTSTRAP_SERVERS: brokers[0],
      CONTENT_SPECIALIST_LIVE_RELAY_ENABLED: true,
    };
    relay = new SpecialistNotificationLiveRelay(configuration, repository);
    producer = kafka.producer({ idempotent: true, allowAutoTopicCreation: false });
    await producer.connect();
    consumer = kafka.consumer({ groupId: 'mb800-content-test-' + randomUUID() });
    await consumer.connect();
    await consumer.subscribe({ topic: LIVE_NOTIFICATION_TOPIC, fromBeginning: true });
    await consumer.run({
      eachMessage: async ({ message }) => {
        received.push({
          key: message.key?.toString() ?? '',
          event: JSON.parse(message.value?.toString() ?? '{}'),
        });
      },
    });
    app = await createApplication({
      ...configuration,
      KAFKA_BOOTSTRAP_SERVERS: undefined,
      CONTENT_SPECIALIST_LIVE_RELAY_ENABLED: false,
    });
    await app.init();
  }, 180_000);
  beforeEach(async () => {
    await db.query('TRUNCATE notification,notification_preference CASCADE');
  });
  afterAll(async () => {
    await app?.close();
    await relay?.onApplicationShutdown();
    await consumer?.disconnect();
    await producer?.disconnect();
    await db?.onModuleDestroy();
    await broker?.stop();
    await postgres?.stop();
  });

  it('commits one immutable creation identity across simultaneous duplicates and new transport IDs', async () => {
    const source = event();
    await Promise.all(
      Array.from({ length: 8 }, () =>
        materializer.materialize({ ...source, messageId: randomUUID() }),
      ),
    );
    const stored = await rows();
    expect(stored).toHaveLength(1);
    expect((await db.query('SELECT id FROM notification')).rows).toHaveLength(1);
    expect(stored[0]).toMatchObject({
      recipient_account_id: owner,
      aggregate_version: '0',
      state: 'PENDING',
      attempt_count: 0,
    });
    await expect(
      materializer.materialize({ ...source, payload: { ...source.payload, status: 'CANCELLED' } }),
    ).rejects.toBeInstanceOf(SpecialistAppointmentNotificationDedupeConflictError);
    expect(await rows()).toHaveLength(1);
    await expect(
      db.query('UPDATE specialist_notification_live_outbox SET correlation_id=$2 WHERE id=$1', [
        stored[0]!.id,
        randomUUID(),
      ]),
    ).rejects.toThrow('identity is immutable');
  });

  it('rolls back inbox, preferences and outbox together when the outbox insert fails', async () => {
    await db.query(
      `ALTER TABLE specialist_notification_live_outbox ADD CONSTRAINT synthetic_failure CHECK (recipient_account_id <> '${owner}'::uuid)`,
    );
    try {
      await expect(materializer.materialize(event())).rejects.toThrow('synthetic_failure');
    } finally {
      await db.query(
        'ALTER TABLE specialist_notification_live_outbox DROP CONSTRAINT synthetic_failure',
      );
    }
    expect(await rows()).toHaveLength(0);
    expect((await db.query('SELECT id FROM notification')).rows).toHaveLength(0);
    expect((await db.query('SELECT user_id FROM notification_preference')).rows).toHaveLength(0);
  });

  it('preserves legacy v1 reminder intake without inferring a specialist recipient or enqueueing history', async () => {
    const source = event();
    const { specialistAccountId: _specialistAccountId, ...payload } = source.payload;
    const intake = new AppointmentStatusConsumer(
      configuration,
      new AppointmentStatusProjector(
        new AppointmentReminderRepository(db),
        { now: () => new Date() },
        materializer,
      ),
    );
    await intake.handle(Buffer.from(JSON.stringify({ ...source, schemaVersion: '1.0', payload })));
    expect(
      (
        await db.query(
          'SELECT appointment_id FROM appointment_reminder_checkpoint WHERE appointment_id=$1',
          [source.aggregateId],
        )
      ).rows,
    ).toEqual([{ appointment_id: source.aggregateId }]);
    expect((await db.query('SELECT id FROM notification')).rows).toHaveLength(0);
    expect(await rows()).toHaveLength(0);
  });

  it('still delivers a CANCELLED appointment-status notification when inbox delivery is eligible', async () => {
    const source = event();
    await materializer.materialize({
      ...source,
      payload: { ...source.payload, status: 'CANCELLED' },
    });
    const [row] = await rows();
    expect(row).toMatchObject({ state: 'PENDING' });
    expect((await db.query('SELECT delivery_state FROM notification')).rows).toEqual([
      { delivery_state: 'DELIVERED' },
    ]);
    await relay.publishDue(1);
    expect((await rows())[0]).toMatchObject({ state: 'PUBLISHED' });
  });

  it.each(['notifications_enabled', 'channel_in_app_enabled', 'group_appointment_message_enabled'])(
    'creates no creation event when %s is already disabled at intake',
    async (column) => {
      await new NotificationPreferenceRepository(db).createDefaults(owner, 'SPECIALIST');
      await db.query(`UPDATE notification_preference SET ${column}=false WHERE user_id=$1`, [
        owner,
      ]);
      await materializer.materialize(event());
      expect((await db.query('SELECT delivery_state FROM notification')).rows).toEqual([
        { delivery_state: 'CANCELLED' },
      ]);
      expect(await rows()).toHaveLength(0);
    },
  );

  it('creates no live outbox for preference-off intake and serializes a concurrent preference update', async () => {
    const preferences = new NotificationPreferenceRepository(db);
    await preferences.createDefaults(owner, 'SPECIALIST');
    let release!: () => void;
    let locked!: () => void;
    const releaseGate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const lockedGate = new Promise<void>((resolve) => {
      locked = resolve;
    });
    const preferenceUpdate = db.withTransaction(async (client) => {
      await client.query(
        'UPDATE notification_preference SET group_appointment_message_enabled=false WHERE user_id=$1',
        [owner],
      );
      locked();
      await releaseGate;
    });
    await lockedGate;
    let completed = false;
    const intake = materializer.materialize(event()).finally(() => {
      completed = true;
    });
    try {
      await new Promise((resolve) => setTimeout(resolve, 75));
      expect(completed).toBe(false);
    } finally {
      release();
    }
    await Promise.all([preferenceUpdate, intake]);
    expect((await db.query('SELECT delivery_state FROM notification')).rows).toEqual([
      { delivery_state: 'CANCELLED' },
    ]);
    expect(await rows()).toHaveLength(0);
  });

  it('claims concurrently once, recovers a crash before ACK and fences every stale completion', async () => {
    const { row } = await create();
    const concurrent = await Promise.all([repository.claim(), repository.claim()]);
    expect(concurrent.filter(Boolean)).toHaveLength(1);
    const stale = concurrent.find(Boolean)!;
    expect(stale.id).toBe(row.id);
    await expireClaim(row.id);
    const current = await repository.claim();
    expect(current?.id).toBe(stale.id);
    expect(current?.claim_token).not.toBe(stale.claim_token);
    expect(await repository.published(stale)).toBe(false);
    expect(await repository.suppress(stale)).toBe(false);
    expect(await repository.retry(stale)).toBe(false);
    expect(await repository.eligibleClaim(current!)).toBe(true);
    await repository.retry(current!);
    expect((await rows())[0]).toMatchObject({
      state: 'PENDING',
      attempt_count: 2,
      claim_token: null,
    });
  });

  it.each(['notifications_enabled', 'channel_in_app_enabled', 'group_appointment_message_enabled'])(
    'suppresses relay when current %s preference is disabled',
    async (column) => {
      const { row } = await create();
      await db.query(`UPDATE notification_preference SET ${column}=false WHERE user_id=$1`, [
        owner,
      ]);
      await relay.publishDue();
      expect((await rows())[0]).toMatchObject({
        state: 'SUPPRESSED',
        published_at: null,
        last_error_code: 'NOT_ELIGIBLE',
      });
      expect(received.filter((item) => item.event.messageId === row.id)).toHaveLength(0);
      expect(await repository.eligible(row.notification_id, owner)).toBe(null);
    },
  );

  it.each(['CANCELLED', 'DELETED', 'EXPIRED', 'WRONG_SOURCE', 'WRONG_ROLE'])(
    'suppresses an already queued %s notification before publication',
    async (state) => {
      const { row } = await create();
      if (state === 'CANCELLED')
        await db.query(`UPDATE notification SET delivery_state='CANCELLED' WHERE id=$1`, [
          row.notification_id,
        ]);
      if (state === 'DELETED')
        await db.query('UPDATE notification SET deleted_at=clock_timestamp() WHERE id=$1', [
          row.notification_id,
        ]);
      if (state === 'EXPIRED')
        await db.query(
          `UPDATE notification SET created_at=clock_timestamp()-interval '2 days', expires_at=clock_timestamp()-interval '1 day' WHERE id=$1`,
          [row.notification_id],
        );
      if (state === 'WRONG_SOURCE')
        await db.query(`UPDATE notification SET source='SYNTHETIC_OTHER_SOURCE' WHERE id=$1`, [
          row.notification_id,
        ]);
      if (state === 'WRONG_ROLE')
        await db.query(`UPDATE notification_preference SET owner_type='USER' WHERE user_id=$1`, [
          owner,
        ]);
      await relay.publishDue();
      expect((await rows())[0]).toMatchObject({ state: 'SUPPRESSED', published_at: null });
      expect(received.filter((item) => item.event.messageId === row.id)).toHaveLength(0);
      expect(await repository.eligible(row.notification_id, owner)).toBe(null);
    },
  );

  it('retains inbox correctness through a real broker connection outage and recovers to ACKed publication', async () => {
    const { row } = await create();
    const unavailable = new SpecialistNotificationLiveRelay(
      { ...configuration, KAFKA_BOOTSTRAP_SERVERS: '127.0.0.1:1' },
      repository,
    );
    try {
      await unavailable.publishDue(1);
    } finally {
      await unavailable.onApplicationShutdown();
    }
    expect((await rows())[0]).toMatchObject({
      state: 'PENDING',
      attempt_count: 1,
      published_at: null,
      last_error_code: 'PUBLISH_UNAVAILABLE',
    });
    expect((await db.query('SELECT delivery_state FROM notification')).rows).toEqual([
      { delivery_state: 'DELIVERED' },
    ]);
    await forceDue(row.id);
    await relay.publishDue(1);
    expect((await rows())[0]).toMatchObject({ state: 'PUBLISHED', attempt_count: 2 });
    await vi.waitFor(
      () => {
        expect(received.filter((item) => item.event.messageId === row.id)).toHaveLength(1);
      },
      { timeout: 30_000 },
    );
    const delivered = received.find((item) => item.event.messageId === row.id)!;
    expect(delivered.key).toBe(row.notification_id);
    expect(delivered.event.payload).toEqual({
      notificationId: row.notification_id,
      recipientAccountId: owner,
      recipientRole: 'SPECIALIST',
      expiresAt: new Date(row.expires_at).toISOString(),
    });
  });

  it('repeats the same creation identity after Kafka ACK then crash without rewriting read version', async () => {
    const { row } = await create();
    const abandoned = await repository.claim();
    const first = toNotificationCreatedEvent(abandoned!);
    await producer.send({
      topic: LIVE_NOTIFICATION_TOPIC,
      acks: -1,
      messages: [{ key: first.aggregateId, value: JSON.stringify(first) }],
    });
    await db.query(
      'UPDATE notification SET read_at=clock_timestamp(),version=version+1 WHERE id=$1',
      [row.notification_id],
    );
    await expireClaim(row.id);
    await relay.publishDue(1);
    await vi.waitFor(
      () => {
        expect(received.filter((item) => item.event.messageId === row.id)).toHaveLength(2);
      },
      { timeout: 30_000 },
    );
    expect(
      received.filter((item) => item.event.messageId === row.id).map((item) => item.event),
    ).toEqual([first, first]);
    expect(first.aggregateVersion).toBe(0);
    expect(
      (await db.query('SELECT version FROM notification WHERE id=$1', [row.notification_id]))
        .rows[0]?.version,
    ).toBe('1');
  });

  it('exhausts bounded publish attempts and recovers an abandoned fifth claim to FAILED', async () => {
    const { row } = await create();
    for (let attempt = 1; attempt <= 5; attempt++) {
      const claimed = await repository.claim();
      expect(claimed?.attempt_count).toBe(attempt);
      if (attempt === 5) await expireClaim(row.id);
      else {
        await repository.retry(claimed!);
        await forceDue(row.id);
      }
    }
    expect(await repository.claim()).toBe(null);
    expect((await rows())[0]).toMatchObject({
      state: 'FAILED',
      attempt_count: 5,
      last_error_code: 'CLAIM_EXHAUSTED',
    });
    expect((await db.query('SELECT delivery_state FROM notification')).rows).toEqual([
      { delivery_state: 'DELIVERED' },
    ]);
  });

  it('exhausts the fifth ordinary publish failure without changing the durable inbox', async () => {
    const { row } = await create();
    for (let attempt = 1; attempt <= 5; attempt++) {
      const claimed = await repository.claim();
      expect(claimed?.attempt_count).toBe(attempt);
      expect(await repository.retry(claimed!)).toBe(true);
      if (attempt < 5) await forceDue(row.id);
    }
    expect(await repository.claim()).toBe(null);
    expect((await rows())[0]).toMatchObject({
      state: 'FAILED',
      attempt_count: 5,
      last_error_code: 'PUBLISH_UNAVAILABLE',
      next_attempt_at: null,
      claim_token: null,
      claim_until: null,
      published_at: null,
    });
    expect((await db.query('SELECT delivery_state FROM notification')).rows).toEqual([
      { delivery_state: 'DELIVERED' },
    ]);
  });

  it('serves current real owner eligibility and REST recovery after preference-off, read and delete', async () => {
    const { row } = await create();
    const path = '/internal/v1/notifications/' + String(row.notification_id) + '/live-eligibility';
    const bearer = await token();
    const eligible = await request(app.getHttpServer())
      .get(path)
      .auth(bearer, { type: 'bearer' })
      .expect(200);
    expect(eligible.body).toEqual({ notificationId: row.notification_id });
    expect(eligible.headers['cache-control']).toBe('private, no-store');
    await request(app.getHttpServer())
      .get(path)
      .auth(await token(randomUUID()), { type: 'bearer' })
      .expect(404);
    for (const role of ['USER', 'ADMIN'])
      await request(app.getHttpServer())
        .get(path)
        .auth(await token(owner, [role]), { type: 'bearer' })
        .expect(403);
    await relay.publishDue(1);
    await db.query(
      'UPDATE notification_preference SET group_appointment_message_enabled=false WHERE user_id=$1',
      [owner],
    );
    const denied = await request(app.getHttpServer())
      .get(path)
      .auth(bearer, { type: 'bearer' })
      .expect(404);
    expect(denied.body.code).toBe('NOTIFICATION_NOT_FOUND');
    let inbox = await request(app.getHttpServer())
      .get('/api/v1/notifications')
      .auth(bearer, { type: 'bearer' })
      .expect(200);
    expect(inbox.body.items).toHaveLength(1);
    expect(inbox.body.unreadCount).toBe(1);
    await request(app.getHttpServer())
      .patch('/api/v1/notifications/' + String(row.notification_id) + '/read')
      .auth(bearer, { type: 'bearer' })
      .expect(200);
    inbox = await request(app.getHttpServer())
      .get('/api/v1/notifications')
      .auth(bearer, { type: 'bearer' })
      .expect(200);
    expect(inbox.body.unreadCount).toBe(0);
    await request(app.getHttpServer())
      .delete('/api/v1/notifications/' + String(row.notification_id))
      .auth(bearer, { type: 'bearer' })
      .expect(204);
    inbox = await request(app.getHttpServer())
      .get('/api/v1/notifications')
      .auth(bearer, { type: 'bearer' })
      .expect(200);
    expect(inbox.body.items).toEqual([]);
    expect((await rows())[0]?.state).toBe('PUBLISHED');
  });
});
