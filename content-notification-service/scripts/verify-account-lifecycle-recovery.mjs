import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { Pool } from 'pg';
import {
  ACCOUNT_LIFECYCLE_TOPIC,
  ACCOUNT_PREFERENCE_DEAD_LETTER_TOPIC,
  AccountLifecycleConsumer,
  AccountLifecycleProjector,
} from '../dist/notification-preferences/account-lifecycle.consumer.js';
import { NotificationPreferenceService } from '../dist/notification-preferences/notification-preference.service.js';
import { NotificationPreferenceRepository } from '../dist/notification-preferences/notification-preference.repository.js';
import { UserNotificationRepository } from '../dist/user-notifications/user-notification.repository.js';

async function eventually(description, predicate) {
  const deadline = Date.now() + 60_000;
  while (Date.now() < deadline) {
    if (await predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  assert.fail(description);
}

export async function verifyAccountLifecycleRecovery(
  kafka,
  broker,
  databaseUrl,
  database,
  pool,
  fact,
) {
  const admin = kafka.admin();
  const producer = kafka.producer({ allowAutoTopicCreation: false });
  const dlq = kafka.consumer({ groupId: `mb565-dlq-${randomUUID()}` });
  const control = new Pool({
    connectionString: databaseUrl.replace('/notification_precision', '/postgres'),
  });
  const parked = [];
  let failures = 0;
  const retryingDatabase = {
    withTransaction: async (operation) => {
      try {
        return await database.withTransaction(operation);
      } catch (error) {
        failures += 1;
        throw error;
      }
    },
  };
  const preferences = new NotificationPreferenceService(
    new NotificationPreferenceRepository(database),
  );
  const consumer = new AccountLifecycleConsumer(
    { CONTENT_ACCOUNT_LIFECYCLE_CONSUMER_ENABLED: 'true', KAFKA_BOOTSTRAP_SERVERS: broker },
    new AccountLifecycleProjector(preferences, new UserNotificationRepository(retryingDatabase)),
  );
  const groupId = 'content-notification-account-preferences-v1';
  async function committed() {
    const offsets = await admin.fetchOffsets({ groupId, topics: [ACCOUNT_LIFECYCLE_TOPIC] });
    return Number(offsets[0]?.partitions[0]?.offset ?? -1);
  }
  async function endOffset() {
    return Number((await admin.fetchTopicOffsets(ACCOUNT_LIFECYCLE_TOPIC))[0].offset);
  }
  async function send(event) {
    await producer.send({
      topic: ACCOUNT_LIFECYCLE_TOPIC,
      acks: -1,
      messages: [{ key: event.aggregateId, value: JSON.stringify(event) }],
    });
  }
  const before = Number((await pool.query('SELECT count(*) FROM notification')).rows[0].count);
  let databaseBlocked = false;
  try {
    await admin.connect();
    await admin.createTopics({
      topics: [
        { topic: ACCOUNT_PREFERENCE_DEAD_LETTER_TOPIC, numPartitions: 1, replicationFactor: 1 },
      ],
    });
    await producer.connect();
    await dlq.connect();
    await dlq.subscribe({ topic: ACCOUNT_PREFERENCE_DEAD_LETTER_TOPIC, fromBeginning: true });
    await dlq.run({
      eachMessage: async ({ message }) => {
        parked.push(JSON.parse(message.value.toString()));
      },
    });
    await consumer.onModuleInit();
    const initialOffset = await endOffset();
    await eventually(
      'initial lifecycle replay must commit',
      async () => (await committed()) >= initialOffset,
    );
    await new Promise((resolve) => setTimeout(resolve, 1500));
    await control.query('ALTER DATABASE notification_precision WITH ALLOW_CONNECTIONS false');
    databaseBlocked = true;
    const outage = {
      ...fact,
      messageId: randomUUID(),
      correlationId: randomUUID(),
      aggregateVersion: 4,
      payload: { ...fact.payload, factType: 'ACCOUNT_RESTORED' },
    };
    await send(outage);
    await eventually(
      'more than three real PostgreSQL failures must occur',
      async () => failures >= 4,
    );
    assert.equal(await committed(), initialOffset, 'failed fact must retain its Kafka offset');
    assert.equal(
      Number((await admin.fetchTopicOffsets(ACCOUNT_PREFERENCE_DEAD_LETTER_TOPIC))[0].offset),
      0,
    );
    assert.equal(parked.length, 0);
    await control.query('ALTER DATABASE notification_precision WITH ALLOW_CONNECTIONS true');
    databaseBlocked = false;
    await eventually(
      'recovered persistence must commit the retained offset',
      async () => (await committed()) >= initialOffset + 1,
    );
    assert.equal(
      Number((await pool.query('SELECT count(*) FROM notification')).rows[0].count),
      before + 1,
    );

    await send({ ...outage, messageId: randomUUID(), correlationId: randomUUID() });
    await eventually(
      'new-envelope replay must commit',
      async () => (await committed()) >= initialOffset + 2,
    );
    assert.equal(
      Number((await pool.query('SELECT count(*) FROM notification')).rows[0].count),
      before + 1,
    );

    await send({
      ...outage,
      messageId: randomUUID(),
      aggregateVersion: 5,
      payload: { ...outage.payload, actorType: 'SPECIALIST' },
    });
    await eventually(
      'specialist facts must be ignored',
      async () => (await committed()) >= initialOffset + 3,
    );
    assert.equal(
      Number((await pool.query('SELECT count(*) FROM notification')).rows[0].count),
      before + 1,
    );

    const disabledOwner = randomUUID();
    await pool.query(
      'INSERT INTO notification_preference (user_id, owner_type, group_resource_system_enabled) VALUES ($1, $2, false)',
      [disabledOwner, 'USER'],
    );
    await send({
      ...outage,
      messageId: randomUUID(),
      aggregateId: disabledOwner,
      payload: { ...outage.payload, recipientId: disabledOwner },
    });
    await eventually(
      'disabled preference must commit a cancelled outcome',
      async () => (await committed()) >= initialOffset + 4,
    );
    const disabled = await pool.query(
      'SELECT delivery_state FROM notification WHERE recipient_id = $1',
      [disabledOwner],
    );
    assert.deepEqual(disabled.rows, [{ delivery_state: 'CANCELLED' }]);
    assert.equal(
      Number(
        (await pool.query("SELECT count(*) FROM notification WHERE delivery_state = 'DELIVERED'"))
          .rows[0].count,
      ),
      before + 1,
    );

    await send({
      ...outage,
      messageId: randomUUID(),
      payload: { ...outage.payload, email: 'private@example.test' },
    });
    await eventually(
      'malformed fact must commit after digest-only parking',
      async () => (await committed()) >= initialOffset + 5,
    );
    await eventually(
      'malformed fact must appear in the dead-letter topic',
      async () => parked.length === 1,
    );
    assert.equal(parked[0].errorCode, 'INVALID_EVENT');
    assert.deepEqual(Object.keys(parked[0]).sort(), [
      'errorCode',
      'failedAt',
      'schemaVersion',
      'sourceMessageDigest',
      'sourceOffset',
      'sourcePartition',
      'sourceTopic',
    ]);
    assert.match(parked[0].sourceMessageDigest, /^[0-9a-f]{64}$/);
    assert.ok(!JSON.stringify(parked).includes('private@example.test'));
    console.log(
      `Verified Identity outage: ${failures} actual PostgreSQL failures, unchanged Kafka offset/no DLQ, recovery, new-envelope dedupe, specialist exclusion, disabled preference and digest-only poison parking`,
    );
  } finally {
    if (databaseBlocked)
      await control.query('ALTER DATABASE notification_precision WITH ALLOW_CONNECTIONS true');
    await consumer.onApplicationShutdown();
    await dlq.disconnect();
    await producer.disconnect();
    await admin.disconnect();
    await control.end();
  }
}
