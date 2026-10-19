import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readdir, readFile } from 'node:fs/promises';
import { Kafka, logLevel } from 'kafkajs';
import { Pool } from 'pg';
import { GenericContainer, Wait } from 'testcontainers';
import { DatabaseService } from '../dist/database/database.service.js';
import { UserNotificationRepository } from '../dist/user-notifications/user-notification.repository.js';
import {
  CareUserNotificationFactSchema,
  IdentityUserNotificationFactSchema,
} from '../dist/user-notifications/user-notification.types.js';
import { CareUserNotificationProjector } from '../dist/user-notifications/care-user-notification.consumer.js';
import { AccountLifecycleProjector } from '../dist/notification-preferences/account-lifecycle.consumer.js';

const [owner, broker, expectedCount] = process.argv.slice(2);
const expected = Number(expectedCount);
assert.ok(['care', 'identity'].includes(owner) && expected > 0);
const topic =
  owner === 'care'
    ? 'mentalbridge.care.user-notification.v1'
    : 'mentalbridge.identity.account-lifecycle.v1';
const schema =
  owner === 'care' ? CareUserNotificationFactSchema : IdentityUserNotificationFactSchema;
const container = await new GenericContainer('postgres:17-alpine')
  .withEnvironment({ POSTGRES_PASSWORD: 'test-only', POSTGRES_DB: 'notification_precision' })
  .withExposedPorts(5432)
  .withWaitStrategy(Wait.forLogMessage(/database system is ready to accept connections/, 2))
  .start();
const databaseUrl = `postgresql://postgres:test-only@${container.getHost()}:${container.getMappedPort(5432)}/notification_precision`;
const pool = new Pool({ connectionString: databaseUrl });
const database = new DatabaseService({
  DATABASE_URL: databaseUrl,
  DB_POOL_MAX: 3,
  DB_IDLE_TIMEOUT_MS: 1000,
  DB_CONNECT_TIMEOUT_MS: 1000,
});
const kafka = new Kafka({
  clientId: 'mb565-precision',
  brokers: [broker],
  logLevel: logLevel.NOTHING,
});
const consumer = kafka.consumer({ groupId: `mb565-precision-${randomUUID()}` });
const facts = [];
let failure;
try {
  const directory = new URL('../migrations/', import.meta.url);
  const migrations = (await readdir(directory))
    .filter((name) => /^\d+_.+\.sql$/.test(name))
    .sort((left, right) => left.localeCompare(right, 'en', { numeric: true }));
  for (const name of migrations) {
    const sql = await readFile(new URL(name, directory), 'utf8');
    await pool.query(sql.split('-- Down Migration')[0]);
  }
  const repository = new UserNotificationRepository(database);
  const projector =
    owner === 'care'
      ? new CareUserNotificationProjector(repository)
      : new AccountLifecycleProjector({}, repository);
  await consumer.connect();
  await consumer.subscribe({ topic, fromBeginning: true });
  await consumer.run({
    eachMessage: async ({ message }) => {
      try {
        const candidate = JSON.parse(message.value.toString());
        if (candidate.messageType !== `${owner}.user-notification.fact`) return;
        const event = schema.parse(candidate);
        assert.equal(event.occurredAt, event.payload.availableAt);
        assert.match(event.occurredAt, /\.\d{6}Z$/);
        assert.ok(/\.(123456|999999)Z$/.test(event.occurredAt));
        await projector.project(event);
        await projector.project({ ...event, messageId: randomUUID(), correlationId: randomUUID() });
        assert.equal(
          schema.safeParse({
            ...event,
            occurredAt: '2026-10-19T08:00:02.000001Z',
          }).success,
          false,
        );
        facts.push(event);
      } catch (error) {
        failure = error;
      }
    },
  });
  const deadline = Date.now() + 30_000;
  while (facts.length < expected && !failure && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  if (failure) throw failure;
  assert.equal(facts.length, expected);
  const rows = await pool.query('SELECT category, delivery_state FROM notification');
  assert.equal(rows.rowCount, expected);
  assert.ok(rows.rows.every((row) => row.delivery_state === 'DELIVERED'));
  assert.deepEqual(
    [...new Set(rows.rows.map((row) => row.category))].sort(),
    owner === 'care'
      ? ['ASSESSMENT_RESULT_AVAILABLE', 'SUPPORT_GUIDE_AVAILABLE']
      : ['ACCOUNT_STATUS'],
  );
  if (owner === 'identity') {
    assert.deepEqual([...new Set(facts.map((event) => event.payload.factType))].sort(), [
      'ACCOUNT_ACTIVATED',
      'ACCOUNT_RESTORED',
    ]);
  }
  console.log(
    `Verified ${owner}: PostgreSQL outbox → relay → Kafka → strict Content validation → ${expected} durable inbox items, replay dedupe and timestamp rejection`,
  );
} finally {
  await consumer.disconnect();
  await database.onModuleDestroy();
  await pool.end();
  await container.stop();
}
