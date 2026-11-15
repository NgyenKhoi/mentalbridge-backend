import { describe, expect, it, vi } from 'vitest';
import type { RedisService } from '../../src/database/redis.service.js';
import { SpecialistNotificationFanout } from '../../src/notifications/specialist-notification-fanout.js';
import { createMetrics } from '../../src/observability/metrics.js';
import { testConfiguration } from '../fixtures/test-configuration.js';

const fakes = vi.hoisted(() => {
  const consumer = {
    events: { CRASH: 'crash', GROUP_JOIN: 'join', REQUEST_TIMEOUT: 'timeout' },
    on: vi.fn(),
    connect: vi.fn(),
    disconnect: vi.fn(),
    subscribe: vi.fn(),
    run: vi.fn(),
  };
  const producer = { connect: vi.fn(), disconnect: vi.fn() };
  const subscriber = {
    isOpen: false,
    on: vi.fn(),
    connect: vi.fn(),
    subscribe: vi.fn(),
    destroy: vi.fn(),
  };
  return { consumer, producer, subscriber };
});

vi.mock('kafkajs', () => ({
  logLevel: { NOTHING: 0 },
  Kafka: class {
    consumer = () => fakes.consumer;
    producer = () => fakes.producer;
  },
}));
vi.mock('redis', () => ({ createClient: () => fakes.subscriber }));

describe('Specialist notification fanout lifecycle', () => {
  it('does not reopen Kafka when shutdown overlaps an in-flight recovery', async () => {
    let finishDisconnect = (): void => undefined;
    fakes.consumer.disconnect.mockReturnValueOnce(
      new Promise<void>((resolve) => {
        finishDisconnect = resolve;
      }),
    );
    fakes.consumer.disconnect.mockResolvedValue(undefined);
    fakes.producer.disconnect.mockResolvedValue(undefined);
    fakes.subscriber.connect.mockImplementation(() => {
      fakes.subscriber.isOpen = true;
      return Promise.resolve();
    });
    fakes.subscriber.subscribe.mockResolvedValue(undefined);
    const fanout = new SpecialistNotificationFanout(
      {} as unknown as RedisService,
      testConfiguration('synthetic'),
      createMetrics(testConfiguration('synthetic')),
      { warn: vi.fn() } as never,
    );
    const timers = vi.spyOn(globalThis, 'setInterval');

    const init = fanout.onModuleInit();
    await vi.waitFor(() => {
      expect(fakes.consumer.disconnect).toHaveBeenCalledTimes(1);
    });
    let shutdownDone = false;
    const shutdown = fanout.onApplicationShutdown().then(() => {
      shutdownDone = true;
    });
    await Promise.resolve();
    expect(shutdownDone).toBe(false);

    finishDisconnect();
    await Promise.all([init, shutdown]);

    expect(fakes.producer.connect).not.toHaveBeenCalled();
    expect(fakes.consumer.connect).not.toHaveBeenCalled();
    expect(fakes.consumer.subscribe).not.toHaveBeenCalled();
    expect(fakes.consumer.run).not.toHaveBeenCalled();
    expect(fakes.subscriber.destroy).toHaveBeenCalledTimes(1);
    expect(fakes.producer.disconnect).toHaveBeenCalledTimes(1);
    expect(timers).not.toHaveBeenCalled();
    timers.mockRestore();
  });
});
