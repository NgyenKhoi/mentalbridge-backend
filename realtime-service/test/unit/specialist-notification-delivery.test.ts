import { randomUUID } from 'node:crypto';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  SpecialistNotificationDelivery,
  type NotificationSocket,
} from '../../src/notifications/specialist-notification-delivery.js';
import type { SpecialistNotificationFanout } from '../../src/notifications/specialist-notification-fanout.js';
import type { ContentNotificationClient } from '../../src/notifications/content-notification.client.js';
import type { NotificationEligibility } from '../../src/notifications/content-notification.client.js';
import type { NotificationPointer } from '../../src/notifications/notification-schema.js';
import { createMetrics } from '../../src/observability/metrics.js';
import { testConfiguration } from '../fixtures/test-configuration.js';

const accountId = randomUUID();
const pointer = (): NotificationPointer => ({
  schemaVersion: 1,
  eventId: randomUUID(),
  correlationId: randomUUID(),
  occurredAt: new Date().toISOString(),
  notificationId: randomUUID(),
  recipientAccountId: accountId,
  expiresAt: new Date(Date.now() + 60_000).toISOString(),
});
const socket = (role: 'SPECIALIST' | 'USER' | 'ADMIN' = 'SPECIALIST', owner = accountId) => ({
  id: randomUUID(),
  connected: true,
  data: {
    principal: {
      accountId: owner,
      role,
      tokenId: randomUUID(),
      expiresAtEpochSeconds: Math.floor(Date.now() / 1000) + 60,
    },
    bearerToken: 'synthetic-identity-bearer',
  },
  emit: vi.fn(),
});
const settle = async () => {
  for (let index = 0; index < 100; index++) await Promise.resolve();
};

describe('Per-socket specialist notification delivery', () => {
  let delivery: SpecialistNotificationDelivery;
  const check = vi.fn<ContentNotificationClient['check']>();
  beforeEach(() => {
    vi.useFakeTimers();
    check.mockReset().mockResolvedValue('eligible');
    delivery = new SpecialistNotificationDelivery(
      { onPointer: vi.fn() } as unknown as SpecialistNotificationFanout,
      { check } as unknown as ContentNotificationClient,
      createMetrics(testConfiguration('synthetic')),
    );
  });
  afterEach(() => {
    delivery.onApplicationShutdown();
    vi.useRealTimers();
  });

  it('sends the minimal named event to every exact SPECIALIST device independently', async () => {
    const first = socket();
    const second = socket();
    const user = socket('USER');
    const admin = socket('ADMIN');
    const foreign = socket('SPECIALIST', randomUUID());
    for (const client of [first, second, user, admin, foreign]) delivery.add(client);
    const event = pointer();
    delivery.receive(event);
    await settle();
    for (const client of [first, second])
      expect(client.emit).toHaveBeenCalledExactlyOnceWith('notification.created', {
        schemaVersion: 1,
        eventId: event.eventId,
        eventType: 'notification.created',
        correlationId: event.correlationId,
        occurredAt: event.occurredAt,
        payload: { notificationId: event.notificationId },
      });
    for (const client of [user, admin, foreign]) expect(client.emit).not.toHaveBeenCalled();
    expect(check).toHaveBeenCalledTimes(2);
  });

  it('coalesces concurrent duplicates and deduplicates only after a successful emission', async () => {
    const client = socket();
    delivery.add(client);
    let release: (value: NotificationEligibility) => void = () => undefined;
    check.mockReturnValueOnce(
      new Promise((resolve) => {
        release = resolve;
      }),
    );
    const event = pointer();
    delivery.receive(event);
    delivery.receive(event);
    delivery.receive(event);
    expect(check).toHaveBeenCalledOnce();
    release('eligible');
    await settle();
    delivery.receive(event);
    await settle();
    expect(client.emit).toHaveBeenCalledOnce();
    expect(check).toHaveBeenCalledOnce();
  });

  it.each(['disconnect', 'remove', 'account', 'role', 'bearer', 'expiry', 'event-expiry'] as const)(
    'rechecks %s after the owner read',
    async (change) => {
      const client = socket();
      delivery.add(client);
      let release: (value: NotificationEligibility) => void = () => undefined;
      check.mockReturnValueOnce(
        new Promise((resolve) => {
          release = resolve;
        }),
      );
      const event = pointer();
      delivery.receive(event);
      if (change === 'disconnect') client.connected = false;
      if (change === 'remove') delivery.remove(client.id);
      if (change === 'account')
        client.data.principal = { ...client.data.principal, accountId: randomUUID() };
      if (change === 'role') client.data.principal = { ...client.data.principal, role: 'USER' };
      if (change === 'bearer') client.data.bearerToken = 'changed-bearer';
      if (change === 'expiry' || change === 'event-expiry') vi.setSystemTime(Date.now() + 60_001);
      release('eligible');
      await settle();
      expect(client.emit).not.toHaveBeenCalled();
    },
  );

  it('does not remember ineligible checks as delivered and retries a later fresh signal', async () => {
    const client = socket();
    delivery.add(client);
    const event = pointer();
    check.mockResolvedValueOnce('ineligible');
    delivery.receive(event);
    await settle();
    expect(client.emit).not.toHaveBeenCalled();
    delivery.receive(event);
    await settle();
    expect(client.emit).toHaveBeenCalledOnce();
  });

  it('bounds owner failures to three attempts and permits later fresh recovery', async () => {
    vi.useRealTimers();
    const client = socket();
    delivery.add(client);
    const event = pointer();
    check.mockResolvedValue('unavailable');
    delivery.receive(event);
    await new Promise((resolve) => setTimeout(resolve, 450));
    await settle();
    expect(check).toHaveBeenCalledTimes(3);
    expect(client.emit).not.toHaveBeenCalled();
    check.mockResolvedValue('eligible');
    delivery.receive(event);
    await settle();
    expect(client.emit).toHaveBeenCalledOnce();
  });

  it('drops excess backlog with metrics while bounding in-flight owner reads', async () => {
    const client = socket();
    delivery.add(client);
    let release: (value: NotificationEligibility) => void = () => undefined;
    check.mockReturnValueOnce(
      new Promise((resolve) => {
        release = resolve;
      }),
    );
    for (let index = 0; index < 80; index++) delivery.receive(pointer());
    expect(check).toHaveBeenCalledOnce();
    release('eligible');
    await settle();
    await settle();
    expect(check.mock.calls.length).toBeLessThanOrEqual(32);
    await settle();
    await settle();
    expect(client.emit).toHaveBeenCalledTimes(32);
  });

  it('bounds successful dedupe capacity and TTL without suppressing another socket', async () => {
    const client = socket();
    delivery.add(client);
    const first = pointer();
    delivery.receive(first);
    await settle();
    for (let index = 0; index < 256; index++) {
      delivery.receive(pointer());
      await settle();
    }
    delivery.receive(first);
    await settle();
    expect(client.emit).toHaveBeenCalledTimes(258);
    const second = socket();
    delivery.add(second);
    delivery.receive(first);
    await settle();
    expect(second.emit).toHaveBeenCalledOnce();
  });

  it('fails closed before I/O for missing bearer or expired event', async () => {
    const client: NotificationSocket = {
      ...socket(),
      data: { principal: socket().data.principal },
    };
    delivery.add(client);
    delivery.receive(pointer());
    const connected = socket();
    delivery.add(connected);
    delivery.receive({ ...pointer(), expiresAt: new Date(Date.now() - 1).toISOString() });
    await settle();
    expect(check).not.toHaveBeenCalled();
  });
});
