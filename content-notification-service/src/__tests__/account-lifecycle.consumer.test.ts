import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  AccountLifecycleConsumer,
  AccountLifecycleProjector,
  type AccountRegisteredEvent,
} from '../notification-preferences/account-lifecycle.consumer.js';
import { UserNotificationDedupeConflictError } from '../user-notifications/user-notification.repository.js';

const broker = vi.hoisted(() => ({
  send: vi.fn().mockResolvedValue([]),
  run: vi.fn().mockResolvedValue(undefined),
  consumer: vi.fn(),
}));

vi.mock('kafkajs', () => ({
  logLevel: { NOTHING: 0 },
  Kafka: class {
    consumer(options: unknown) {
      broker.consumer(options);
      return {
        connect: async () => {},
        subscribe: async () => {},
        run: broker.run,
        disconnect: async () => {},
      };
    }
    producer() {
      return { connect: async () => {}, send: broker.send, disconnect: async () => {} };
    }
  },
}));

const identityFact = {
  messageId: '41111111-1111-4111-8111-111111111111',
  messageType: 'identity.user-notification.fact',
  occurredAt: '2026-10-19T08:00:00.123456Z',
  producer: 'identity-service',
  schemaVersion: '1.0',
  correlationId: '42222222-2222-4222-8222-222222222222',
  aggregateId: '43333333-3333-4333-8333-333333333333',
  aggregateVersion: 1,
  payload: {
    recipientId: '43333333-3333-4333-8333-333333333333',
    actorType: 'USER',
    factType: 'ACCOUNT_ACTIVATED',
    availableAt: '2026-10-19T08:00:00.123456Z',
  },
};

describe('AccountLifecycleConsumer delivery reliability', () => {
  beforeEach(() => vi.clearAllMocks());

  async function setup(project = vi.fn().mockResolvedValue(undefined)) {
    const consumer = new AccountLifecycleConsumer(
      {
        CONTENT_ACCOUNT_LIFECYCLE_CONSUMER_ENABLED: 'true',
        KAFKA_BOOTSTRAP_SERVERS: 'kafka.test:9092',
      } as never,
      { project } as never,
    );
    await consumer.onModuleInit();
    return { consumer, project };
  }

  it('rejects after three storage failures without an offset acknowledgement or digest-only conversion, then recovers', async () => {
    const project = vi.fn().mockRejectedValue(new Error('private database failure'));
    const { consumer } = await setup(project);
    const value = Buffer.from(JSON.stringify(identityFact));

    for (let pass = 0; pass < 2; pass += 1) {
      await expect(consumer.handle('identity', 0, '7', null, value)).rejects.toMatchObject({
        name: 'AccountLifecyclePersistenceUnavailableError',
        message: 'Account lifecycle persistence is unavailable',
        retriable: true,
      });
    }
    expect(project).toHaveBeenCalledTimes(6);
    expect(broker.send).not.toHaveBeenCalled();
    expect(broker.consumer).toHaveBeenCalledWith({
      groupId: 'content-notification-account-preferences-v1',
      retry: { initialRetryTime: 1000, maxRetryTime: 30_000, retries: 5 },
    });

    project.mockResolvedValue(undefined);
    await expect(consumer.handle('identity', 0, '7', null, value)).resolves.toBeUndefined();
    expect(project).toHaveBeenCalledTimes(7);
    expect(broker.send).not.toHaveBeenCalled();
  });

  it('keeps registered-user preference initialization retryable on persistent database failure', async () => {
    const { consumer } = await setup(vi.fn().mockRejectedValue(new Error('unavailable')));
    await expect(
      consumer.handle('identity', 0, '8', null, Buffer.from(JSON.stringify(event('USER')))),
    ).rejects.toMatchObject({ retriable: true });
    expect(broker.send).not.toHaveBeenCalled();
  });

  it('parks malformed facts as minimal metadata without retaining private fields', async () => {
    const { consumer, project } = await setup();
    await consumer.handle(
      'identity',
      0,
      '9',
      null,
      Buffer.from(
        JSON.stringify({
          ...identityFact,
          payload: { ...identityFact.payload, email: 'private@example.test' },
        }),
      ),
    );
    expect(project).not.toHaveBeenCalled();
    expect(broker.send).toHaveBeenCalledOnce();
    const parked = JSON.parse(broker.send.mock.calls[0][0].messages[0].value);
    expect(Object.keys(parked).sort()).toEqual([
      'errorCode',
      'failedAt',
      'schemaVersion',
      'sourceMessageDigest',
      'sourceOffset',
      'sourcePartition',
      'sourceTopic',
    ]);
    expect(parked.errorCode).toBe('INVALID_EVENT');
    expect(parked.sourceMessageDigest).toMatch(/^[0-9a-f]{64}$/);
    expect(JSON.stringify(parked)).not.toContain('private@example.test');
  });

  it('parks a conflicting logical identity as poison without retrying storage indefinitely', async () => {
    const { consumer, project } = await setup(
      vi.fn().mockRejectedValue(new UserNotificationDedupeConflictError()),
    );
    await consumer.handle('identity', 0, '10', null, Buffer.from(JSON.stringify(identityFact)));
    expect(project).toHaveBeenCalledOnce();
    expect(JSON.parse(broker.send.mock.calls[0][0].messages[0].value).errorCode).toBe(
      'INVALID_EVENT',
    );
  });
});

const event = (actorType: 'USER' | 'SPECIALIST'): AccountRegisteredEvent => ({
  messageId: '11111111-1111-4111-8111-111111111111',
  messageType: 'identity.account.registered',
  occurredAt: '2026-09-28T08:00:00.000Z',
  producer: 'identity-service',
  schemaVersion: '1.0',
  correlationId: '22222222-2222-4222-8222-222222222222',
  aggregateId: '33333333-3333-4333-8333-333333333333',
  aggregateVersion: 0,
  payload: {
    accountId: '33333333-3333-4333-8333-333333333333',
    actorType,
    status: 'PENDING_EMAIL_VERIFICATION',
  },
});

describe('AccountLifecycleProjector', () => {
  it('initializes the default preference aggregate for a registered user', async () => {
    const initializeForAccount = vi.fn().mockResolvedValue(undefined);
    const projector = new AccountLifecycleProjector(
      { initializeForAccount } as never,
      { materialize: vi.fn() } as never,
    );

    await expect(projector.project(event('USER'))).resolves.toBeUndefined();

    expect(initializeForAccount).toHaveBeenCalledOnce();
    expect(initializeForAccount).toHaveBeenCalledWith(
      '33333333-3333-4333-8333-333333333333',
      'USER',
    );
  });

  it('initializes bounded notification preferences for specialist accounts', async () => {
    const initializeForAccount = vi.fn().mockResolvedValue(undefined);
    const projector = new AccountLifecycleProjector(
      { initializeForAccount } as never,
      { materialize: vi.fn() } as never,
    );

    await expect(projector.project(event('SPECIALIST'))).resolves.toBeUndefined();

    expect(initializeForAccount).toHaveBeenCalledWith(
      '33333333-3333-4333-8333-333333333333',
      'SPECIALIST',
    );
  });
});
